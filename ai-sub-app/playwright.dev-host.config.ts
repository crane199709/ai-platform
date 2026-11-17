import { defineConfig } from '@playwright/test'

// Browser plugin not available: reuse the isolated full-stack platform and Chromium.
export default defineConfig({
  testDir: './e2e/dev-host',
  outputDir: process.env.AI_E2E_OUTPUT + '/dev-host-results',
  reporter: [['list']],
  workers: 1,
  retries: 0,
  timeout: 60000,
  expect: { timeout: 15000 },
  use: {
    baseURL: 'http://localhost:5174',
    launchOptions: { args: ['--host-resolver-rules=MAP localhost 127.0.0.1'] },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
})
