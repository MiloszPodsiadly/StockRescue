import { expect, test } from '@playwright/test'

test('real workspace is available, keyboard accessible and fits a narrow viewport', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Backend available' })).toBeVisible()
  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused()
  await page.screenshot({ path: '../.tools/screenshots/workspace-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 360, height: 800 })
  await expect(page.getByRole('heading', { name: /A strong start/ })).toBeVisible()
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
