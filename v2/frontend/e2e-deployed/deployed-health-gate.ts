import type { APIResponse, Page } from '@playwright/test';
import {
  classifyReadOnlyRequest,
  deployedHealthRouteId,
  deployedQaOrigin,
  deployedSnapshotRouteId,
  deployedUiApiRouteIds,
  type DeployedUiApiRouteId
} from './read-only-network-policy';

export type DeployedHealthSafety = {
  execution_enabled: false;
  broker_adapter: 'disabled';
  build_sha: string;
};

export type PinnedDeployedSnapshot = {
  safety: DeployedHealthSafety;
  responses: Record<DeployedUiApiRouteId, unknown>;
};

const deployedHealthUrl = `${deployedQaOrigin}/health`;
const deployedSnapshotUrl = `${deployedQaOrigin}/api/v2/qa/deployed-read-only-snapshot`;
const requestOptions = {
  failOnStatusCode: false,
  maxRedirects: 0,
  timeout: 8_000,
  headers: { 'Cache-Control': 'no-store' }
} as const;

function hasExactKeys(value: Record<string, unknown>, expectedKeys: string[]) {
  return JSON.stringify(Object.keys(value).sort()) === JSON.stringify([...expectedKeys].sort());
}

function validatedSafetyBody(
  body: unknown,
  expectedBuildSha: string,
  errorCode = 'DEPLOYED_HEALTH_OR_SHA_MISMATCH'
): DeployedHealthSafety {
  if (!body || typeof body !== 'object') {
    throw new Error(errorCode);
  }
  const candidate = body as Record<string, unknown>;
  if (candidate.status !== 'UP'
    || candidate.execution_enabled !== false
    || candidate.broker_adapter !== 'disabled'
    || candidate.build_sha !== expectedBuildSha) {
    throw new Error(errorCode);
  }
  return {
    execution_enabled: false,
    broker_adapter: 'disabled',
    build_sha: expectedBuildSha
  };
}

async function readExactJsonResponse(response: APIResponse, expectedUrl: string, errorCode: string) {
  try {
    if (!response.ok() || response.url() !== expectedUrl) throw new Error(errorCode);
    try {
      return await response.json() as unknown;
    } catch {
      throw new Error(errorCode);
    }
  } finally {
    await response.dispose();
  }
}

function validatedSnapshotBody(
  body: unknown,
  expectedBuildSha: string
): PinnedDeployedSnapshot {
  if (!body || typeof body !== 'object') throw new Error('DEPLOYED_SNAPSHOT_OR_SHA_MISMATCH');
  const candidate = body as Record<string, unknown>;
  if (!hasExactKeys(candidate, [
    'schema_version',
    'status',
    'build_sha',
    'execution_enabled',
    'broker_adapter',
    'responses'
  ])) {
    throw new Error('DEPLOYED_SNAPSHOT_OR_SHA_MISMATCH');
  }
  const safety = validatedSafetyBody(
    candidate,
    expectedBuildSha,
    'DEPLOYED_SNAPSHOT_OR_SHA_MISMATCH'
  );
  if (candidate.schema_version !== '1.0'
    || !candidate.responses || typeof candidate.responses !== 'object'
    || Array.isArray(candidate.responses)) {
    throw new Error('DEPLOYED_SNAPSHOT_OR_SHA_MISMATCH');
  }
  const responses = candidate.responses as Record<string, unknown>;
  if (JSON.stringify(Object.keys(responses).sort())
    !== JSON.stringify([...deployedUiApiRouteIds].sort())) {
    throw new Error('DEPLOYED_SNAPSHOT_OR_SHA_MISMATCH');
  }
  const operationsStatus = responses['operations-status'];
  if (!operationsStatus || typeof operationsStatus !== 'object') {
    throw new Error('DEPLOYED_SNAPSHOT_OR_SHA_MISMATCH');
  }
  const status = operationsStatus as Record<string, unknown>;
  if (status.execution_enabled !== false
    || status.broker_adapter !== 'disabled'
    || status.build_sha !== expectedBuildSha
    || !Array.isArray(responses.strategies)
    || !Array.isArray(responses.accounts)
    || !Array.isArray(responses['market-data-catalog'])
    || !Array.isArray(responses['operations-audit-limit-30'])) {
    throw new Error('DEPLOYED_SNAPSHOT_OR_SHA_MISMATCH');
  }
  return {
    safety,
    responses: responses as Record<DeployedUiApiRouteId, unknown>
  };
}

export async function loadDeployedSnapshotAfterHealthGate(
  page: Page,
  expectedBuildSha: string,
  recordRouteId: (
    routeId: typeof deployedHealthRouteId | typeof deployedSnapshotRouteId
  ) => void
): Promise<PinnedDeployedSnapshot> {
  const healthDecision = classifyReadOnlyRequest(deployedHealthUrl, 'GET');
  const snapshotDecision = classifyReadOnlyRequest(deployedSnapshotUrl, 'GET');
  if (healthDecision.kind !== 'preflight'
    || healthDecision.routeId !== deployedHealthRouteId
    || snapshotDecision.kind !== 'preflight'
    || snapshotDecision.routeId !== deployedSnapshotRouteId) {
    throw new Error('DEPLOYED_PREFLIGHT_ROUTE_POLICY_INVALID');
  }

  recordRouteId(deployedHealthRouteId);
  const healthResponse = await page.context().request.get(deployedHealthUrl, requestOptions);
  const healthBody = await readExactJsonResponse(
    healthResponse,
    deployedHealthUrl,
    'DEPLOYED_HEALTH_READ_FAILED'
  );
  validatedSafetyBody(healthBody, expectedBuildSha);

  // This candidate-only endpoint is absent from old releases. If deployment changes
  // after health, it fails safely before any legacy UI endpoint can reach the server.
  recordRouteId(deployedSnapshotRouteId);
  const snapshotResponse = await page.context().request.get(deployedSnapshotUrl, requestOptions);
  const snapshotBody = await readExactJsonResponse(
    snapshotResponse,
    deployedSnapshotUrl,
    'DEPLOYED_SNAPSHOT_READ_FAILED'
  );
  return validatedSnapshotBody(snapshotBody, expectedBuildSha);
}
