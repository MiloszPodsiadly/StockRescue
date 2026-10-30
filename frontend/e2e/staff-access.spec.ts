import { expect, test } from '@playwright/test'
import type { Browser, BrowserContext } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { clickWhenAdmitted } from './support/admission'

test('administrator invites, verifies, changes roles and revokes staff while their other organization stays accessible', async ({ browser }) => {
  test.setTimeout(60000)
  const owner = await account(browser)
  const recipient = await account(browser)
  try {
    const orgA = await organization(owner.context, `Cooperative A ${randomUUID()}`)
    const orgB = await organization(recipient.context, `Cooperative B ${randomUUID()}`)
    const ownerPage = await owner.context.newPage()
    await ownerPage.setViewportSize({ width: 390, height: 844 })
    await ownerPage.goto(`/organizations/${orgA}/members`)
    await expect(ownerPage.getByRole('heading', { name: 'Members and access' })).toBeVisible()
    await ownerPage.getByLabel('Recipient email').fill(recipient.email)
    await ownerPage.getByLabel('Reason for invitation').fill('New colleague identity review')
    await ownerPage.getByRole('button', { name: 'Create private invitation' }).click()
    const privateLink = await ownerPage.getByLabel('Private invitation link').inputValue()
    await expect(ownerPage.getByText('Invitation created. It has not been emailed.', { exact: false })).toBeVisible()
    expect(await ownerPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const recipientPage = await recipient.context.newPage()
    await recipientPage.goto(privateLink)
    await expect(recipientPage).toHaveURL(/\/invitations\/accept$/)
    await clickWhenAdmitted(recipientPage, recipientPage.getByRole('button', { name: 'Request membership review' }), recipientPage.getByRole('heading', { name: 'Awaiting administrator review' }))
    await expect(recipientPage.getByRole('heading', { name: 'Awaiting administrator review' })).toBeVisible()
    expect((await recipient.context.request.get(`/api/v1/organizations/${orgA}`)).status()).toBe(404)
    expect((await recipient.context.request.get(`/api/v1/organizations/${orgB}`)).status()).toBe(200)
    await ownerPage.reload()
    await ownerPage.getByRole('button', { name: `Manage access for ${recipient.email}`, exact: true }).click()
    await ownerPage.getByLabel('Access operation').selectOption('APPROVE')
    await ownerPage.getByLabel('Verified account reference', { exact: true }).fill(recipient.id)
    await ownerPage.getByRole('checkbox', { name: 'I independently verified' }).check()
    await ownerPage.getByLabel('Reason for access change').fill('Verified intended colleague and signed-in account reference')
    await ownerPage.getByRole('button', { name: 'Apply access change' }).click()
    await expect.poll(async () => (await recipient.context.request.get(`/api/v1/organizations/${orgA}`)).status()).toBe(200)
    await recipientPage.goto(`/organizations/${orgA}/members`)
    await expect(recipientPage.getByRole('heading', { name: 'Access management unavailable' })).toBeVisible()
    await expect(recipientPage.getByText(owner.email, { exact: true })).toHaveCount(0)
    await changeRole('ORG_ADMIN')
    await recipientPage.getByRole('button', { name: 'Check access again' }).click()
    await expect(recipientPage.getByRole('heading', { name: 'Members and access' })).toBeVisible()
    await changeRole('MEMBER')
    await recipientPage.getByRole('button', { name: 'Reload access' }).click()
    await expect(recipientPage.getByRole('heading', { name: 'Access management unavailable' })).toBeVisible()
    await expect(recipientPage.getByText(owner.email, { exact: true })).toHaveCount(0)
    await ownerPage.getByRole('button', { name: `Manage access for ${recipient.email}`, exact: true }).click()
    await ownerPage.getByLabel('Access operation').selectOption('REVOKE')
    await ownerPage.getByLabel('Reason for access change').fill('Staff assignment ended')
    await ownerPage.getByRole('checkbox', { name: 'I confirm this access should be revoked.' }).check()
    await ownerPage.getByRole('button', { name: 'Apply access change' }).click()
    await expect.poll(async () => (await recipient.context.request.get(`/api/v1/organizations/${orgA}`)).status()).toBe(404)
    expect((await recipient.context.request.get('/api/v1/auth/me')).status()).toBe(200)
    expect((await recipient.context.request.get(`/api/v1/organizations/${orgB}`)).status()).toBe(200)
    await recipientPage.reload()
    await expect(recipientPage.getByRole('heading', { name: 'Organization unavailable' })).toBeVisible()
    expect(await recipientPage.evaluate(() => Object.keys(localStorage).length + Object.keys(sessionStorage).length)).toBe(0)

    async function changeRole(role: 'ORG_ADMIN' | 'MEMBER') {
      await ownerPage.getByRole('button', { name: `Manage access for ${recipient.email}`, exact: true }).click()
      await ownerPage.getByLabel('Access operation').selectOption('CHANGE_ROLE')
      await ownerPage.getByLabel('New organization role').selectOption(role)
      await ownerPage.getByLabel('Reason for access change').fill('Reviewed responsibility update')
      await ownerPage.getByRole('button', { name: 'Apply access change' }).click()
      await expect.poll(async () => {
        const response = await owner.context.request.get(`/api/v1/organizations/${orgA}/members`)
        const body = await response.json() as { items: { userId: string, role: string }[] }
        return body.items.find(member => member.userId === recipient.id)?.role
      }).toBe(role)
    }
  } finally { await owner.context.close(); await recipient.context.close() }
})

async function admitted<T>(operation: () => Promise<{ status(): number, json(): Promise<T> }>, expected: number): Promise<T> {
  let data: T | undefined
  await expect.poll(async () => {
    const response = await operation()
    if (response.status() === 429) return false
    expect(response.status()).toBe(expected)
    data = await response.json()
    return true
  }, { timeout: 12000, intervals: [1100, 2100, 4100] }).toBe(true)
  return data!
}
async function write(context: BrowserContext, path: string, data: unknown, expected: number) {
  const csrf = await admitted(() => context.request.get('/api/v1/auth/csrf'), 200) as { token: string }
  return admitted(() => context.request.post(path, { headers: { 'X-CSRF-TOKEN': csrf.token }, data }), expected)
}
async function account(browser: Browser) {
  const context = await browser.newContext()
  const email = `staff-${randomUUID()}@example.test`
  const credentials = { email, password: 'real-staff-access-passphrase' }
  await write(context, '/api/v1/auth/sign-up', credentials, 202)
  const actor = await write(context, '/api/v1/auth/sign-in', credentials, 200) as { id: string }
  return { context, email, id: actor.id }
}
async function organization(context: BrowserContext, name: string) {
  const created = await write(context, '/api/v1/organizations', { requestId: randomUUID(), name }, 201) as { id: string }
  return created.id
}
