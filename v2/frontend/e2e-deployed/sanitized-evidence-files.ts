import { createHash } from 'node:crypto';
import {
  chmodSync,
  lstatSync,
  readFileSync,
  readdirSync,
  realpathSync,
  renameSync,
  rmSync,
  writeFileSync
} from 'node:fs';
import { isAbsolute, relative, resolve } from 'node:path';

export type SanitizedEvidenceFile = {
  kind: string;
  path: string;
  sha256: string;
};

export type FinalizedSanitizedManifest = {
  writeSucceeded: boolean;
  validation: 'PASS' | 'FAIL';
};

function sha256(path: string) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

export function sanitizedEvidenceFile(
  evidenceRoot: string,
  path: string,
  kind: string
): SanitizedEvidenceFile | null {
  const absolutePath = resolve(path);
  const metadata = lstatSync(absolutePath);
  const currentUserId = process.getuid?.();
  if (!metadata.isFile()
    || typeof currentUserId !== 'number'
    || metadata.uid !== currentUserId
    || metadata.nlink !== 1) return null;
  const canonicalRoot = realpathSync(evidenceRoot);
  if (lstatSync(canonicalRoot).uid !== currentUserId) return null;
  const canonicalPath = realpathSync(absolutePath);
  const relativePath = relative(canonicalRoot, canonicalPath);
  if (relativePath === '' || relativePath.startsWith('..') || isAbsolute(relativePath)) return null;
  chmodSync(canonicalPath, 0o600);
  return { kind, path: relativePath.replaceAll('\\', '/'), sha256: sha256(canonicalPath) };
}

export function retainOnlySanitizedEvidence(
  evidenceRoot: string,
  evidence: readonly SanitizedEvidenceFile[]
) {
  const canonicalRoot = realpathSync(evidenceRoot);
  const resultsRoot = resolve(canonicalRoot, 'results');
  rmSync(resultsRoot, { recursive: true, force: true });

  const expected = new Map<string, string>();
  let evidenceMetadataValid = true;
  for (const item of evidence) {
    if (item.path.includes('/') || expected.has(item.path)) {
      evidenceMetadataValid = false;
      continue;
    }
    expected.set(item.path, item.sha256);
  }
  for (const entry of readdirSync(canonicalRoot, { withFileTypes: true })) {
    if (!expected.has(entry.name) || !entry.isFile() || entry.isSymbolicLink()) {
      rmSync(resolve(canonicalRoot, entry.name), { recursive: true, force: true });
    }
  }
  const retained = readdirSync(canonicalRoot, { withFileTypes: true });
  return evidenceMetadataValid
    && retained.length === expected.size
    && retained.every((entry) => {
      const expectedHash = expected.get(entry.name);
      const path = resolve(canonicalRoot, entry.name);
      return entry.isFile()
        && !entry.isSymbolicLink()
        && typeof expectedHash === 'string'
        && sha256(path) === expectedHash;
    });
}

export function finalizeSanitizedEvidenceManifest(
  evidenceRoot: string,
  exactTreeValidated: boolean
): FinalizedSanitizedManifest {
  let canonicalRoot: string;
  try {
    canonicalRoot = realpathSync(evidenceRoot);
  } catch {
    return { writeSucceeded: false, validation: 'FAIL' };
  }

  const manifestPath = resolve(canonicalRoot, 'manifest.json');
  const temporaryPath = resolve(canonicalRoot, '.manifest.finalizing.json');
  let validation: 'PASS' | 'FAIL' = exactTreeValidated ? 'PASS' : 'FAIL';
  let manifest: Record<string, unknown>;

  if (validation === 'PASS') {
    try {
      const parsed = JSON.parse(readFileSync(manifestPath, 'utf8')) as unknown;
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new Error('manifest must be an object');
      }
      manifest = parsed as Record<string, unknown>;
      if (manifest.runStatus !== 'PASS' && manifest.runStatus !== 'FAIL') {
        throw new Error('manifest runStatus must be PASS or FAIL');
      }
    } catch {
      validation = 'FAIL';
      manifest = {};
    }
  } else {
    // Never preserve a manifest that failed its exact-tree hash check: it may have
    // been modified after onEnd and could contain data outside the safe schema.
    manifest = {};
  }

  if (validation === 'FAIL') {
    manifest = {
      schemaVersion: '2.2',
      evidenceType: 'DEPLOYED_READ_ONLY_UI_QA',
      qaId: 'QA-ACC-002',
      runStatus: 'FAIL',
      artifactRetention: {
        sanitizedArtifactsOnly: false,
        rawRunnerOutputRetained: null,
        finalExitExactTreeRequired: true,
        finalExitValidation: 'FAIL'
      },
      safety: {
        realOrderSubmissionAllowed: false,
        resourceMutations: []
      },
      failureCategory: 'FINAL_EXIT_EXACT_TREE_VALIDATION_FAILED'
    };
  } else {
    manifest.artifactRetention = {
      sanitizedArtifactsOnly: true,
      rawRunnerOutputRetained: false,
      finalExitExactTreeRequired: true,
      finalExitValidation: 'PASS'
    };
  }

  try {
    rmSync(temporaryPath, { force: true });
    writeFileSync(temporaryPath, `${JSON.stringify(manifest, null, 2)}\n`, {
      encoding: 'utf8',
      mode: 0o600,
      flag: 'wx'
    });
    chmodSync(temporaryPath, 0o600);
    renameSync(temporaryPath, manifestPath);
    return { writeSucceeded: true, validation };
  } catch {
    try {
      rmSync(temporaryPath, { force: true });
    } catch {
      // The caller treats writeSucceeded=false as a hard run failure.
    }
    return { writeSucceeded: false, validation: 'FAIL' };
  }
}
