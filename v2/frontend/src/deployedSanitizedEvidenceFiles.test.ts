import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  chmodSync,
  linkSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  symlinkSync,
  unlinkSync,
  writeFileSync
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import DeployedEvidenceReporter from '../e2e-deployed/deployed-reporter';
import {
  finalizeSanitizedEvidenceManifest,
  retainOnlySanitizedEvidence,
  sanitizedEvidenceFile
} from '../e2e-deployed/sanitized-evidence-files';

const temporaryRoots: string[] = [];
const expectedBuildSha = '0'.repeat(40);
const requiredScreens = [
  {
    id: 'overview',
    label: '오늘의 운영',
    maskedRegions: ['recent-accounts', 'recent-strategies', 'shell-environment', 'summary-metrics']
  },
  {
    id: 'strategies',
    label: '전략 빌더',
    maskedRegions: ['shell-environment', 'strategy-catalog']
  },
  {
    id: 'research',
    label: '연구·검증',
    maskedRegions: ['research-strategy-selector', 'shell-environment']
  },
  {
    id: 'accounts',
    label: '계좌·위험',
    maskedRegions: ['account-catalog', 'shell-environment']
  },
  {
    id: 'data',
    label: '데이터',
    maskedRegions: ['market-data-catalog', 'shell-environment']
  },
  {
    id: 'operations',
    label: '안전·감사',
    maskedRegions: ['global-control-reason', 'operations-audit', 'shell-environment']
  }
] as const;
const expectedMasking =
  'every deployed screen uses its exact registered sensitive-region set with opaque Playwright locator masks';
const requiredApiRouteIds = [
  'health-safety-gate',
  'deployed-read-only-snapshot',
  'operations-status',
  'strategies',
  'accounts',
  'market-data-catalog',
  'operations-audit-limit-30'
];

function sha256(path: string) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

function deployedNetworkObservation() {
  return {
    allowedStaticRequestCount: 3,
    allowedApiRequestCount: requiredApiRouteIds.length,
    apiRequestCountByRouteId: Object.fromEntries(requiredApiRouteIds.map((routeId) => [routeId, 1])),
    preflightBackendGetCount: 2,
    uiApiLocalFulfillCount: requiredApiRouteIds.length - 2,
    uiApiBackendContinueCount: 0,
    unexpectedPageCount: 0,
    unsafeMethodCount: 0,
    externalRequestCount: 0,
    disallowedPathCount: 0,
    webSocketCount: 0,
    consoleErrorCount: 0,
    pageErrorCount: 0
  };
}

function temporaryRoot() {
  const root = mkdtempSync(join(tmpdir(), 'wallant-deployed-evidence-'));
  temporaryRoots.push(root);
  return root;
}

