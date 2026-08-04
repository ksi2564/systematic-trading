import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e-security',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  reporter: [['line']],
  use: {
    browserName: 'chromium',
    viewport: { width: 540, height: 280 },
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    colorScheme: 'light',
    trace: 'off',
    screenshot: 'off',
    video: 'off'
  }
});
