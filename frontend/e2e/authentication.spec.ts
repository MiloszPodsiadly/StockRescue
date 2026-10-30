import { expect, test } from '@playwright/test'
import { randomUUID } from 'node:crypto'

test('operator creates an account, signs in, refreshes and signs out without cached account data', async ({ page, context }) => {
  const email = `operator-${randomUUID()}@example.test`
  const password = 'a-real-operator-passphrase'
  await page.goto('/sign-up')
  await page.getByLabel('Email address').fill(email)
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('registration request was processed')
  await page.getByRole('link', { name: 'Continue to sign in' }).click()
  await page.getByLabel('Email address').fill(email)
  await page.getByLabel('Password', { exact: true }).fill('incorrect-operator-password')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Sign-in could not be completed')
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.getByRole('heading', { name: "You're signed in." })).toBeVisible()
  await expect(page.getByText(email, { exact: true })).toBeVisible()
  await page.reload()
  await expect(page.getByText(email, { exact: true })).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 })
  const organizationName = `Cooperative ${randomUUID()}`
  await page.getByLabel('Organization name', { exact: true }).fill(organizationName)
  await page.getByRole('button', { name: 'Create organization', exact: true }).click()
  await expect(page.getByRole('heading', { name: organizationName, exact: true })).toBeVisible()
  await expect(page.getByText('Org Admin', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.reload()
  await expect(page.getByRole('heading', { name: organizationName, exact: true })).toBeVisible()
  const csrfResponse = await context.request.get('/api/v1/auth/csrf')
  const { token } = await csrfResponse.json() as { token: string }
  const foreignId = randomUUID()
  const denied = await context.request.post('/api/v1/auth/active-organization', { headers: { 'X-CSRF-TOKEN': token }, data: { organizationId: foreignId } })
  expect(denied.status()).toBe(404)
  expect((await denied.json() as { code: string }).code).toBe('RESOURCE_NOT_FOUND')
  const foreignDetail = await context.request.get(`/api/v1/organizations/${foreignId}`)
  expect(foreignDetail.status()).toBe(404)
  await page.getByRole('button', { name: 'Reload organizations' }).click()
  await expect(page.getByRole('heading', { name: organizationName, exact: true })).toBeVisible()
  const cookie = (await context.cookies()).find(value => value.name === 'SRSESSION')
  expect(cookie).toMatchObject({ httpOnly: true, sameSite: 'Lax', secure: false, path: '/' })
  expect(await page.evaluate(() => Object.keys(localStorage))).toEqual([])
  await page.getByRole('button', { name: 'Sign out', exact: true }).click()
  await expect(page).toHaveURL(/\/sign-in$/)
  await page.goto('/account')
  await expect(page.getByRole('heading', { name: 'Sign in to continue' })).toBeVisible()
  await expect(page.getByText(email, { exact: true })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: organizationName, exact: true })).toHaveCount(0)
})

test('mobile account forms support keyboard navigation and refresh-safe routes', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/sign-in')
  await expect(page.getByRole('heading', { name: 'Sign in', exact: true })).toBeVisible()
  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.locator('#auth-content')).toBeFocused()
  await expect(page.getByLabel('Email address')).toBeVisible()
  await expect(page.getByLabel('Password', { exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'Create an account', exact: true }).click()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Create your account' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('proxy rejects missing CSRF and limits bursts without exposing credentials', async ({ request }) => {
  let token = ''
  // Other real workflows share this proxy/IP bucket. Wait for admission before
  // asserting CSRF, then deliberately fill the bucket in the separate burst below.
  await expect.poll(async () => {
    const csrf = await request.get('/api/v1/auth/csrf')
    if (csrf.status() === 429) return false
    expect(csrf.status()).toBe(200)
    token = (await csrf.json() as { token: string }).token
    return true
  }, { timeout: 8000, intervals: [600, 1100, 2100] }).toBe(true)
  await expect.poll(async () => {
    const missing = await request.post('/api/v1/auth/sign-in', { data: { email: 'operator@example.test', password: 'not-submitted-passphrase' } })
    return missing.status()
  }, { timeout: 8000, intervals: [600, 1100, 2100] }).toBe(403)
  let limited = false
  for (let attempt = 0; attempt < 12; attempt++) {
    const response = await request.post('/api/v1/auth/sign-in', {
      headers: { 'X-CSRF-TOKEN': token }, data: { email: 'invalid-email', password: 'short' },
    })
    if (response.status() === 429) {
      const problem = await response.json() as { code: string; correlationId: string }
      expect(problem.code).toBe('AUTH_RATE_LIMITED')
      expect(problem.correlationId).toBeTruthy()
      limited = true
      break
    }
    expect(response.status()).toBe(400)
  }
  expect(limited).toBe(true)
})
