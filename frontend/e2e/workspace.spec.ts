import { expect, test } from '@playwright/test'

test('real workspace is available, keyboard accessible and fits a narrow viewport', async ({ page }) => {
  test.skip(process.env.EXPECT_COLD_START === 'unavailable')
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Backend available' })).toBeVisible()
  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused()
  await page.screenshot({ path: '../.tools/screenshots/workspace-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 360, height: 800 })
  await expect(page.getByRole('heading', { name: /Your team/ })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: '../.tools/screenshots/workspace-mobile.png', fullPage: true })
})

test('a failed browser request displays unavailability and never a green status', async ({ page }) => {
  await page.route('**/api/health/readiness', route => route.abort('connectionrefused'))
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Backend unavailable' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Backend available' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Check again' })).toBeEnabled()
})

test('a malformed HTTP 200 readiness response never displays a green status', async ({ page }) => {
  await page.route('**/api/health/readiness', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: '{"status":"UP","components":{"db":{"status":"DOWN"}}}',
  }))
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Backend unavailable' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Backend available' })).toHaveCount(0)
})

test('cold-start with a missing optional integration still reports database readiness', async ({ page }) => {
  test.skip(process.env.EXPECT_COLD_START !== 'ready')
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Backend available' })).toBeVisible()
  expect(await page.evaluate(async () => (await fetch('/api/health/integration')).status)).toBe(503)
  await expect(page.getByText(/integrations? healthy/i)).toHaveCount(0)
})

test('cold-start without a backend serves the shell and reports it unavailable', async ({ page }) => {
  test.skip(process.env.EXPECT_COLD_START !== 'unavailable')
  const response = await page.goto('/')
  expect(response?.status()).toBe(200)
  await expect(page.getByRole('heading', { name: 'Backend unavailable' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Backend available' })).toHaveCount(0)
})