function writeIndependentVerifierFixture(root: string) {
  chmodSync(root, 0o700);
  const projects = [
    { project: 'deployed-desktop-readonly', viewport: { width: 1440, height: 1000 } },
    { project: 'deployed-mobile-readonly', viewport: { width: 360, height: 800 } }
  ] as const;
  const network = deployedNetworkObservation();
  const scenarios = projects.map(({ project, viewport }) => {
    const observationName = `QA-ACC-002-${project}-sanitized-observation.json`;
    const observationPath = join(root, observationName);
    const screenFixture = requiredScreens.map((screen) => {
      const screenshotName = `QA-ACC-002-${project}-${screen.id}-masked.png`;
      const screenshotPath = join(root, screenshotName);
      writeFileSync(
        screenshotPath,
        Buffer.from(
          'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2w9sAAAAASUVORK5CYII=',
          'base64'
        ),
        { mode: 0o600 }
      );
      chmodSync(screenshotPath, 0o600);
      const screenshotHash = sha256(screenshotPath);
      return {
        observation: {
          id: screen.id,
          label: screen.label,
          horizontalOverflowPx: 0,
          maskedRegions: [...screen.maskedRegions],
          screenshot: {
            fileName: screenshotName,
            sha256: screenshotHash,
            masking: expectedMasking
          }
        },
        evidence: {
          kind: 'opaque-masked-screenshot',
          path: screenshotName,
          sha256: screenshotHash
        }
      };
    });
    const observation = {
      schemaVersion: '2.3',
      observationType: 'DEPLOYED_READ_ONLY_UI_QA_SANITIZED_OBSERVATION',
      qaId: 'QA-ACC-002',
      environment: 'deployed',
      baseUrlHost: 'app.wall-ant.com',
      project,
      browserName: 'chromium',
      viewport,
      screens: screenFixture.map((screen) => screen.observation),
      expectedBuildSha,
      observedBuildSha: expectedBuildSha,
      observedUiBuildSha: expectedBuildSha,
      qaStartedAt: '2026-08-05T00:00:00.000Z',
      observationFinishedAt: '2026-08-05T00:01:00.000Z',
      operationsSafety: { execution_enabled: false, broker_adapter: 'disabled' },
      harnessSafety: {
        realOrderSubmissionAllowed: false,
        requestPolicy: 'candidate-only snapshot; allowlisted UI GET fulfilled locally; exact-origin static GET/HEAD only',
        resourceMutationCount: 0
      },
      network,
      excludedSecretCategories: [
        'cookies',
        'authorization headers',
        'OTP',
        'Access tokens',
        'account numbers',
        'request headers',
        'query strings'
      ],
      automaticPlaywrightCapture: { trace: false, screenshotOnFailure: false, video: false },
      validation: { status: 'PASS', errorCategories: [] }
    };
    writeFileSync(observationPath, `${JSON.stringify(observation, null, 2)}\n`, { mode: 0o600 });
    chmodSync(observationPath, 0o600);
    return {
      qaId: 'QA-ACC-002',
      project,
      browserName: 'chromium',
      viewport,
      screens: screenFixture.map((screen) => screen.observation),
      status: 'PASS',
      playwrightStatus: 'passed',
      errorCategories: [],
      observedBuildSha: expectedBuildSha,
      observedUiBuildSha: expectedBuildSha,
      operationsSafety: { execution_enabled: false, broker_adapter: 'disabled' },
      network,
      startedAt: '2026-08-05T00:00:00.000Z',
      finishedAt: '2026-08-05T00:01:00.000Z',
      evidence: [
        ...screenFixture.map((screen) => screen.evidence),
        { kind: 'sanitized-observation', path: observationName, sha256: sha256(observationPath) }
      ]
    };
  });
  const manifestPath = join(root, 'manifest.json');
  writeFileSync(manifestPath, `${JSON.stringify({
    schemaVersion: '2.2',
    evidenceType: 'DEPLOYED_READ_ONLY_UI_QA',
    qaId: 'QA-ACC-002',
    runStatus: 'PASS',
    expectedBuildSha,
    harnessGit: { sha: expectedBuildSha, workingTreeDirty: false, matchesDeployedSha: true },
    runtimeMatrix: projects.map(({ project, viewport }) => ({ project, browserName: 'chromium', viewport })),
    baseUrlHost: 'app.wall-ant.com',
    exactOrigin: 'https://app.wall-ant.com',
    requestPolicy: 'candidate-only snapshot; allowlisted UI GET fulfilled locally; exact-origin static GET/HEAD only',
    automaticCapture: { trace: false, screenshotOnFailure: false, video: false },
    artifactRetention: {
      sanitizedArtifactsOnly: true,
      rawRunnerOutputRetained: false,
      finalExitExactTreeRequired: true,
      finalExitValidation: 'PASS'
    },
    safety: { realOrderSubmissionAllowed: false, resourceMutations: [] },
    summary: { expectedScenarios: 2, actualScenarios: 2, passed: 2, failed: 0, matrixValid: true },
    scenarios,
    finalizedAt: '2026-08-05T00:01:00.000Z'
  }, null, 2)}\n`, { mode: 0o600 });
  chmodSync(manifestPath, 0o600);
  return {
    screenshotNames: scenarios.flatMap((scenario) => scenario.evidence
      .filter((item) => item.kind === 'opaque-masked-screenshot')
      .map((item) => item.path)),
    projects,
    scenarios
  };
}

afterEach(() => {
  for (const root of temporaryRoots.splice(0)) {
    rmSync(root, { recursive: true, force: true });
  }
});

