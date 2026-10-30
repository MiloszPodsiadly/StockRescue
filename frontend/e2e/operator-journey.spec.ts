import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { clickWhenAdmitted } from './support/admission'

test.use({ actionTimeout: 10000 })

test('new owner configures four stores and transport, grants scoped staff access, revokes it and switches organizations without stale data', async ({ browser }) => {
  test.setTimeout(150000)
  const ownerContext = await browser.newContext({ viewport: { width: 390, height: 844 } })
  const staffContext = await browser.newContext({ viewport: { width: 390, height: 844 } })
  const suffix = randomUUID().slice(0, 8)
  const owner = { email: `owner-journey-${suffix}@example.test`, password: 'first-owner-browser-passphrase' }
  const staff = { email: `staff-journey-${suffix}@example.test`, password: 'first-staff-browser-passphrase' }
  const organizationA = `Harbor grocers ${suffix}`
  const organizationB = `Hill retailers ${suffix}`
  try {
    const ownerPage = await ownerContext.newPage()
    await registerAndSignIn(ownerPage, owner)
    await createOrganization(ownerPage, organizationA)
    await ownerPage.getByRole('link', { name: 'Open stores', exact: true }).click()
    const stores: { id: string, name: string, code: string, href: string }[] = []
    for (const [index, direction] of ['North', 'South', 'East', 'West'].entries()) {
      if (index) await ownerPage.getByRole('button', { name: 'Create another store' }).click()
      const code = `${direction.toUpperCase()}_${suffix.toUpperCase()}`
      const name = `${direction} harbor store`
      await createStore(ownerPage, code, name)
      const href = await ownerPage.getByRole('link', { name, exact: true }).getAttribute('href')
      expect(href).toBeTruthy()
      stores.push({ id: href!.split('/').at(-1)!, name, code, href: href! })
    }
    await accessibilitySmoke(ownerPage)
    await ownerPage.getByRole('link', { name: 'Skip to content', exact: true }).focus()
    await ownerPage.keyboard.press('Enter')
    await expect(ownerPage.getByRole('main')).toBeFocused()
    for (const store of stores) {
      await ownerPage.getByRole('link', { name: store.name, exact: true }).click()
      await ownerPage.getByRole('link', { name: 'Storage zones', exact: true }).click()
      await ownerPage.getByLabel('Zone name', { exact: true }).fill('Ambient room')
      await ownerPage.getByLabel('Temperature class').selectOption('AMBIENT')
      await ownerPage.getByRole('button', { name: 'Create zone', exact: true }).click()
      await expect(ownerPage.getByRole('heading', { name: 'Ambient room', exact: true })).toBeVisible()
      await navigation(ownerPage).getByRole('link', { name: 'Stores', exact: true }).click()
    }
    await navigation(ownerPage).getByRole('link', { name: 'Transport lanes', exact: true }).click()
    await ownerPage.getByLabel('Origin store', { exact: true }).selectOption(stores[0].id)
    await ownerPage.getByLabel('Destination store', { exact: true }).selectOption(stores[1].id)
    await ownerPage.getByLabel('Origin zone', { exact: true }).selectOption({ label: 'Ambient room · AMBIENT' })
    await ownerPage.getByLabel('Destination zone', { exact: true }).selectOption({ label: 'Ambient room · AMBIENT' })
    await ownerPage.getByLabel('Reason for lane configuration').fill('Approved route for the two stores')
    await ownerPage.getByRole('button', { name: 'Create lane', exact: true }).click()
    await ownerPage.getByRole('link', { name: 'Open saved lane', exact: true }).click()
    await expect(ownerPage.getByText('Current cost and duration: unavailable.', { exact: true })).toBeVisible()
    for (const amount of ['27.1250', '31.5000']) {
      await ownerPage.getByLabel('Cost amount', { exact: true }).fill(amount)
      await ownerPage.getByLabel('Currency', { exact: true }).selectOption('PLN')
      await ownerPage.getByLabel('Journey duration (minutes)').fill('45')
      await ownerPage.getByLabel('Reason for cost revision').fill('Reviewed carrier agreement')
      await ownerPage.getByRole('button', { name: 'Approve cost revision', exact: true }).click()
      await expect(ownerPage.getByText(`${amount} PLN`, { exact: true })).toBeVisible()
    }
    await expect(ownerPage.getByRole('heading', { name: 'Revision 1', exact: true })).toBeVisible()
    await expect(ownerPage.getByRole('heading', { name: 'Revision 2', exact: true })).toBeVisible()
    await navigation(ownerPage).getByRole('link', { name: 'Settings', exact: true }).click()
    await expect(ownerPage.getByRole('heading', { name: 'Settings', exact: true })).toBeVisible()
    await expect(navigation(ownerPage).getByRole('link', { name: 'Settings', exact: true })).toHaveAttribute('aria-current', 'page')
    await accessibilitySmoke(ownerPage)
    await ownerPage.setViewportSize({ width: 1280, height: 900 })
    await ownerPage.screenshot({ path: '../.tools/screenshots/settings-desktop.png', fullPage: true })
    await ownerPage.setViewportSize({ width: 390, height: 844 })
    await ownerPage.screenshot({ path: '../.tools/screenshots/settings-mobile.png', fullPage: true })

    const staffPage = await staffContext.newPage()
    await registerAndSignIn(staffPage, staff)
    const staffReference = await staffPage.locator('[data-account-reference]').innerText()
    await createOrganization(staffPage, organizationB)
    await staffPage.getByRole('link', { name: 'Open stores', exact: true }).click()
    await createStore(staffPage, `HILL_${suffix.toUpperCase()}`, 'Private hill store')
    const privateStoreHref = await staffPage.getByRole('link', { name: 'Private hill store', exact: true }).getAttribute('href')

    await navigation(ownerPage).getByRole('link', { name: 'Members and access', exact: true }).click()
    await ownerPage.getByLabel('Recipient email').fill(staff.email)
    await ownerPage.getByLabel('Initial store (optional)').selectOption(stores[0].id)
    await ownerPage.getByLabel('Initial store role').selectOption('STORE_MANAGER')
    await ownerPage.getByLabel('Reason for invitation').fill('Assign the new North store manager')
    await ownerPage.getByRole('button', { name: 'Create private invitation' }).click()
    const privateLink = await ownerPage.getByLabel('Private invitation link').inputValue()
    await staffPage.goto(privateLink)
    await expect(staffPage).toHaveURL(/\/invitations\/accept$/)
    await clickWhenAdmitted(staffPage, staffPage.getByRole('button', { name: 'Request membership review' }), staffPage.getByRole('heading', { name: 'Awaiting administrator review' }))
    await expect(staffPage.getByText(staffReference, { exact: true })).toBeVisible()
    await ownerPage.getByRole('button', { name: 'Reload access', exact: true }).click()
    await manageStaff(ownerPage, staff.email, 'APPROVE', 'Verified intended colleague through the trusted contact')
    await ownerPage.getByLabel('Verified account reference', { exact: true }).fill(staffReference)
    await ownerPage.getByRole('checkbox', { name: 'I independently verified the intended person and this account reference.' }).check()
    await ownerPage.getByRole('button', { name: 'Apply access change', exact: true }).click()
    await expect(ownerPage.getByText('Access change saved.', { exact: true })).toBeVisible()

    await staffPage.getByRole('link', { name: 'Return to your account', exact: true }).click()
    await staffPage.getByLabel('Organization', { exact: true }).selectOption({ label: organizationA })
    await expect(staffPage.getByRole('heading', { name: organizationA, exact: true })).toBeVisible()
    await staffPage.getByRole('link', { name: 'Open stores', exact: true }).click()
    await expect(staffPage.getByRole('heading', { name: stores[0].name, exact: true })).toBeVisible()
    for (const store of stores.slice(1)) await expect(staffPage.getByRole('heading', { name: store.name, exact: true })).toHaveCount(0)
    await expect(navigation(staffPage).getByRole('link', { name: 'Members and access', exact: true })).toHaveCount(0)
    await expect(navigation(staffPage).getByRole('link', { name: 'Transport lanes', exact: true })).toHaveCount(0)
    await staffPage.getByRole('link', { name: stores[0].name, exact: true }).click()
    await expect(staffPage.getByRole('button', { name: 'Edit store', exact: true })).toBeVisible()
    await expect(staffPage.getByRole('button', { name: 'Deactivate store', exact: true })).toHaveCount(0)
    await staffPage.goto(stores[1].href)
    await expect(staffPage.getByRole('heading', { name: 'Store unavailable' })).toBeVisible()
    await expect(staffPage.getByText(stores[1].name, { exact: true })).toHaveCount(0)
    await navigation(staffPage).getByRole('link', { name: 'Stores', exact: true }).click()
    await expect(staffPage.getByRole('heading', { name: stores[0].name, exact: true })).toBeVisible()

    await manageStaff(ownerPage, staff.email, 'REVOKE', 'Staff assignment ended')
    await ownerPage.getByRole('checkbox', { name: 'I confirm this access should be revoked.' }).check()
    await ownerPage.getByRole('button', { name: 'Apply access change', exact: true }).click()
    await expect(ownerPage.getByText('Access change saved.', { exact: true })).toBeVisible()
    await staffPage.getByRole('button', { name: 'Reload stores', exact: true }).click()
    await expect(staffPage.getByRole('heading', { name: 'Organization unavailable' })).toBeVisible()
    await expect(staffPage.getByText(stores[0].name, { exact: true })).toHaveCount(0)
    await expect(staffPage.getByRole('heading', { name: organizationA, exact: true })).toHaveCount(0)
    await staffPage.getByRole('link', { name: 'Your organizations', exact: true }).click()
    await staffPage.getByLabel('Organization', { exact: true }).selectOption({ label: organizationB })
    await staffPage.getByRole('link', { name: 'Open stores', exact: true }).click()
    await expect(staffPage.getByRole('heading', { name: 'Private hill store', exact: true })).toBeVisible()
    await expect(staffPage.getByRole('heading', { name: organizationA, exact: true })).toHaveCount(0)
    for (const store of stores) await expect(staffPage.getByText(store.name, { exact: true })).toHaveCount(0)
    await accessibilitySmoke(staffPage)

    const otherTab = await staffContext.newPage()
    await otherTab.goto(privateStoreHref!)
    await expect(otherTab.getByRole('heading', { name: 'Private hill store', exact: true })).toBeVisible()
    await staffPage.getByRole('button', { name: 'Sign out', exact: true }).click()
    await expect(staffPage).toHaveURL(/\/sign-in$/)
    await expect(otherTab.getByRole('heading', { name: 'Sign in to continue' })).toBeVisible()
    await expect(otherTab.getByText('Private hill store', { exact: true })).toHaveCount(0)
    await signIn(staffPage, owner)
    await expect(staffPage.getByText(staff.email, { exact: true })).toHaveCount(0)
    await staffPage.getByLabel('Organization', { exact: true }).selectOption({ label: organizationA })
    await staffPage.getByRole('link', { name: 'Open stores', exact: true }).click()
    for (const store of stores) await expect(staffPage.getByRole('heading', { name: store.name, exact: true })).toBeVisible()
    await expect(staffPage.getByText('Private hill store', { exact: true })).toHaveCount(0)
    await expect(otherTab.getByText('Private hill store', { exact: true })).toHaveCount(0)
    for (const page of [ownerPage, staffPage, otherTab]) expect(await page.evaluate(() => Object.keys(localStorage).length + Object.keys(sessionStorage).length)).toBe(0)
  } finally { await ownerContext.close(); await staffContext.close() }
})

