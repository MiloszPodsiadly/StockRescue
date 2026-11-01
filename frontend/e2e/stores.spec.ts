import { expect, test } from '@playwright/test'
import type { BrowserContext } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { basename, dirname, resolve } from 'node:path'
import type { components } from '../src/api/schema'
import { clickWhenAdmitted } from './support/admission'

test('owner creates four stores in the UI and retains their data after PostgreSQL and backend replacement', async ({ browser }) => {
  test.setTimeout(120000)
  const project = process.env.STOCKRESCUE_TEST_PROJECT ?? ''
  const envFile = process.env.STOCKRESCUE_TEST_ENV_FILE ?? ''
  const root = resolve(process.cwd(), '..')
  expect(/^stockrescue-check-[a-f0-9]{16}$/.test(project)).toBe(true)
  expect(dirname(resolve(envFile)) === resolve(root, '.tools') && basename(envFile) === `${project}.env`).toBe(true)
  const context = await browser.newContext()
  const foreignContext = await browser.newContext()
  try {
    const email = `stores-${randomUUID()}@example.test`
    const credentials = { email, password: 'real-store-owner-passphrase' }
    await write(context, '/api/v1/auth/sign-up', credentials, 202)
    await write(context, '/api/v1/auth/sign-in', credentials, 200)
    const org = await write(context, '/api/v1/organizations', { requestId: randomUUID(), name: 'Four-store cooperative' }, 201) as { id: string }
    const page = await context.newPage()
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(`/organizations/${org.id}/stores`)
    await expect(page.getByRole('heading', { name: 'Stores', exact: true })).toBeVisible()
    const suffix = randomUUID().slice(0, 8).toUpperCase()
    const directions = ['North', 'South', 'East', 'West']
    for (const [index, direction] of directions.entries()) {
      if (index) await page.getByRole('button', { name: 'Create another store' }).click()
      await page.getByLabel('Store code').fill(`${direction.toUpperCase()}_${suffix}`)
      await page.getByLabel('Store name').fill(`${direction} store ${suffix}`)
      await page.getByLabel('City', { exact: true }).fill('Warsaw')
      await page.getByLabel('Country code').fill('PL')
      await page.getByLabel('IANA timezone').fill('Europe/Warsaw')
      await clickWhenAdmitted(page, page.getByRole('button', { name: 'Create store', exact: true }), page.getByRole('heading', { name: `${direction} store ${suffix}`, exact: true }))
      await expect(page.getByRole('heading', { name: `${direction} store ${suffix}`, exact: true })).toBeVisible()
      await expect(page.getByRole('button', { name: 'Create another store' })).toBeVisible()
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const endpoint = `/api/v1/organizations/${org.id}/stores?state=ALL`
    const before = await (await context.request.get(endpoint)).json() as components['schemas']['StorePage']
    expect(before.items).toHaveLength(4)
    expect(new Set(before.items.map(store => store.id)).size).toBe(4)
    await page.reload()
    for (const direction of directions) await expect(page.getByRole('heading', { name: `${direction} store ${suffix}`, exact: true })).toBeVisible()
    const docker = promisify(execFile)
    const args = ['compose', '--env-file', envFile, '-p', project]
    await docker('docker', [...args, 'restart', 'postgres'], { cwd: root, timeout: 90000 })
    await docker('docker', [...args, 'up', '-d', '--wait', '--wait-timeout', '90', 'postgres'], { cwd: root, timeout: 100000 })
    await docker('docker', [...args, 'up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '90', 'backend'], { cwd: root, timeout: 100000 })
    await expect.poll(async () => {
      try { return (await context.request.get(endpoint, { timeout: 5000 })).status() } catch { return 0 }
    }, { timeout: 30000, intervals: [500, 1000, 2000] }).toBe(200)
    const after = await (await context.request.get(endpoint)).json() as components['schemas']['StorePage']
    expect(after).toEqual(before)
    await page.reload()
    for (const direction of directions) await expect(page.getByRole('heading', { name: `${direction} store ${suffix}`, exact: true })).toBeVisible()
    const foreignCredentials = { email: `foreign-${randomUUID()}@example.test`, password: 'foreign-store-owner-passphrase' }
    await write(foreignContext, '/api/v1/auth/sign-up', foreignCredentials, 202)
    await write(foreignContext, '/api/v1/auth/sign-in', foreignCredentials, 200)
    const foreignOrg = await write(foreignContext, '/api/v1/organizations', { requestId: randomUUID(), name: 'Other cooperative' }, 201) as { id: string }
    const foreignStore = await write(foreignContext, `/api/v1/organizations/${foreignOrg.id}/stores`, { requestId: randomUUID(), code: before.items[0].code, name: 'Private foreign store', zoneId: 'UTC' }, 201) as { id: string }
    expect((await context.request.get(`/api/v1/organizations/${org.id}/stores/${foreignStore.id}`)).status()).toBe(404)
    const foreignPage = await foreignContext.newPage()
    await foreignPage.goto(`/organizations/${org.id}/stores/${before.items[0].id}`)
    await expect(foreignPage.getByRole('heading', { name: 'Organization unavailable' })).toBeVisible()
    await expect(foreignPage.getByText(before.items[0].name, { exact: true })).toHaveCount(0)
    await page.getByRole('link', { name: before.items[0].name, exact: true }).click()
    await page.getByRole('button', { name: 'Edit store', exact: true }).click()
    await page.getByLabel('Store name').fill('Renamed durable store')
    await page.getByLabel('Reason for store change').fill('Reviewed store name update')
    await page.getByRole('button', { name: 'Save store changes' }).click()
    await expect(page.getByRole('heading', { name: 'Renamed durable store', exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'Deactivate store', exact: true }).click()
    await page.getByLabel('Reason for deactivation').fill('Location closed to new operations')
    await page.getByRole('checkbox', { name: 'I confirm this store should be deactivated.' }).check()
    await page.getByRole('button', { name: 'Confirm deactivation' }).click()
    await expect(page.getByRole('button', { name: 'Edit store', exact: true })).toHaveCount(0)
    const archived = await (await context.request.get(endpoint)).json() as components['schemas']['StorePage']
    expect(archived.items).toHaveLength(4)
    expect(archived.items.find(store => store.id === before.items[0].id)?.active).toBe(false)
    expect(await page.evaluate(() => Object.keys(localStorage).length + Object.keys(sessionStorage).length)).toBe(0)
  } finally { await context.close(); await foreignContext.close() }
})

async function write(context: BrowserContext, path: string, data: unknown, expected: number) {
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
