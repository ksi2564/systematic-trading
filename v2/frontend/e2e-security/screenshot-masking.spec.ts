import { expect, test } from '@playwright/test';
import {
  assertDeployedScreenshotMaskingCoverage,
  captureMaskedDeployedScreenshot,
  deployedQaScreens,
  requiredMaskedRegionsForScreen,
  type DeployedQaScreenId
} from '../e2e-deployed/screenshot-masking';

function pageTemplate(
  screenId: DeployedQaScreenId,
  secret: string,
  options: { omit?: string; duplicate?: string; extra?: string } = {}
) {
  const regions = requiredMaskedRegionsForScreen(screenId).filter((region) => region !== options.omit);
  const regionMarkup = regions.map((region) => `
    <div data-qa-sensitive="true" data-qa-sensitive-region="${region}">
      ${region}: ${secret}
    </div>`).join('');
  const duplicateMarkup = options.duplicate
    ? `<div data-qa-sensitive-region="${options.duplicate}">${secret}</div>`
    : '';
  const extraMarkup = options.extra
    ? `<div data-qa-sensitive-region="${options.extra}">${secret}</div>`
    : '';
  return `
    <style>
      html, body { margin: 0; background: white; }
      main { width: 520px; min-height: 260px; padding: 20px; }
      [data-qa-sensitive-region] {
        box-sizing: border-box;
        width: 460px;
        height: 44px;
        margin: 6px;
        padding: 10px;
        border: 1px solid #ccd3dd;
        font: 16px sans-serif;
      }
    </style>
    <main data-qa-screen="${screenId}">
      <h1>${screenId} QA evidence</h1>
      ${regionMarkup}
      ${duplicateMarkup}
      ${extraMarkup}
    </main>
  `;
}

for (const screen of deployedQaScreens) {
  test(`${screen.label} 민감 sentinel 값이 달라도 opaque mask 증적 픽셀은 동일하다`, async ({ page }) => {
    await page.setContent(pageTemplate(screen.id, 'CLIENT-SECRET-ALPHA-000000'));
    await expect(assertDeployedScreenshotMaskingCoverage(page, screen.id)).resolves.toEqual(
      requiredMaskedRegionsForScreen(screen.id)
    );
    const unmaskedAlpha = await page.screenshot({ fullPage: true });
    const maskedAlpha = await captureMaskedDeployedScreenshot(page, screen.id);

    await page.setContent(pageTemplate(screen.id, 'CLIENT-SECRET-BRAVO-000000'));
    const unmaskedBravo = await page.screenshot({ fullPage: true });
    const maskedBravo = await captureMaskedDeployedScreenshot(page, screen.id);

    expect(unmaskedAlpha.equals(unmaskedBravo)).toBe(false);
    expect(maskedAlpha.equals(maskedBravo)).toBe(true);
  });
}

test('필수 민감 marker가 빠지거나 중복·추가되면 캡처 전에 fail-closed한다', async ({ page }) => {
  const screenId: DeployedQaScreenId = 'overview';
  const [firstRegion] = requiredMaskedRegionsForScreen(screenId);

  await page.setContent(pageTemplate(screenId, 'SECRET', { omit: firstRegion }));
  await expect(captureMaskedDeployedScreenshot(page, screenId)).rejects.toThrow(
    'DEPLOYED_MASKING_REGION_MATRIX_INVALID'
  );

  await page.setContent(pageTemplate(screenId, 'SECRET', { duplicate: firstRegion }));
  await expect(captureMaskedDeployedScreenshot(page, screenId)).rejects.toThrow(
    'DEPLOYED_MASKING_REGION_DUPLICATE'
  );

  await page.setContent(pageTemplate(screenId, 'SECRET', { extra: 'unexpected-sensitive-region' }));
  await expect(captureMaskedDeployedScreenshot(page, screenId)).rejects.toThrow(
    'DEPLOYED_MASKING_REGION_MATRIX_INVALID'
  );
});

test('필수 marker가 보이지 않거나 미등록 민감 노드가 있으면 캡처 전에 실패한다', async ({ page }) => {
  const screenId: DeployedQaScreenId = 'overview';
  await page.setContent(pageTemplate(screenId, 'SECRET'));
  await page.locator('[data-qa-sensitive-region]').first().evaluate((element) => {
    (element as HTMLElement).style.display = 'none';
  });
  await expect(captureMaskedDeployedScreenshot(page, screenId)).rejects.toThrow(
    'DEPLOYED_MASKING_REGION_NOT_VISIBLE'
  );

  await page.setContent(pageTemplate(screenId, 'SECRET'));
  await page.locator('main').evaluate((element) => {
    const orphan = document.createElement('div');
    orphan.dataset.qaSensitive = 'true';
    orphan.textContent = 'UNREGISTERED SECRET';
    element.appendChild(orphan);
  });
  await expect(captureMaskedDeployedScreenshot(page, screenId)).rejects.toThrow(
    'DEPLOYED_SENSITIVE_NODE_UNREGISTERED'
  );
});
