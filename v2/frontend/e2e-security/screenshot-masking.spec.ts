import { expect, test } from '@playwright/test';
import { captureMaskedDeployedScreenshot } from '../e2e-deployed/screenshot-masking';

const pageTemplate = (secret: string) => `
  <style>
    html, body { margin: 0; background: white; }
    main { width: 480px; height: 220px; padding: 20px; }
    [data-qa-sensitive="true"] {
      box-sizing: border-box;
      width: 420px;
      height: 100px;
      padding: 16px;
      border: 1px solid #ccd3dd;
      font: 20px sans-serif;
    }
  </style>
  <main>
    <h1>QA evidence</h1>
    <div data-qa-sensitive="true">${secret}</div>
  </main>
`;

test('민감 sentinel 값이 달라도 opaque mask 증적 픽셀은 동일하다', async ({ page }) => {
  await page.setContent(pageTemplate('CLIENT-SECRET-ALPHA-000000'));
  const unmaskedAlpha = await page.screenshot({ fullPage: true });
  const maskedAlpha = await captureMaskedDeployedScreenshot(page);

  await page.setContent(pageTemplate('CLIENT-SECRET-BRAVO-000000'));
  const unmaskedBravo = await page.screenshot({ fullPage: true });
  const maskedBravo = await captureMaskedDeployedScreenshot(page);

  expect(unmaskedAlpha.equals(unmaskedBravo)).toBe(false);
  expect(maskedAlpha.equals(maskedBravo)).toBe(true);
});
