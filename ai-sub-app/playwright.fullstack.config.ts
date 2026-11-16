import { defineConfig } from '@playwright/test'

// Browser plugin not available: this workflow uses the existing Playwright Chromium.
export default defineConfig({
  testDir: './e2e/fullstack',
  outputDir: process.env.AI_E2E_OUTPUT + '/browser-results',
  reporter: [['list'], ['json', { outputFile: process.env.AI_E2E_OUTPUT + '/playwright.json' }]],
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 90000,
  expect: { timeout: 15000 },
  use: {
    baseURL: 'http://localhost:5174',
    // Keep IAM's registered localhost callback on the IPv4 preview, away from existing IPv6 dev servers.
    launchOptions: { args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
})
