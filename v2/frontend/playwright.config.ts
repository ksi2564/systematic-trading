import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  timeout: 30_000,
  expect: {
    timeout: 5_000
  },
  outputDir: 'artifacts/playwright/results',
  reporter: [
    ['list'],
    ['html', { outputFolder: 'artifacts/playwright/html', open: 'never' }],
    ['junit', { outputFile: 'artifacts/playwright/junit/results.xml' }],
    ['./e2e/reporters/evidence-reporter.ts']
  ],
  use: {
    baseURL: 'http://127.0.0.1:4173',
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    colorScheme: 'light',
    serviceWorkers: 'block',
    trace: 'on',
    screenshot: 'only-on-failure'
  },
  webServer: {
    command: 'npm run preview -- --port 4173 --strictPort',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 30_000
  },
  projects: [
    {
      name: 'desktop-chromium',
      use: {
        browserName: 'chromium',
        viewport: { width: 1440, height: 1000 }
      }
    },
    {
      name: 'mobile-chromium',
      use: {
        browserName: 'chromium',
        viewport: { width: 360, height: 800 },
        isMobile: true,
        hasTouch: true
      }
    }
  ]
});