describe('배포 QA 증적 파일 경계', () => {
  it('root 밖 파일과 symlink를 거부하고 raw runner 산출물을 제거한다', () => {
    const root = temporaryRoot();
    const outsideRoot = temporaryRoot();
    const safePath = join(root, 'QA-ACC-002-safe.json');
    const outsidePath = join(outsideRoot, 'outside.json');
    const symlinkPath = join(root, 'outside-link.json');
    writeFileSync(safePath, '{"safe":true}\n');
    writeFileSync(outsidePath, '{"secret":"not-retained"}\n');
    symlinkSync(outsidePath, symlinkPath);

    const safe = sanitizedEvidenceFile(root, safePath, 'sanitized-observation');
    expect(safe).not.toBeNull();
    expect(sanitizedEvidenceFile(root, outsidePath, 'outside')).toBeNull();
    expect(sanitizedEvidenceFile(root, symlinkPath, 'symlink')).toBeNull();

    mkdirSync(join(root, 'results'), { recursive: true });
    writeFileSync(join(root, 'results', 'raw-error-context.md'), 'raw sentinel');
    writeFileSync(join(root, 'unexpected-root-file.txt'), 'raw sentinel');

    expect(retainOnlySanitizedEvidence(root, [safe!])).toBe(true);
    expect(readdirSync(root)).toEqual(['QA-ACC-002-safe.json']);
    expect(readFileSync(safePath, 'utf8')).toBe('{"safe":true}\n');
  });

  it('root 안 hardlink 증적을 chmod 전에 거부해 밖 원본 mode를 바꾸지 않는다', () => {
    const root = temporaryRoot();
    const outsideRoot = temporaryRoot();
    const outsidePath = join(outsideRoot, 'outside.json');
    const hardlinkPath = join(root, 'hardlink.json');
    writeFileSync(outsidePath, '{"safe":false}\n', { mode: 0o640 });
    chmodSync(outsidePath, 0o640);
    linkSync(outsidePath, hardlinkPath);

    expect(sanitizedEvidenceFile(root, hardlinkPath, 'hardlink')).toBeNull();
    expect((lstatSync(outsidePath).mode & 0o777)).toBe(0o640);
  });

  it('보존 대상 hash가 바뀌면 exact-tree 검증을 실패시킨다', () => {
    const root = temporaryRoot();
    const safePath = join(root, 'QA-ACC-002-safe.json');
    writeFileSync(safePath, '{"safe":true}\n');
    const safe = sanitizedEvidenceFile(root, safePath, 'sanitized-observation');
    expect(safe).not.toBeNull();
    writeFileSync(safePath, '{"tampered":true}\n');

    expect(retainOnlySanitizedEvidence(root, [safe!])).toBe(false);
  });

  it('중복 evidence metadata가 있어도 raw root 파일을 지운 뒤 실패한다', () => {
    const root = temporaryRoot();
    const safePath = join(root, 'QA-ACC-002-safe.json');
    writeFileSync(safePath, '{"safe":true}\n');
    const safe = sanitizedEvidenceFile(root, safePath, 'sanitized-observation');
    expect(safe).not.toBeNull();
    writeFileSync(join(root, 'unexpected-root-file.txt'), 'raw sentinel');
    mkdirSync(join(root, 'nested'), { recursive: true });
    writeFileSync(join(root, 'nested', 'raw.txt'), 'raw sentinel');

    expect(retainOnlySanitizedEvidence(root, [safe!, safe!, {
      ...safe!,
      path: 'nested/raw.txt'
    }])).toBe(false);
    expect(readdirSync(root)).toEqual(['QA-ACC-002-safe.json']);
  });

  it('최종 exact-tree 성공을 manifest에 원자적으로 확정한다', () => {
    const root = temporaryRoot();
    const manifestPath = join(root, 'manifest.json');
    writeFileSync(manifestPath, JSON.stringify({
      schemaVersion: '2.0',
      runStatus: 'PASS',
      artifactRetention: { finalExitValidation: 'PENDING' }
    }));

    expect(finalizeSanitizedEvidenceManifest(root, true)).toEqual({
      writeSucceeded: true,
      validation: 'PASS'
    });
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
    expect(manifest.runStatus).toBe('PASS');
    expect(manifest.artifactRetention).toEqual({
      sanitizedArtifactsOnly: true,
      rawRunnerOutputRetained: false,
      finalExitExactTreeRequired: true,
      finalExitValidation: 'PASS'
    });
    expect(readdirSync(root)).toEqual(['manifest.json']);
  });

  it('최종 exact-tree 실패는 기존 PASS 내용을 폐기하고 FAIL manifest만 남긴다', () => {
    const root = temporaryRoot();
    const manifestPath = join(root, 'manifest.json');
    writeFileSync(manifestPath, JSON.stringify({
      runStatus: 'PASS',
      unsafeUnexpectedField: 'must not be retained'
    }));

    expect(finalizeSanitizedEvidenceManifest(root, false)).toEqual({
      writeSucceeded: true,
      validation: 'FAIL'
    });
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
    expect(manifest).not.toHaveProperty('unsafeUnexpectedField');
    expect(manifest.runStatus).toBe('FAIL');
    expect(manifest.failureCategory).toBe('FINAL_EXIT_EXACT_TREE_VALIDATION_FAILED');
    expect(manifest.artifactRetention.finalExitValidation).toBe('FAIL');
    expect(readdirSync(root)).toEqual(['manifest.json']);
  });

  it('scenario가 빠진 reporter 결과도 최종 tree를 확정하되 명시적 FAIL로 반환한다', async () => {
    const root = temporaryRoot();
    const reporter = new DeployedEvidenceReporter({
      evidenceRoot: root,
      expectedBuildSha: '0'.repeat(40),
      harnessGitSha: '0'.repeat(40)
    });
    const result = await reporter.onEnd({ status: 'passed' } as never);

    expect(result).toEqual({ status: 'failed' });
    const manifest = JSON.parse(readFileSync(join(root, 'manifest.json'), 'utf8'));
    expect(manifest.runStatus).toBe('FAIL');
    expect(manifest.artifactRetention.finalExitValidation).toBe('PASS');
    expect(readdirSync(root)).toEqual(['manifest.json']);
  });

  it('reporter onEnd 내부 I/O 예외도 삼켜진 성공이 아니라 명시적 실패로 반환한다', async () => {
    const root = temporaryRoot();
    const reporter = new DeployedEvidenceReporter({
      evidenceRoot: root,
      expectedBuildSha: '0'.repeat(40),
      harnessGitSha: '0'.repeat(40)
    });
    rmSync(root, { recursive: true, force: true });
    const result = await reporter.onEnd({ status: 'passed' } as never);
    expect(result).toEqual({ status: 'failed' });
  });

  it('raw 6화면 attachment를 viewport별로 정제해 12 PNG exact bundle을 만든다', async () => {
    const root = temporaryRoot();
    chmodSync(root, 0o700);
    const projects = [
      { project: 'deployed-desktop-readonly', viewport: { width: 1440, height: 1000 } },
      { project: 'deployed-mobile-readonly', viewport: { width: 360, height: 800 } }
    ] as const;
    const reporter = new DeployedEvidenceReporter({
      evidenceRoot: root,
      expectedBuildSha,
      harnessGitSha: expectedBuildSha
    });
    const reporterInternals = reporter as unknown as {
      runtimeByProject: Map<string, { browserName: string; viewport: { width: number; height: number } }>;
      runtimeConfigValid: boolean;
    };
    reporterInternals.runtimeConfigValid = true;

    for (const { project, viewport } of projects) {
      reporterInternals.runtimeByProject.set(project, { browserName: 'chromium', viewport });
      const resultRoot = join(root, 'results', project);
      mkdirSync(resultRoot, { recursive: true, mode: 0o700 });
      const attachments: Array<{
        name: string;
        contentType: string;
        path?: string;
        body?: Buffer;
      }> = [];
      const screens = requiredScreens.map((screen) => {
        const screenshotName = `QA-ACC-002-${project}-${screen.id}-masked.png`;
        const screenshotPath = join(resultRoot, screenshotName);
        writeFileSync(
          screenshotPath,
          Buffer.from(
            'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2w9sAAAAASUVORK5CYII=',
            'base64'
          ),
          { mode: 0o600 }
        );
        attachments.push({
          name: `masked-deployed-screenshot:${screen.id}`,
          contentType: 'image/png',
          path: screenshotPath
        });
        return {
          id: screen.id,
          label: screen.label,
          horizontalOverflowPx: 0,
          maskedRegions: [...screen.maskedRegions],
          screenshot: {
            fileName: screenshotName,
            sha256: sha256(screenshotPath),
            masking: expectedMasking
          }
        };
      });
      attachments.push({
        name: 'deployed-readonly-observation',
        contentType: 'application/json',
        body: Buffer.from(`${JSON.stringify({
          schemaVersion: '2.2',
          observationType: 'DEPLOYED_READ_ONLY_UI_QA_OBSERVATION',
          qaId: 'QA-ACC-002',
          environment: 'deployed',
          baseUrlHost: 'app.wall-ant.com',
          project,
          browserName: 'chromium',
          viewport,
          screens,
          expectedBuildSha,
          observedBuildSha: expectedBuildSha,
          observedUiBuildSha: expectedBuildSha,
          qaStartedAt: '2026-08-05T00:00:00.000Z',
          observationFinishedAt: '2026-08-05T00:01:00.000Z',
          operationsSafety: { execution_enabled: false, broker_adapter: 'disabled' },
          harnessSafety: {
            realOrderSubmissionAllowed: false,
            requestPolicy: 'candidate-only snapshot; allowlisted UI GET fulfilled locally; exact-origin static GET/HEAD only',
            resourceMutations: []
          },
          network: deployedNetworkObservation(),
          excludedSecrets: [
            'cookies',
            'authorization headers',
            'OTP',
            'Access tokens',
            'account numbers',
            'request headers',
            'query strings'
          ],
          automaticPlaywrightCapture: { trace: false, screenshotOnFailure: false, video: false }
        }, null, 2)}\n`, 'utf8')
      });
      reporter.onTestEnd({
        parent: { project: () => ({ name: project }) },
        titlePath: () => ['QA-ACC-002'],
        expectedStatus: 'passed'
      } as never, {
        status: 'passed',
        attachments,
        startTime: new Date('2026-08-05T00:00:00.000Z'),
        duration: 60_000
      } as never);
    }

    expect(await reporter.onEnd({ status: 'passed' } as never)).toBeUndefined();
    const entries = readdirSync(root);
    expect(entries.filter((entry) => entry.endsWith('.png'))).toHaveLength(12);
    expect(entries.filter((entry) => entry.endsWith('-sanitized-observation.json'))).toHaveLength(2);
    expect(entries).toHaveLength(15);
  });

  it('reporter가 만든 exact PASS bundle을 독립 verifier가 통과시키고 hash 변조는 거부한다', async () => {
    const root = temporaryRoot();
    const outsideRoot = temporaryRoot();
    const fixture = writeIndependentVerifierFixture(root);
    unlinkSync(join(root, 'manifest.json'));
    const reporter = new DeployedEvidenceReporter({
      evidenceRoot: root,
      expectedBuildSha,
      harnessGitSha: expectedBuildSha
    });
    const reporterInternals = reporter as unknown as {
      scenarios: typeof fixture.scenarios;
      runtimeByProject: Map<string, { browserName: string; viewport: { width: number; height: number } }>;
      runtimeConfigValid: boolean;
    };
    reporterInternals.scenarios.push(...fixture.scenarios);
    for (const { project, viewport } of fixture.projects) {
      reporterInternals.runtimeByProject.set(project, { browserName: 'chromium', viewport });
    }
    reporterInternals.runtimeConfigValid = true;
    expect(await reporter.onEnd({ status: 'passed' } as never)).toBeUndefined();
    expect(fixture.screenshotNames).toHaveLength(12);
    expect(readdirSync(root)).toHaveLength(15);

    const verifierPath = join(process.cwd(), 'e2e-deployed', 'verify-sanitized-evidence.mjs');
    const environment = {
      ...process.env,
      DEPLOYED_QA_OUTPUT_DIR: root,
      DEPLOYED_QA_EXPECTED_SHA: expectedBuildSha
    };

    const passed = spawnSync(process.execPath, [verifierPath], { env: environment, encoding: 'utf8' });
    expect(passed.status, passed.stderr).toBe(0);
    expect(passed.stdout).toContain('DEPLOYED_READ_ONLY_UI_QA VERIFIED_PASS');

    const firstScreenshotPath = join(root, fixture.screenshotNames[0]!);
    chmodSync(firstScreenshotPath, 0o700);
    const wrongFileMode = spawnSync(process.execPath, [verifierPath], {
      env: environment,
      encoding: 'utf8'
    });
    expect(wrongFileMode.status).toBe(1);
    expect(wrongFileMode.stderr).toContain(
      'DEPLOYED_READ_ONLY_UI_QA VERIFIED_FAIL EVIDENCE_PERMISSIONS_NOT_600'
    );
    chmodSync(firstScreenshotPath, 0o600);

    chmodSync(root, 0o500);
    const wrongRootMode = spawnSync(process.execPath, [verifierPath], {
      env: environment,
      encoding: 'utf8'
    });
    expect(wrongRootMode.status).toBe(1);
    expect(wrongRootMode.stderr).toContain(
      'DEPLOYED_READ_ONLY_UI_QA VERIFIED_FAIL OUTPUT_ROOT_PERMISSIONS_NOT_700'
    );
    chmodSync(root, 0o700);

    const hardlinkPath = join(outsideRoot, 'hardlinked-evidence.png');
    linkSync(join(root, fixture.screenshotNames[0]!), hardlinkPath);
    const hardlinked = spawnSync(process.execPath, [verifierPath], { env: environment, encoding: 'utf8' });
    expect(hardlinked.status).toBe(1);
    expect(hardlinked.stderr).toContain('DEPLOYED_READ_ONLY_UI_QA VERIFIED_FAIL EVIDENCE_LINK_COUNT_INVALID');
    unlinkSync(hardlinkPath);

    const manifestPath = join(root, 'manifest.json');
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
    const originalEvidence = [...manifest.scenarios[0].evidence];
    manifest.scenarios[0].evidence = originalEvidence.slice(1);
    writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o600 });
    chmodSync(manifestPath, 0o600);
    const missingScreenshot = spawnSync(process.execPath, [verifierPath], {
      env: environment,
      encoding: 'utf8'
    });
    expect(missingScreenshot.status).toBe(1);
    expect(missingScreenshot.stderr).toContain(
      'DEPLOYED_READ_ONLY_UI_QA VERIFIED_FAIL SCENARIO_EVIDENCE_COUNT_INVALID'
    );

    manifest.scenarios[0].evidence = [...originalEvidence, originalEvidence[0]];
    writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o600 });
    chmodSync(manifestPath, 0o600);
    const duplicateScreenshot = spawnSync(process.execPath, [verifierPath], {
      env: environment,
      encoding: 'utf8'
    });
    expect(duplicateScreenshot.status).toBe(1);
    expect(duplicateScreenshot.stderr).toContain(
      'DEPLOYED_READ_ONLY_UI_QA VERIFIED_FAIL SCENARIO_EVIDENCE_COUNT_INVALID'
    );

    manifest.scenarios[0].evidence = originalEvidence;
    const originalRegions = [...manifest.scenarios[0].screens[0].maskedRegions];
    manifest.scenarios[0].screens[0].maskedRegions = originalRegions.slice(1);
    writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o600 });
    chmodSync(manifestPath, 0o600);
    const missingMaskRegion = spawnSync(process.execPath, [verifierPath], {
      env: environment,
      encoding: 'utf8'
    });
    expect(missingMaskRegion.status).toBe(1);
    expect(missingMaskRegion.stderr).toContain(
      'DEPLOYED_READ_ONLY_UI_QA VERIFIED_FAIL SCREEN_MASKING_REGIONS_INVALID'
    );
    manifest.scenarios[0].screens[0].maskedRegions = originalRegions;

    const observationEvidence = manifest.scenarios[0].evidence.find(
      (item: { kind: string }) => item.kind === 'sanitized-observation'
    );
    const observationPath = join(root, observationEvidence.path);
    const observation = JSON.parse(readFileSync(observationPath, 'utf8'));
    observation.rawError = 'must never be retained';
    writeFileSync(observationPath, `${JSON.stringify(observation, null, 2)}\n`, { mode: 0o600 });
    chmodSync(observationPath, 0o600);
    observationEvidence.sha256 = sha256(observationPath);
    writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o600 });
    chmodSync(manifestPath, 0o600);
    const extraField = spawnSync(process.execPath, [verifierPath], { env: environment, encoding: 'utf8' });
    expect(extraField.status).toBe(1);
    expect(extraField.stderr).toContain(
      'DEPLOYED_READ_ONLY_UI_QA VERIFIED_FAIL OBSERVATION_FIELD_SET_INVALID'
    );

    delete observation.rawError;
    const canonicalObservation = `${JSON.stringify(observation, null, 2)}\n`;
    const duplicateKeyObservation = canonicalObservation.replace(
      '{\n',
      '{\n  "schemaVersion": "2.3",\n'
    );
    writeFileSync(observationPath, duplicateKeyObservation, { mode: 0o600 });
    chmodSync(observationPath, 0o600);
    observationEvidence.sha256 = sha256(observationPath);
    writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o600 });
    chmodSync(manifestPath, 0o600);
    const duplicateKey = spawnSync(process.execPath, [verifierPath], { env: environment, encoding: 'utf8' });
    expect(duplicateKey.status).toBe(1);
    expect(duplicateKey.stderr).toContain(
      'DEPLOYED_READ_ONLY_UI_QA VERIFIED_FAIL OBSERVATION_JSON_NOT_CANONICAL'
    );

    writeFileSync(join(root, fixture.screenshotNames[0]!), 'tampered', { mode: 0o600 });
    const failed = spawnSync(process.execPath, [verifierPath], { env: environment, encoding: 'utf8' });
    expect(failed.status).toBe(1);
    expect(failed.stderr).toContain('DEPLOYED_READ_ONLY_UI_QA VERIFIED_FAIL EVIDENCE_HASH_MISMATCH');
  });
});
