import { attachNetworkEvidence, expect, test } from './support/qa-test';
import { attachViewportScreenshot, openConsole } from './support/ui';

test('[QA-RWD-001] 1440/360 화면에서 가로 잘림 없이 키보드로 주요 메뉴를 연다', async ({ page, networkEvidence }, testInfo) => {
  await openConsole(page);
  const navigation = page.getByRole('navigation', { name: '주요 메뉴' });
  const overview = navigation.getByRole('button', { name: '오늘의 운영', exact: true });
  const strategies = navigation.getByRole('button', { name: '전략 빌더', exact: true });

  if (testInfo.project.name === 'mobile-chromium') {
    const menuButton = page.getByRole('button', { name: '메뉴 열기' });
    await menuButton.focus();
    await expect(menuButton).toBeFocused();
    await page.keyboard.press('Enter');

    const closeButton = page.getByRole('button', { name: '메뉴 닫기' }).first();
    await expect(closeButton).toBeVisible();
    await closeButton.focus();
    await page.keyboard.press('Tab');
    await expect(overview).toBeFocused();
  } else {
    await overview.focus();
    await expect(overview).toBeFocused();
  }

  await page.keyboard.press('Tab');
  await expect(strategies).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { level: 1, name: '전략 빌더' })).toBeVisible();
  const viewportOverflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth
  );
  expect(viewportOverflow).toBeLessThanOrEqual(1);

  await attachViewportScreenshot(page, testInfo, 'keyboard-menu-navigation');
  await attachNetworkEvidence(testInfo, 'populated', networkEvidence);
});

test('[QA-NAV-001] UI 새로고침과 브라우저 reload 뒤에도 mock 안전 상태를 유지한다', async ({
  page,
  networkEvidence
}, testInfo) => {
  await openConsole(page);
  expect(networkEvidence.apiCalls).toHaveLength(5);

  await page.getByRole('button', { name: '새로고침' }).click();
  await expect.poll(() => networkEvidence.apiCalls.length).toBe(10);
  await expect(page.getByText('실주문 어댑터 비활성')).toBeVisible();

  await page.reload();
  await expect.poll(() => networkEvidence.apiCalls.length).toBe(15);
  await expect(page.getByRole('heading', { level: 1, name: '오늘의 운영' })).toBeVisible();
  await expect(page.getByText('실주문 어댑터 비활성')).toBeVisible();
  expect(networkEvidence.apiCalls.every((call) => call.method === 'GET' && call.mocked)).toBe(true);

  await attachViewportScreenshot(page, testInfo, 'refresh-safe-state');
  await attachNetworkEvidence(testInfo, 'populated', networkEvidence);
});
