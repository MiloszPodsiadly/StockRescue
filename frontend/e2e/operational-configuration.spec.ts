import { expect, test } from '@playwright/test'
import type { BrowserContext, Page } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import type { components } from '../src/api/schema'
import { clickWhenAdmitted } from './support/admission'

test('four-store owner configures compatible zones and preserves exact current and future lane cost history', async ({ browser }) => {
  test.setTimeout(90000)
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } })
  try {
    const credentials = { email: `configuration-${randomUUID()}@example.test`, password: 'real-configuration-owner-passphrase' }
    await write(context, '/api/v1/auth/sign-up', credentials, 202)
    await write(context, '/api/v1/auth/sign-in', credentials, 200)
    const org = await write(context, '/api/v1/organizations', { requestId: randomUUID(), name: 'Configured cooperative' }, 201) as { id: string }
    const stores: components['schemas']['StoreResponse'][] = []
    for (const code of ['NORTH', 'SOUTH', 'EAST', 'WEST']) stores.push(await write(context, `/api/v1/organizations/${org.id}/stores`, { requestId: randomUUID(), code, name: `${code} shop`, zoneId: 'Europe/Warsaw' }, 201) as components['schemas']['StoreResponse'])
    const page = await context.newPage()
    for (const [index, store] of stores.entries()) {
      if (!index) await page.goto(`/organizations/${org.id}/stores/${store.id}/storage-zones`)
      else await openZones(page, store.name)
      await expect(page.getByRole('heading', { name: 'No storage zones' })).toBeVisible()
      await page.getByLabel('Zone name', { exact: true }).fill(`${store.code} ambient`)
      await page.getByLabel('Temperature class').selectOption('AMBIENT')
      await clickWhenAdmitted(page, page.getByRole('button', { name: 'Create zone', exact: true }), page.getByRole('heading', { name: `${store.code} ambient`, exact: true }))
      await expect(page.getByRole('heading', { name: `${store.code} ambient`, exact: true })).toBeVisible()
      await expect(page.getByRole('status')).toHaveText('Storage zone configuration saved.')
      await expect(page.getByLabel('Zone name', { exact: true })).toHaveValue('')
    }
    await openZones(page, stores[1].name)
    await page.getByLabel('Zone name', { exact: true }).fill('SOUTH chilled')
    await page.getByLabel('Temperature class').selectOption('CHILLED')
    await clickWhenAdmitted(page, page.getByRole('button', { name: 'Create zone', exact: true }), page.getByRole('heading', { name: 'SOUTH chilled', exact: true }))
    await expect(page.getByRole('heading', { name: 'SOUTH chilled', exact: true })).toBeVisible()
    await page.getByRole('navigation', { name: 'Workspace navigation', exact: true }).getByRole('link', { name: 'Transport lanes', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'No transport lanes' })).toBeVisible()
    await page.getByLabel('Origin store', { exact: true }).selectOption(stores[0].id)
    await page.getByLabel('Destination store', { exact: true }).selectOption(stores[1].id)
    await page.getByLabel('Origin zone', { exact: true }).selectOption({ label: 'NORTH ambient · AMBIENT' })
    await page.getByLabel('Destination zone', { exact: true }).selectOption({ label: 'SOUTH ambient · AMBIENT' })
    await page.getByLabel('Reason for lane configuration').fill('Approved ambient route configuration')
    await page.getByRole('button', { name: 'Create lane', exact: true }).click()
    await page.getByRole('link', { name: 'Open saved lane', exact: true }).click()
    await expect(page.getByText('UNCONFIGURED', { exact: true })).toBeVisible()
    await expect(page.getByText('Current cost and duration: unavailable.', { exact: true })).toBeVisible()
    for (const amount of ['999999999999999.9999', '12.34']) {
      await page.getByLabel('Cost amount', { exact: true }).fill(amount)
      await page.getByLabel('Currency', { exact: true }).selectOption('PLN')
      await page.getByLabel('Journey duration (minutes)').fill('45')
      await page.getByLabel('Reason for cost revision').fill('Reviewed carrier quote')
      await page.getByRole('button', { name: 'Approve cost revision', exact: true }).click()
      await expect(page.getByText(`${amount === '12.34' ? '12.3400' : amount} PLN`, { exact: true })).toBeVisible()
    }
    await expect(page.getByRole('heading', { name: 'Revision 1', exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Revision 2', exact: true })).toBeVisible()
    await page.getByLabel('Cost amount', { exact: true }).fill('99.99')
    await page.getByLabel('Currency', { exact: true }).selectOption('EUR')
    await page.getByLabel('Journey duration (minutes)').fill('60')
    await page.getByLabel('Schedule a future revision in UTC').check()
    await page.getByLabel('Effective time (UTC)', { exact: true }).fill(new Date(Date.now() + 86400000).toISOString().slice(0, 19))
    await page.getByLabel('Reason for cost revision').fill('Tomorrow approved carrier quote')
    await page.getByRole('button', { name: 'Approve cost revision', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Revision 3', exact: true })).toBeVisible()
    await expect(page.getByText('12.3400 PLN', { exact: true })).toBeVisible()
    const laneUrl = page.url()
    await page.reload()
    for (const revision of [1, 2, 3]) await expect(page.getByRole('heading', { name: `Revision ${revision}`, exact: true })).toBeVisible()
    await page.getByLabel('Destination zone', { exact: true }).selectOption({ label: 'SOUTH chilled · CHILLED' })
    await page.getByLabel('Reason for lane configuration').fill('Compatibility rejection verification')
    await page.getByRole('button', { name: 'Save lane configuration' }).click()
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByText('12.3400 PLN', { exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await openZones(page, stores[1].name)
    await page.getByRole('button', { name: 'Edit SOUTH ambient', exact: true }).click()
    await page.getByLabel('Reason for zone change').fill('Cooling/storage area closed to new operations')
    await page.getByRole('checkbox', { name: 'Permanently deactivate this zone. Routes using it will be unavailable.' }).check()
    await page.getByRole('button', { name: 'Deactivate zone', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'SOUTH ambient', exact: true })).toHaveCount(0)
    await page.goto(laneUrl)
    await expect(page.getByText('INACTIVE', { exact: true })).toBeVisible()
    await expect(page.getByText('Current cost and duration: unavailable.', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Approve cost revision' })).toHaveCount(0)
    for (const revision of [1, 2, 3]) await expect(page.getByRole('heading', { name: `Revision ${revision}`, exact: true })).toBeVisible()
    expect(await page.evaluate(() => Object.keys(localStorage).length + Object.keys(sessionStorage).length)).toBe(0)
  } finally { await context.close() }
})

async function openZones(page: Page, storeName: string) {
  await page.getByRole('navigation', { name: 'Workspace navigation', exact: true }).getByRole('link', { name: 'Stores', exact: true }).click()
  await page.getByRole('link', { name: storeName, exact: true }).click()
  await page.getByRole('link', { name: 'Storage zones', exact: true }).click()
}

async function write(context: BrowserContext, path: string, data: unknown, expected: number): Promise<unknown> {
  let token = ''
  await expect.poll(async () => {
    const response = await context.request.get('/api/v1/auth/csrf')
    if (response.status() === 429) return false
    expect(response.status()).toBe(200)
    token = (await response.json() as { token: string }).token
    return true
  }, { timeout: 12000, intervals: [1100, 2100, 4100] }).toBe(true)
  let result: unknown
  await expect.poll(async () => {
    const response = await context.request.post(path, { headers: { 'X-CSRF-TOKEN': token }, data })
    if (response.status() === 429) return false
    expect(response.status()).toBe(expected)
    result = await response.json()
    return true
  }, { timeout: 12000, intervals: [1100, 2100, 4100] }).toBe(true)
  return result
}
