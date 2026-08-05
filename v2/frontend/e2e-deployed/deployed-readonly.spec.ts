import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { createHash } from 'node:crypto';
import { chmod, readFile } from 'node:fs/promises';
import {
  allowedApiRouteIds,
  deployedRequestPolicy,
  deployedHealthRouteId,
  deployedSnapshotRouteId,
  deployedUiApiRouteIds
} from './read-only-network-policy';
import { loadDeployedSnapshotAfterHealthGate } from './deployed-health-gate';
import {
  installPinnedReadOnlyBrowserPolicy,
  type DeployedNetworkObservation as NetworkObservation
} from './pinned-ui-routing';
import {
  assertDeployedScreenshotMaskingCoverage,
  captureMaskedDeployedScreenshot,
  deployedQaScreens,
  deployedScreenshotMaskingPolicy,
  type DeployedQaScreenId
} from './screenshot-masking';

const baseUrl = new URL(process.env.DEPLOYED_QA_BASE_URL ?? 'https://app.wall-ant.com/');
const expectedBuildSha = process.env.DEPLOYED_QA_EXPECTED_SHA!;
const screenMarkers: Record<DeployedQaScreenId, string> = {
  overview: '주문 의도',
  strategies: '전략 정의',
  research: '전략 연구',
  accounts: '계좌별 독립 운영',
  data: '시장 데이터 카탈로그',
  operations: '안전 제어'
};

type ScreenEvidence = {
  id: DeployedQaScreenId;
  label: string;
  horizontalOverflowPx: number;
  maskedRegions: string[];
  screenshot: {
    fileName: string;
    sha256: string;
    masking: string;
  };
};
function recordAllowedApiRoute(observed: NetworkObservation, routeId: string) {
  observed.allowedApiRequestCount += 1;
  observed.apiRequestCountByRouteId[routeId] =
    (observed.apiRequestCountByRouteId[routeId] ?? 0) + 1;
  if (routeId === deployedHealthRouteId || routeId === deployedSnapshotRouteId) {
    observed.preflightBackendGetCount += 1;
  }
}

