import { expect, test, type Page } from '@playwright/test'

async function expectBusinessLanding(page: Page) {
  await expect(page.getByRole('heading', { name: /Your team\.\s*One workspace\./ })).toBeVisible()
  await expect(page.getByRole('heading', { name: /Backend (available|unavailable)/ })).toHaveCount(0)
  await expect(page.getByRole('status')).toHaveCount(0)
}

test('business landing page is keyboard accessible and fits a narrow viewport', async ({ page }) => {
  test.skip(process.env.EXPECT_COLD_START === 'unavailable')
  await page.goto('/')
  await expectBusinessLanding(page)
  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused()
  await page.screenshot({ path: '../.tools/screenshots/workspace-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 360, height: 800 })
  await expectBusinessLanding(page)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: '../.tools/screenshots/workspace-mobile.png', fullPage: true })
})

test('landing page remains business focused when readiness is unavailable', async ({ page }) => {
  let readinessRequests = 0
  await page.route('**/api/health/readiness', route => {
    readinessRequests += 1
    return route.abort('connectionrefused')
  })
  await page.goto('/')
  const readinessStatus = await page.evaluate(async () => {
    try { return (await fetch('/api/health/readiness')).status } catch { return 0 }
  })
  expect(readinessStatus).toBe(0)
  expect(readinessRequests).toBe(1)
  await expectBusinessLanding(page)
})

test('landing page stays business focused when readiness returns an invalid payload', async ({ page }) => {
  await page.route('**/api/health/readiness', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: '{"status":"UP","components":{"db":{"status":"DOWN"}}}',
  }))
  await page.goto('/')
  const readiness = await page.evaluate(async () => {
    const response = await fetch('/api/health/readiness')
    return { status: response.status, payload: await response.json() }
  })
  expect(readiness.status).toBe(200)
  expect(readiness.payload.components.db.status).toBe('DOWN')
  await expectBusinessLanding(page)
})

test('cold-start with a missing optional integration still reports database readiness', async ({ page }) => {
  test.skip(process.env.EXPECT_COLD_START !== 'ready')
  await page.goto('/')
  await expectBusinessLanding(page)
  expect(await page.evaluate(async () => (await fetch('/api/health/readiness')).status)).toBe(200)
  expect(await page.evaluate(async () => (await fetch('/api/health/integration')).status)).toBe(503)
  await expect(page.getByText(/integrations? healthy/i)).toHaveCount(0)
})

test('cold-start without a backend still serves the business landing page', async ({ page }) => {
  test.skip(process.env.EXPECT_COLD_START !== 'unavailable')
  const response = await page.goto('/')
  expect(response?.status()).toBe(200)
  await expectBusinessLanding(page)
  const readinessStatus = await page.evaluate(async () => {
    try { return (await fetch('/api/health/readiness')).status } catch { return 0 }
  })
  expect(readinessStatus).toBeGreaterThanOrEqual(500)
})
