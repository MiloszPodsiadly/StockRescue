import { expect } from '@playwright/test'
import type { Locator, Page, Response } from '@playwright/test'

/** Test accounts share a proxy/IP bucket. Retry clicks only after a known rejection. */
export async function clickWhenAdmitted(page: Page, button: Locator, saved: Locator, prepare?: () => Promise<void>) {
  let limited = false
  const observe = (response: Response) => { if (response.status() === 429 && /^\/api\/v1\/auth\/(csrf|sign-up|sign-in)$/.test(new URL(response.url()).pathname)) limited = true }
  page.on('response', observe)
  try {
    await prepare?.(); await button.click()
    await expect.poll(async () => {
      if (await saved.isVisible()) return true
      // An uncertain business response never permits another submission.
      if (limited && await button.isEnabled()) { limited = false; await prepare?.(); await button.click() }
      return false
    }, { timeout: 16000, intervals: [600, 1100, 2100] }).toBe(true)
  } finally { page.off('response', observe) }
}

/** Durable forms keep the original command and offer a dedicated recovery button. */
export async function submitCapturedWhenAdmitted(page: Page, form: Locator, submitLabel: string, saved: Locator) {
  let limited = false
  const observe = (response: Response) => {
    if (response.status() === 429 && new URL(response.url()).pathname === '/api/v1/auth/csrf') limited = true
  }
  page.on('response', observe)
  try {
    await form.getByRole('button', { name: submitLabel, exact: true }).click()
    await expect.poll(async () => {
      if (await saved.isVisible()) return true
      const retry = form.getByRole('button', { name: 'Retry same request', exact: true })
      if (limited && await retry.isVisible() && await retry.isEnabled()) { limited = false; await retry.click() }
      return false
    }, { timeout: 16000, intervals: [600, 1100, 2100] }).toBe(true)
  } finally { page.off('response', observe) }
}
