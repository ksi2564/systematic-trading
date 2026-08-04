import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { createHash } from 'node:crypto';
import { chmod, readFile } from 'node:fs/promises';
import {
  allowedApiRouteIds,
  classifyReadOnlyRequest
} from './read-only-network-policy';
import {
  captureMaskedDeployedScreenshot,
  deployedScreenshotMaskingPolicy,
  deployedScreenshotMaskingRegions,
  requiredDeployedScreenshotMaskingRegions
} from './screenshot-masking';

type NetworkObservation = {
  allowedStaticRequestCount: number;
  allowedApiRequestCount: number;
  apiRequestCountByRouteId: Record<string, number>;
  unsafeMethodCount: number;
  externalRequestCount: number;
  disallowedPathCount: number;
  webSocketCount: number;
  consoleErrorCount: number;
  pageErrorCount: number;
};

const baseUrl = new URL(process.env.DEPLOYED_QA_BASE_URL ?? 'https://app.wall-ant.com/');
const expectedBuildSha = process.env.DEPLOYED_QA_EXPECTED_SHA!;
const screens = [
  { label: '오늘의 운영', marker: '주문 의도' },
  { label: '전략 빌더', marker: '전략 정의' },
  { label: '연구·검증', marker: '전략 연구' },
  { label: '계좌·위험', marker: '계좌별 독립 운영' },
  { label: '데이터', marker: '시장 데이터 카탈로그' },
  { label: '안전·감사', marker: '안전 제어' }
] as const;
const navigation = screens.map((screen) => screen.label);
async function installReadOnlyNetworkPolicy(page: Page, observed: NetworkObservation) {
  page.on('console', (message) => {
    if (message.type() === 'error') observed.consoleErrorCount += 1;
  });
  page.on('pageerror', () => {
    observed.pageErrorCount += 1;
  });

  await page.routeWebSocket(/.*/, async (socket) => {
    observed.webSocketCount += 1;
    await socket.close({ code: 1008, reason: 'deployed read-only QA blocks WebSockets' });
  });

  await page.route('**/*', async (route) => {
    const request = route.request();
    const method = request.method().toUpperCase();
    const decision = classifyReadOnlyRequest(request.url(), method);
    if (decision.kind === 'api') {
      observed.allowedApiRequestCount += 1;
      observed.apiRequestCountByRouteId[decision.routeId] =
        (observed.apiRequestCountByRouteId[decision.routeId] ?? 0) + 1;
      await route.continue();
      return;
    }
    if (decision.kind === 'static') {
      observed.allowedStaticRequestCount += 1;
      await route.continue();
      return;
    }

    if (decision.reason === 'external-origin') observed.externalRequestCount += 1;
    else if (decision.reason === 'unsafe-method') observed.unsafeMethodCount += 1;
    else observed.disallowedPathCount += 1;
    await route.abort('blockedbyclient');
  });
}

async function attachSanitizedEvidence(
  page: Page,
  testInfo: TestInfo,
  observed: NetworkObservation,
  safety: { execution_enabled: boolean; broker_adapter: string; build_sha: string },
  qaStartedAt: string
) {
  const maskedRegions = await deployedScreenshotMaskingRegions(page);
  expect(maskedRegions).toEqual([...requiredDeployedScreenshotMaskingRegions].sort());
  await page.addStyleTag({
    content: [
      '.list-row strong, .list-row span,',
      '.strategy-card h3, .strategy-card p, .strategy-card small,',
      '.account-card h3, .account-card p, .account-card dd,',
      'table tbody td {',
      '  color: transparent !important;',
      '  text-shadow: none !important;',
      '  background: #dfe3ea !important;',
      '  border-radius: 4px !important;',
      '}'
    ].join(' ')
  });

  const screenshotName = `QA-ACC-002-${testInfo.project.name}-masked.png`;
  const screenshotPath = testInfo.outputPath(screenshotName);
  await captureMaskedDeployedScreenshot(page, screenshotPath);
  await chmod(screenshotPath, 0o600);
  const screenshotSha256 = createHash('sha256').update(await readFile(screenshotPath)).digest('hex');

  const observation = {
    schemaVersion: '2.0',
    observationType: 'DEPLOYED_READ_ONLY_UI_QA_OBSERVATION',
    qaId: 'QA-ACC-002',
    environment: 'deployed',
    baseUrlHost: baseUrl.hostname,
    project: testInfo.project.name,
    browserName: testInfo.project.use.browserName,
    viewport: testInfo.project.use.viewport,
    maskedRegions,
    verifiedScreens: navigation,
    expectedBuildSha,
    observedBuildSha: safety.build_sha,
    qaStartedAt,
    observationFinishedAt: new Date().toISOString(),
    operationsSafety: {
      execution_enabled: safety.execution_enabled,
      broker_adapter: safety.broker_adapter
    },
    harnessSafety: {
      realOrderSubmissionAllowed: false,
      requestPolicy: 'exact-origin allowlisted GET/HEAD only',
      resourceMutations: []
    },
    network: observed,
    screenshot: {
      fileName: screenshotName,
      sha256: screenshotSha256,
      masking: deployedScreenshotMaskingPolicy
    },
    excludedSecrets: [
      'cookies',
      'authorization headers',
      'OTP',
      'Access tokens',
      'account numbers',
      'request headers',
      'query strings'
    ],
    automaticPlaywrightCapture: {
      trace: false,
      screenshotOnFailure: false,
      video: false
    }
  };
  await testInfo.attach('masked-deployed-screenshot', { path: screenshotPath, contentType: 'image/png' });
  await testInfo.attach('deployed-readonly-observation', {
    body: Buffer.from(`${JSON.stringify(observation, null, 2)}\n`, 'utf8'),
    contentType: 'application/json'
  });
}

