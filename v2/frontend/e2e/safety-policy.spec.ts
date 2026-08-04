import { attachNetworkEvidence, expect, forbiddenAction, test } from './support/qa-test';
import { attachViewportScreenshot, navigateTo, openConsole } from './support/ui';

test('[QA-SAF-001] 주문·계좌 재개·LIVE 승인 API를 금지 요청으로 분류한다', async ({
  page,
  networkEvidence
}, testInfo) => {
  expect(forbiddenAction('POST', '/api/v2/orders', '{}')).toBe('ORDER_OR_EXECUTION');
  expect(forbiddenAction('POST', '/api/v2/executions/execute', '{}')).toBe('ORDER_OR_EXECUTION');
  expect(forbiddenAction('POST', '/api/v2/accounts/account-1/resume', '{"confirmed":true}')).toBe(
    'ACCOUNT_RESUME'
  );
  expect(forbiddenAction('POST', '/api/v2/operations/resume', '{"confirmed":true}')).toBe(
    'GLOBAL_RESUME'
  );
  expect(
    forbiddenAction(
      'POST',
      '/api/v2/strategies/versions/version-1/transition',
      '{"target":"LIVE_APPROVED","confirmed":true}'
    )
  ).toBe('LIVE_APPROVAL');

  await openConsole(page);
  await attachViewportScreenshot(page, testInfo, 'forbidden-api-classification');
  await attachNetworkEvidence(testInfo, 'populated', networkEvidence);
});

test('[QA-SAF-001] 6개 화면 읽기 전용 QA에서 위험 요청이 0건이다', async ({ page, networkEvidence }, testInfo) => {
  await openConsole(page);
  await navigateTo(page, '계좌·위험');
  page.once('dialog', async (dialog) => dialog.dismiss());
  await page.getByRole('button', { name: '확인 후 재개', exact: true }).click();

  await navigateTo(page, '전략 빌더');
  page.once('dialog', async (dialog) => dialog.dismiss());
  await page.getByRole('button', { name: '실전 후보로 수동 승인', exact: true }).click();

  for (const label of ['연구·검증', '데이터', '안전·감사', '오늘의 운영']) {
    await navigateTo(page, label);
  }

  expect(networkEvidence.apiCalls).not.toHaveLength(0);
  expect(networkEvidence.apiCalls.every((call) => call.method === 'GET' && call.mocked)).toBe(true);
  expect(networkEvidence.forbiddenActionRequests).toEqual([]);
  expect(networkEvidence.unmockedApiRequests).toEqual([]);
  expect(networkEvidence.blockedRequests).toEqual([]);
  await attachViewportScreenshot(page, testInfo, 'zero-risk-request-navigation');
  await attachNetworkEvidence(testInfo, 'populated', networkEvidence);
});
