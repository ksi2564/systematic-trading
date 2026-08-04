import type { Page } from '@playwright/test';

export const requiredDeployedScreenshotMaskingRegions = [
  'summary-metrics',
  'recent-strategies',
  'recent-accounts'
] as const;

export const deployedScreenshotMaskingPolicy =
  'required overview summary-metrics, recent-strategies, and recent-accounts covered by opaque Playwright locator masks';

const sensitiveContentSelector = [
  '[data-qa-sensitive="true"]',
  '.list-row',
  '.strategy-card',
  '.account-card',
  'table tbody'
].join(', ');

export function captureMaskedDeployedScreenshot(page: Page, path?: string) {
  return page.screenshot({
    path,
    fullPage: true,
    mask: [page.locator(sensitiveContentSelector)],
    maskColor: '#111827'
  });
}

export async function deployedScreenshotMaskingRegions(page: Page) {
  return page.locator('[data-qa-sensitive-region]').evaluateAll((elements) =>
    elements
      .map((element) => element.getAttribute('data-qa-sensitive-region'))
      .filter((value): value is string => Boolean(value))
      .sort()
  );
}
