import { expect, type Page, type TestInfo } from '@playwright/test';

export async function openConsole(page: Page) {
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1, name: '오늘의 운영' })).toBeVisible();
  await expect(page.getByText('실주문 어댑터 비활성', { exact: true })).toBeVisible();
}

export async function navigateTo(page: Page, label: string) {
  const menuButton = page.getByRole('button', { name: '메뉴 열기' });
  if (await menuButton.isVisible()) await menuButton.click();

  await page.getByRole('button', { name: label, exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: label })).toBeVisible();
}

export async function attachViewportScreenshot(page: Page, testInfo: TestInfo, name: string) {
  const viewport = page.viewportSize();
  const viewportName = viewport ? `${viewport.width}x${viewport.height}` : 'unknown-viewport';
  const evidenceName = `${name}-${testInfo.project.name}-${viewportName}`;
  const path = testInfo.outputPath(`${evidenceName}.png`);
  await page.screenshot({ path, fullPage: false, animations: 'disabled' });
  await testInfo.attach(evidenceName, { path, contentType: 'image/png' });
}
