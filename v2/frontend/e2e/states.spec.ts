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
    await expect(page.getByText('서버에서 실주문 어댑터 비활성을 확인했습니다.')).toBeVisible();

    await attachViewportScreenshot(page, testInfo, 'empty-six-screen-check');
    await attachNetworkEvidence(testInfo, 'empty', networkEvidence);
  });
});

test.describe('전체 긴급 정지 상태', () => {
  test.use({ apiScenario: 'emergency-paused' });

  test('[QA-OPS-001] 가상 정지 상태를 표시하고 해제 확인 취소 시 변경하지 않는다', async ({ page, networkEvidence }, testInfo) => {
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

test.describe('안전 설정 불일치', () => {
  test.use({ apiScenario: 'unsafe' });

  test('[QA-SAF-001] 서버 차단 설정이 예상과 다르면 변경 기능을 잠근다', async ({ page, networkEvidence }, testInfo) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1, name: '오늘의 운영' })).toBeVisible();

    await expect(page.getByText('실주문 차단 설정이 예상과 달라요')).toBeVisible();
    await expect(page.getByText('서버에서 false/disabled를 확인할 때까지 화면의 변경 기능을 잠갔어요.')).toBeVisible();
    await expect(page.getByRole('button', { name: /전략 만들기/ })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'v2 신규 제출 차단' })).toBeEnabled();
    await expect(page.getByText('SAFE RESEARCH MODE')).toHaveCount(0);

    await navigateTo(page, '안전·감사');
    await expect(page.getByText('안전 설정 검증 실패')).toBeVisible();
    await expect(page.getByText('현재 값을 안전하다고 간주하지 않습니다. 필요하면 v2 신규 제출을 차단하세요.')).toBeVisible();
    await expect(page.getByText('설정 실수로도 실주문이 나가지 않습니다.')).toHaveCount(0);

    await attachViewportScreenshot(page, testInfo, 'unsafe-settings-locked');
    await attachNetworkEvidence(testInfo, 'unsafe', networkEvidence);
  });
});