test('[QA-ACC-002] 인증된 배포 화면과 허용 API를 GET 전용으로 검증한다', async ({ page }, testInfo) => {
  const qaStartedAt = new Date().toISOString();
  const observed: NetworkObservation = {
    allowedStaticRequestCount: 0,
    allowedApiRequestCount: 0,
    apiRequestCountByRouteId: Object.fromEntries(allowedApiRouteIds.map((routeId) => [routeId, 0])),
    unsafeMethodCount: 0,
    externalRequestCount: 0,
    disallowedPathCount: 0,
    webSocketCount: 0,
    consoleErrorCount: 0,
    pageErrorCount: 0
  };
  await installReadOnlyNetworkPolicy(page, observed);

  await page.goto('/', { waitUntil: 'domcontentloaded' });
  await expect(page).toHaveURL(baseUrl.href);
  await expect(page.getByRole('heading', { level: 1, name: '오늘의 운영' })).toBeVisible();

  const safety = await page.evaluate(async () => {
    const response = await fetch('/api/v2/operations/status', {
      method: 'GET',
      credentials: 'same-origin',
      cache: 'no-store'
    });
    if (!response.ok) throw new Error('operations/status read failed');
    const body = await response.json();
    return {
      execution_enabled: body.execution_enabled,
      broker_adapter: body.broker_adapter,
      build_sha: body.build_sha
    };
  });

  expect(safety).toEqual({
    execution_enabled: false,
    broker_adapter: 'disabled',
    build_sha: expectedBuildSha
  });
  await expect(page.getByText('실주문 어댑터 비활성')).toBeVisible();

  for (const screen of screens) {
    if (testInfo.project.name.includes('mobile')) {
      await page.getByRole('button', { name: '메뉴 열기' }).click();
    }
    await page.getByRole('navigation', { name: '주요 메뉴' })
      .getByRole('button', { name: screen.label, exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: screen.label })).toBeVisible();
    await expect(page.getByText(screen.marker, { exact: false }).first()).toBeVisible();
    const viewportOverflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth
    );
    expect(viewportOverflow, `${screen.label} horizontal overflow`).toBeLessThanOrEqual(1);
  }

  expect(observed.allowedStaticRequestCount).toBeGreaterThan(0);
  expect(observed.allowedApiRequestCount).toBeGreaterThan(0);
  for (const routeId of allowedApiRouteIds) {
    expect(observed.apiRequestCountByRouteId[routeId], `${routeId} GET count`).toBeGreaterThan(0);
  }
  expect(
    Object.values(observed.apiRequestCountByRouteId).reduce((total, count) => total + count, 0)
  ).toBe(observed.allowedApiRequestCount);
  expect(observed.unsafeMethodCount).toBe(0);
  expect(observed.externalRequestCount).toBe(0);
  expect(observed.disallowedPathCount).toBe(0);
  expect(observed.webSocketCount).toBe(0);
  expect(observed.consoleErrorCount).toBe(0);
  expect(observed.pageErrorCount).toBe(0);

  if (testInfo.project.name.includes('mobile')) {
    await page.getByRole('button', { name: '메뉴 열기' }).click();
  }
  await page.getByRole('navigation', { name: '주요 메뉴' }).getByRole('button', { name: '오늘의 운영', exact: true }).click();
  await attachSanitizedEvidence(page, testInfo, observed, safety, qaStartedAt);
});
