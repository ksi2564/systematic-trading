import { expect, test } from '@playwright/test';

import { loadDeployedSnapshotAfterHealthGate } from '../e2e-deployed/deployed-health-gate';
import {
  applyPinnedReadOnlyDecision,
  installPinnedReadOnlyBrowserPolicy,
  type DeployedNetworkObservation
} from '../e2e-deployed/pinned-ui-routing';
import {
  allowedApiRouteIds,
  classifyReadOnlyRequest,
  deployedHealthRouteId,
  deployedSnapshotRouteId
} from '../e2e-deployed/read-only-network-policy';

const expectedBuildSha = '1'.repeat(40);
const healthUrl = 'https://app.wall-ant.com/health';
const snapshotUrl = 'https://app.wall-ant.com/api/v2/qa/deployed-read-only-snapshot';
const validSafety = {
  status: 'UP',
  execution_enabled: false,
  broker_adapter: 'disabled',
  build_sha: expectedBuildSha
};
const validResponses = {
  'operations-status': {
    service: 'UP',
    execution_enabled: false,
    broker_adapter: 'disabled',
    build_sha: expectedBuildSha
  },
  strategies: [],
  accounts: [],
  'market-data-catalog': [],
  'operations-audit-limit-30': []
};

function emptyNetworkObservation(): DeployedNetworkObservation {
  return {
    allowedStaticRequestCount: 0,
    allowedApiRequestCount: 0,
    apiRequestCountByRouteId: Object.fromEntries(
      allowedApiRouteIds.map((routeId) => [routeId, 0])
    ),
    preflightBackendGetCount: 0,
    uiApiLocalFulfillCount: 0,
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

type FakeResponseOptions = {
  ok?: boolean;
  url?: string;
  body?: unknown;
  jsonFails?: boolean;
};

function response(options: FakeResponseOptions) {
  let disposed = false;
  return {
    ok: () => options.ok ?? true,
    url: () => options.url!,
    json: async () => {
      if (options.jsonFails) throw new Error('not json');
      return options.body;
    },
    dispose: async () => {
      disposed = true;
    },
    disposed: () => disposed
  };
}

function fakePage({
  health = response({ url: healthUrl, body: validSafety }),
  snapshot = response({
    url: snapshotUrl,
    body: { schema_version: '1.0', ...validSafety, responses: validResponses }
  })
} = {}) {
  const requestedUrls: string[] = [];
  const requestOptions: unknown[] = [];
  const routeIds: string[] = [];
  const page = {
    context: () => ({
      request: {
        get: async (url: string, options: unknown) => {
          requestedUrls.push(url);
          requestOptions.push(options);
          return url === healthUrl ? health : snapshot;
        }
      }
    })
  };
  return {
    page,
    health,
    snapshot,
    requestedUrls,
    requestOptions,
    routeIds,
    recordRouteId: (routeId: string) => routeIds.push(routeId)
  };
}

for (const [label, health] of [
  ['302 redirect', response({ ok: false, url: healthUrl, body: validSafety })],
  ['503', response({ ok: false, url: healthUrl, body: validSafety })],
  ['wrong response URL', response({ url: 'https://app.wall-ant.com/', body: validSafety })],
  ['non-JSON', response({ url: healthUrl, jsonFails: true })]
] as const) {
  test(`health ${label}이면 candidate snapshot과 UI API 요청이 0이다`, async () => {
    const fixture = fakePage({ health });
    await expect(loadDeployedSnapshotAfterHealthGate(
      fixture.page as never,
      expectedBuildSha,
      fixture.recordRouteId
    )).rejects.toThrow('DEPLOYED_HEALTH_READ_FAILED');
    expect(fixture.requestedUrls).toEqual([healthUrl]);
    expect(fixture.routeIds).toEqual([deployedHealthRouteId]);
    expect(fixture.health.disposed()).toBe(true);
  });
}

for (const [label, healthBody] of [
  ['status', { ...validSafety, status: 'DOWN' }],
  ['build_sha', { ...validSafety, build_sha: '2'.repeat(40) }],
  ['execution_enabled', { ...validSafety, execution_enabled: true }],
  ['broker_adapter', { ...validSafety, broker_adapter: 'live' }]
] as const) {
  test(`health ${label} 불일치면 candidate snapshot과 UI API 요청이 0이다`, async () => {
    const fixture = fakePage({ health: response({ url: healthUrl, body: healthBody }) });
    await expect(loadDeployedSnapshotAfterHealthGate(
      fixture.page as never,
      expectedBuildSha,
      fixture.recordRouteId
    )).rejects.toThrow('DEPLOYED_HEALTH_OR_SHA_MISMATCH');
    expect(fixture.requestedUrls).toEqual([healthUrl]);
    expect(fixture.routeIds).toEqual([deployedHealthRouteId]);
  });
}

test('expected health 직후 old release로 바뀌면 candidate-only snapshot 404로 끝난다', async () => {
  const fixture = fakePage({
    snapshot: response({ ok: false, url: snapshotUrl, body: { detail: 'Not Found' } })
  });
  await expect(loadDeployedSnapshotAfterHealthGate(
    fixture.page as never,
    expectedBuildSha,
    fixture.recordRouteId
  )).rejects.toThrow('DEPLOYED_SNAPSHOT_READ_FAILED');
  expect(fixture.requestedUrls).toEqual([healthUrl, snapshotUrl]);
  expect(fixture.routeIds).toEqual([deployedHealthRouteId, deployedSnapshotRouteId]);
  expect(fixture.snapshot.disposed()).toBe(true);
});

for (const [label, snapshotBody] of [
  [
    '안전값이 다르면',
    { schema_version: '1.0', ...validSafety, execution_enabled: true, responses: validResponses }
  ],
  [
    '미등록 최상위 필드가 있으면',
    { schema_version: '1.0', ...validSafety, responses: validResponses, secret: 'must-not-pass' }
  ],
  [
    '화면 응답 키가 빠지면',
    {
      schema_version: '1.0',
      ...validSafety,
      responses: {
        'operations-status': validResponses['operations-status'],
        strategies: [],
        'market-data-catalog': [],
        'operations-audit-limit-30': []
      }
    }
  ]
] as const) {
  test(`candidate snapshot ${label} SPA 전에 실패한다`, async () => {
    const fixture = fakePage({
      snapshot: response({ url: snapshotUrl, body: snapshotBody })
    });
    await expect(loadDeployedSnapshotAfterHealthGate(
      fixture.page as never,
      expectedBuildSha,
      fixture.recordRouteId
    )).rejects.toThrow('DEPLOYED_SNAPSHOT_OR_SHA_MISMATCH');
    expect(fixture.requestedUrls).toEqual([healthUrl, snapshotUrl]);
    expect(fixture.routeIds).toEqual([deployedHealthRouteId, deployedSnapshotRouteId]);
  });
}

test('안전한 health와 candidate-only snapshot만 읽고 루트는 열지 않는다', async () => {
  const fixture = fakePage();
  await expect(loadDeployedSnapshotAfterHealthGate(
    fixture.page as never,
    expectedBuildSha,
    fixture.recordRouteId
  )).resolves.toEqual({
    safety: {
      execution_enabled: false,
      broker_adapter: 'disabled',
      build_sha: expectedBuildSha
    },
    responses: validResponses
  });
  expect(fixture.requestedUrls).toEqual([healthUrl, snapshotUrl]);
  expect(fixture.routeIds).toEqual([deployedHealthRouteId, deployedSnapshotRouteId]);
  expect(fixture.requestOptions).toEqual([
    {
      failOnStatusCode: false,
      maxRedirects: 0,
      timeout: 8_000,
      headers: { 'Cache-Control': 'no-store' }
    },
    {
      failOnStatusCode: false,
      maxRedirects: 0,
      timeout: 8_000,
      headers: { 'Cache-Control': 'no-store' }
    }
  ]);
});

function fakeRoute() {
  const actions: string[] = [];
  return {
    route: {
      fulfill: async () => actions.push('fulfilled'),
      continue: async () => actions.push('continued'),
      abort: async () => actions.push('blocked')
    },
    actions
  };
}

test('old SPA의 status GET은 pinned snapshot으로 fulfill하고 backend으로 continue하지 않는다', async () => {
  const fixture = fakeRoute();
  const decision = classifyReadOnlyRequest(
    'https://app.wall-ant.com/api/v2/operations/status',
    'GET'
  );
  await expect(applyPinnedReadOnlyDecision(
    fixture.route as never,
    decision,
    validResponses
  )).resolves.toBe('fulfilled');
  expect(fixture.actions).toEqual(['fulfilled']);
});

for (const [url, method] of [
  ['https://app.wall-ant.com/api/v2/operations/resume', 'POST'],
  ['https://app.wall-ant.com/api/orders', 'GET']
] as const) {
  test(`${method} ${url} 요청은 backend으로 continue하지 않는다`, async () => {
    const fixture = fakeRoute();
    const decision = classifyReadOnlyRequest(url, method);
    await expect(applyPinnedReadOnlyDecision(
      fixture.route as never,
      decision,
      validResponses
    )).resolves.toBe('blocked');
    expect(fixture.actions).toEqual(['blocked']);
  });
}

test('popup의 첫 app 요청도 BrowserContext 정책이 backend 전에 차단한다', async ({ page }) => {
  const observed = emptyNetworkObservation();
  await installPinnedReadOnlyBrowserPolicy(page, observed, validResponses);
  await page.setContent('<button id="open">open</button>');

  await page.evaluate(() => {
    window.open('https://app.wall-ant.com/api/unknown', '_blank');
  });

  await expect.poll(() => observed.unexpectedPageCount).toBe(1);
  await expect.poll(() => observed.disallowedPathCount).toBeGreaterThan(0);
  expect(observed.allowedApiRequestCount).toBe(0);
  expect(observed.uiApiBackendContinueCount).toBe(0);
});
