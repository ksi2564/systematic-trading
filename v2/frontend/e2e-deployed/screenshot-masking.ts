import type { Page } from '@playwright/test';

export const deployedQaScreens = [
  {
    id: 'overview',
    label: '오늘의 운영',
    requiredMaskedRegions: [
      'recent-accounts',
      'recent-strategies',
      'shell-environment',
      'summary-metrics'
    ]
  },
  {
    id: 'strategies',
    label: '전략 빌더',
    requiredMaskedRegions: ['shell-environment', 'strategy-catalog']
  },
  {
    id: 'research',
    label: '연구·검증',
    requiredMaskedRegions: ['research-strategy-selector', 'shell-environment']
  },
  {
    id: 'accounts',
    label: '계좌·위험',
    requiredMaskedRegions: ['account-catalog', 'shell-environment']
  },
  {
    id: 'data',
    label: '데이터',
    requiredMaskedRegions: ['market-data-catalog', 'shell-environment']
  },
  {
    id: 'operations',
    label: '안전·감사',
    requiredMaskedRegions: [
      'global-control-reason',
      'operations-audit',
      'shell-environment'
    ]
  }
] as const;

export type DeployedQaScreenId = (typeof deployedQaScreens)[number]['id'];

export const deployedScreenshotMaskingPolicy =
  'every deployed screen uses its exact registered sensitive-region set with opaque Playwright locator masks';

const sensitiveContentSelector = '[data-qa-sensitive-region]';

export function requiredMaskedRegionsForScreen(screenId: DeployedQaScreenId): string[] {
  const screen = deployedQaScreens.find((candidate) => candidate.id === screenId);
  if (!screen) throw new Error('DEPLOYED_SCREEN_NOT_REGISTERED');
  return [...screen.requiredMaskedRegions].sort();
}

export async function deployedScreenshotMaskingRegions(page: Page) {
  return page.locator('[data-qa-sensitive-region]').evaluateAll((elements) =>
    elements
      .map((element) => element.getAttribute('data-qa-sensitive-region'))
      .filter((value): value is string => Boolean(value))
      .sort()
  );
}

export async function assertDeployedScreenshotMaskingCoverage(
  page: Page,
  screenId: DeployedQaScreenId
) {
  const expected = requiredMaskedRegionsForScreen(screenId);
  const roots = page.locator(`[data-qa-screen="${screenId}"]`);
  if (await roots.count() !== 1 || !await roots.first().isVisible()) {
    throw new Error('DEPLOYED_SCREEN_ROOT_INVALID');
  }
  const visibleScreenIds = await page.locator('[data-qa-screen]:visible').evaluateAll((elements) =>
    elements.map((element) => element.getAttribute('data-qa-screen')).filter(Boolean)
  );
  if (visibleScreenIds.length !== 1 || visibleScreenIds[0] !== screenId) {
    throw new Error('DEPLOYED_VISIBLE_SCREEN_MATRIX_INVALID');
  }
  const invalidGeometry = await page.locator('[data-qa-sensitive-region]').evaluateAll((elements) =>
    elements.some((element) => {
      const rectangle = element.getBoundingClientRect();
      const style = window.getComputedStyle(element);
      return rectangle.width <= 0
        || rectangle.height <= 0
        || style.display === 'none'
        || style.visibility === 'hidden';
    })
  );
  if (invalidGeometry) throw new Error('DEPLOYED_MASKING_REGION_NOT_VISIBLE');
  const orphanSensitiveCount = await page
    .locator('[data-qa-sensitive="true"]:not([data-qa-sensitive-region])')
    .evaluateAll((elements) => elements.filter(
      (element) => !element.closest('[data-qa-sensitive-region]')
    ).length);
  if (orphanSensitiveCount !== 0) throw new Error('DEPLOYED_SENSITIVE_NODE_UNREGISTERED');
  const actual = await deployedScreenshotMaskingRegions(page);
  if (new Set(actual).size !== actual.length) {
    throw new Error('DEPLOYED_MASKING_REGION_DUPLICATE');
  }
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error('DEPLOYED_MASKING_REGION_MATRIX_INVALID');
  }
  return actual;
}

export async function captureMaskedDeployedScreenshot(
  page: Page,
  screenId: DeployedQaScreenId,
  path?: string
) {
  await assertDeployedScreenshotMaskingCoverage(page, screenId);
  return page.screenshot({
    path,
    fullPage: true,
    mask: [page.locator(sensitiveContentSelector)],
    maskColor: '#111827'
  });
}
