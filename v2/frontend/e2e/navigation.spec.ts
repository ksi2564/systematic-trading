import { attachNetworkEvidence, expect, test } from './support/qa-test';
import { attachViewportScreenshot, navigateTo, openConsole } from './support/ui';

const screens = [
  { id: 'overview', label: '오늘의 운영', marker: 'QQQM 자동 운용 전략' },
  { id: 'strategies', label: '전략 빌더', marker: '전략 정의' },
  { id: 'research', label: '연구·검증', marker: '전략 연구' },
  { id: 'accounts', label: '계좌·위험', marker: '주력 미국 주식 계좌' },
  { id: 'data', label: '데이터', marker: '시장 데이터 카탈로그' },
  { id: 'operations', label: '안전·감사', marker: '구조적 안전장치' }
] as const;

for (const screen of screens) {
  test(`[QA-NAV-001] 채워진 상태의 ${screen.label} 화면을 보여준다`, async ({ page, networkEvidence }, testInfo) => {
    await openConsole(page);
    if (screen.id !== 'overview') await navigateTo(page, screen.label);

    await expect(page.getByText(screen.marker, { exact: false }).first()).toBeVisible();
    await expect(page.getByText('실주문 어댑터 비활성', { exact: true })).toBeVisible();
    const viewportOverflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth
    );
    expect(viewportOverflow).toBeLessThanOrEqual(1);

    const bootstrapPaths = networkEvidence.apiCalls.map((call) => call.path.split('?')[0]);
    expect(new Set(bootstrapPaths)).toEqual(
      new Set([
        '/api/v2/operations/status',
        '/api/v2/strategies',
        '/api/v2/accounts',
        '/api/v2/market-data/catalog',
        '/api/v2/operations/audit'
      ])
    );
    expect(networkEvidence.apiCalls.every((call) => call.mocked)).toBe(true);

    await attachViewportScreenshot(page, testInfo, `populated-${screen.id}`);
    await attachNetworkEvidence(testInfo, 'populated', networkEvidence);
  });
}