async function attachSanitizedEvidence(
  testInfo: TestInfo,
  observed: NetworkObservation,
  safety: { execution_enabled: boolean; broker_adapter: string; build_sha: string },
  observedUiBuildSha: string,
  qaStartedAt: string,
  screens: ScreenEvidence[]
) {
  const observation = {
    schemaVersion: '2.2',
    observationType: 'DEPLOYED_READ_ONLY_UI_QA_OBSERVATION',
    qaId: 'QA-ACC-002',
    environment: 'deployed',
    baseUrlHost: baseUrl.hostname,
    project: testInfo.project.name,
    browserName: testInfo.project.use.browserName,
    viewport: testInfo.project.use.viewport,
    screens,
    expectedBuildSha,
    observedBuildSha: safety.build_sha,
    observedUiBuildSha,
    qaStartedAt,
    observationFinishedAt: new Date().toISOString(),
    operationsSafety: {
      execution_enabled: safety.execution_enabled,
      broker_adapter: safety.broker_adapter
    },
    harnessSafety: {
      realOrderSubmissionAllowed: false,
      requestPolicy: deployedRequestPolicy,
      resourceMutations: []
    },
    network: observed,
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
  await testInfo.attach('deployed-readonly-observation', {
    body: Buffer.from(`${JSON.stringify(observation, null, 2)}\n`, 'utf8'),
    contentType: 'application/json'
  });
}

async function captureScreenEvidence(
  page: Page,
  testInfo: TestInfo,
  screen: (typeof deployedQaScreens)[number],
  horizontalOverflowPx: number
): Promise<ScreenEvidence> {
  const maskedRegions = await assertDeployedScreenshotMaskingCoverage(page, screen.id);
  const screenshotName = `QA-ACC-002-${testInfo.project.name}-${screen.id}-masked.png`;
  const screenshotPath = testInfo.outputPath(screenshotName);
  await captureMaskedDeployedScreenshot(page, screen.id, screenshotPath);
  await chmod(screenshotPath, 0o600);
  const screenshotSha256 = createHash('sha256').update(await readFile(screenshotPath)).digest('hex');
  await testInfo.attach(`masked-deployed-screenshot:${screen.id}`, {
    path: screenshotPath,
    contentType: 'image/png'
  });
  return {
    id: screen.id,
    label: screen.label,
    horizontalOverflowPx,
    maskedRegions,
    screenshot: {
      fileName: screenshotName,
      sha256: screenshotSha256,
      masking: deployedScreenshotMaskingPolicy
    }
  };
}

test('[QA-ACC-002] 인증된 배포 화면과 허용 API를 GET 전용으로 검증한다', async ({ page }, testInfo) => {
  const qaStartedAt = new Date().toISOString();
  const observed: NetworkObservation = {
    allowedStaticRequestCount: 0,
    allowedApiRequestCount: 0,
    apiRequestCountByRouteId: Object.fromEntries(allowedApiRouteIds.map((routeId) => [routeId, 0])),
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
  const pinnedSnapshot = await loadDeployedSnapshotAfterHealthGate(
    page,
    expectedBuildSha,
    (routeId) => recordAllowedApiRoute(observed, routeId)
  );
  await installPinnedReadOnlyBrowserPolicy(page, observed, pinnedSnapshot.responses);
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  await expect(page).toHaveURL(baseUrl.href);
  const uiBuildMarker = page.locator('[data-wallant-ui-build-sha]');
  await expect(uiBuildMarker).toHaveCount(1);
  await expect(uiBuildMarker).toHaveAttribute(
    'data-wallant-ui-build-sha',
    expectedBuildSha
  );
  const observedUiBuildSha = await uiBuildMarker.getAttribute('data-wallant-ui-build-sha');
  if (observedUiBuildSha !== expectedBuildSha) {
    throw new Error('DEPLOYED_STATIC_UI_SHA_MISMATCH');
  }
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
  expect(safety).toEqual(pinnedSnapshot.safety);
  await expect(page.getByText('실주문 어댑터 비활성')).toBeVisible();

  const screenEvidence: ScreenEvidence[] = [];
  for (const screen of deployedQaScreens) {
    if (testInfo.project.name.includes('mobile')) {
      await page.getByRole('button', { name: '메뉴 열기' }).click();
    }
    await page.getByRole('navigation', { name: '주요 메뉴' })
      .getByRole('button', { name: screen.label, exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: screen.label })).toBeVisible();
    await expect(page.getByText(screenMarkers[screen.id], { exact: false }).first()).toBeVisible();
    const viewportOverflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth
    );
    expect(viewportOverflow, `${screen.label} horizontal overflow`).toBeLessThanOrEqual(1);
    screenEvidence.push(await captureScreenEvidence(page, testInfo, screen, viewportOverflow));
  }

  expect(observed.allowedStaticRequestCount).toBeGreaterThan(0);
  expect(observed.allowedApiRequestCount).toBeGreaterThan(0);
  for (const routeId of allowedApiRouteIds) {
    expect(observed.apiRequestCountByRouteId[routeId], `${routeId} GET count`).toBeGreaterThan(0);
  }
  expect(
    Object.values(observed.apiRequestCountByRouteId).reduce((total, count) => total + count, 0)
  ).toBe(observed.allowedApiRequestCount);
  expect(observed.preflightBackendGetCount).toBe(2);
  expect(observed.uiApiLocalFulfillCount).toBe(
    deployedUiApiRouteIds.reduce(
      (total, routeId) => total + observed.apiRequestCountByRouteId[routeId],
      0
    )
  );
  expect(observed.uiApiBackendContinueCount).toBe(0);
  expect(observed.unexpectedPageCount).toBe(0);
  expect(observed.unsafeMethodCount).toBe(0);
  expect(observed.externalRequestCount).toBe(0);
  expect(observed.disallowedPathCount).toBe(0);
  expect(observed.webSocketCount).toBe(0);
  expect(observed.consoleErrorCount).toBe(0);
  expect(observed.pageErrorCount).toBe(0);

  await attachSanitizedEvidence(
    testInfo,
    observed,
    safety,
    observedUiBuildSha,
    qaStartedAt,
    screenEvidence
  );
});