function navigation(page: Page) { return page.getByRole('navigation', { name: 'Workspace navigation', exact: true }) }
async function createOrganization(page: Page, name: string) {
  await page.getByLabel('Organization name', { exact: true }).fill(name)
  await page.getByRole('button', { name: 'Create organization', exact: true }).click()
  await expect(page.getByRole('heading', { name, exact: true })).toBeVisible()
}
async function createStore(page: Page, code: string, name: string) {
  await page.getByLabel('Store code').fill(code)
  await page.getByLabel('Store name').fill(name)
  await page.getByLabel('IANA timezone').fill('Europe/Warsaw')
  await page.getByRole('button', { name: 'Create store', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Create another store', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name, exact: true })).toBeVisible()
}
async function manageStaff(page: Page, email: string, operation: 'APPROVE' | 'REVOKE', reason: string) {
  await page.getByRole('button', { name: `Manage access for ${email}`, exact: true }).click()
  await page.getByLabel('Access operation', { exact: true }).selectOption(operation)
  await page.getByLabel('Reason for access change', { exact: true }).fill(reason)
}
async function registerAndSignIn(page: Page, credentials: { email: string, password: string }) {
  await page.goto('/sign-up')
  await expect(page.getByRole('heading', { name: 'Create your account', exact: true })).toBeVisible()
  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.locator('#auth-content')).toBeFocused()
  await page.getByLabel('Email address').fill(credentials.email)
  await clickWhenAdmitted(page, page.getByRole('button', { name: 'Create account', exact: true }), page.getByRole('status'), async () => { await page.getByLabel('Password', { exact: true }).fill(credentials.password) })
  await page.getByRole('link', { name: 'Continue to sign in', exact: true }).click()
  await signIn(page, credentials)
}
async function signIn(page: Page, credentials: { email: string, password: string }) {
  await page.getByLabel('Email address').fill(credentials.email)
  await clickWhenAdmitted(page, page.getByRole('button', { name: 'Sign in', exact: true }), page.getByRole('heading', { name: "You're signed in.", exact: true }), async () => { await page.getByLabel('Password', { exact: true }).fill(credentials.password) })
}
async function accessibilitySmoke(page: Page) {
  await expect(page.getByRole('main')).toHaveCount(1)
  await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  const unlabeled = await page.locator('input:not([type="hidden"]), select, textarea').evaluateAll(elements => elements.filter(element => element.getClientRects().length > 0).filter(element => !element.getAttribute('aria-label') && !element.getAttribute('aria-labelledby') && !Array.from((element as HTMLInputElement).labels ?? []).some(label => label.textContent?.trim())).map(element => element.outerHTML.slice(0, 120)))
  expect(unlabeled).toEqual([])
}
