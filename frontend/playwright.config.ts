import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  workers: 1,
  retries: 0,
  timeout: 20_000,
  outputDir: '.tools/playwright-results',
  reporter: 'list',
  use: {
    baseURL: process.env.BASE_URL ?? 'http://127.0.0.1:18080',
    viewport: { width: 1280, height: 900 },
    screenshot: 'only-on-failure',
  },
})
