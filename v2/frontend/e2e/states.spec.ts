import { attachNetworkEvidence, expect, test } from './support/qa-test';
import { attachViewportScreenshot, navigateTo, openConsole } from './support/ui';

test.describe('빈 상태', () => {
  test.use({ apiScenario: 'empty' });

  test('[QA-NAV-001] 6개 화면이 처음 사용자에게 다음 행동을 안내한다', async ({ page, networkEvidence }, testInfo) => {
    await openConsole(page);
    await expect(page.getByText('아직 전략이 없습니다.')).toBeVisible();
    await expect(page.getByText('등록된 계좌가 없습니다.')).toBeVisible();

    await navigateTo(page, '전략 빌더');
    await expect(page.getByText('첫 전략을 만들어 보세요.')).toBeVisible();

    await navigateTo(page, '연구·검증');
    await expect(page.getByText('평가 결과가 없습니다.')).toBeVisible();
    await expect(page.getByText('시계열 검증 결과가 없습니다.')).toBeVisible();

    await navigateTo(page, '계좌·위험');
    await expect(page.getByText('계좌가 없습니다.')).toBeVisible();

    await navigateTo(page, '데이터');
    await expect(page.getByText('저장된 시계열이 없습니다.')).toBeVisible();

    await navigateTo(page, '안전·감사');
    await expect(page.getByText('감사 기록이 없습니다.')).toBeVisible();
    await expect(page.getByText('실주문 어댑터는 항상 비활성입니다.')).toBeVisible();

    await attachViewportScreenshot(page, testInfo, 'empty-six-screen-check');
    await attachNetworkEvidence(testInfo, 'empty', networkEvidence);
  });
});

test.describe('전체 긴급 정지 상태', () => {
  test.use({ apiScenario: 'emergency-paused' });

  test('[QA-OPS-001] 정지 상태를 명확히 표시하고 확인 없이 해제하지 않는다', async ({ page, networkEvidence }, testInfo) => {
    await openConsole(page);
    await expect(page.getByText('전체 거래 판단이 정지되어 있습니다')).toBeVisible();
    await expect(page.getByText('전체 정지', { exact: true })).toBeVisible();

    await navigateTo(page, '안전·감사');
    await expect(page.getByText('정지됨', { exact: true })).toBeVisible();
    await expect(page.getByText('운영자 QA 안전 점검')).toBeVisible();
    await expect(page.getByText('disabled', { exact: true })).toBeVisible();
    await expect(page.getByText('false', { exact: true })).toBeVisible();

    page.once('dialog', async (dialog) => dialog.dismiss());
    await page.getByRole('button', { name: '확인 후 전체 정지 해제' }).click();
    expect(networkEvidence.apiCalls.every((call) => call.method === 'GET')).toBe(true);

    await attachViewportScreenshot(page, testInfo, 'emergency-paused');
    await attachNetworkEvidence(testInfo, 'emergency-paused', networkEvidence);
  });
});
