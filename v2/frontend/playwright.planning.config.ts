import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e-planning',
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  timeout: 30_000,
  expect: {
    timeout: 5_000
  },
  outputDir: 'artifacts/planning-board/results',
  reporter: [
    ['list'],
    ['html', { outputFolder: 'artifacts/planning-board/html', open: 'never' }],
    ['junit', { outputFile: 'artifacts/planning-board/junit.xml' }]
  ],
  use: {
    baseURL: 'http://127.0.0.1:4181/docs/v2-cutover/review-board/',
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    colorScheme: 'light',
    serviceWorkers: 'block',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  webServer: {
    command: 'python3 -m http.server 4181 --bind 127.0.0.1 --directory ../..',
    url: 'http://127.0.0.1:4181/docs/v2-cutover/review-board/',
    reuseExistingServer: !process.env.CI,
    timeout: 30_000
  },
  projects: [
    {
      name: 'planning-desktop',
      use: {
        browserName: 'chromium',
        viewport: { width: 1440, height: 1000 }
      }
    },
    {
      name: 'planning-mobile',
      use: {
        browserName: 'chromium',
        viewport: { width: 360, height: 800 },
        isMobile: true,
        hasTouch: true
      }
    }
  ]
});
