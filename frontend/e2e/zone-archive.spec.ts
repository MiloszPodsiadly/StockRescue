import { expect, test } from '@playwright/test'
import type { BrowserContext } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import type { components } from '../src/api/schema'

test('reviews zone impact before permanent archival and preserves future revision history', async ({ browser }) => {
  test.setTimeout(90000)
  test.skip(!process.env.STOCKRESCUE_TEST_PROJECT || !process.env.STOCKRESCUE_TEST_ENV_FILE,
    'Run this browser scenario through the Compose integration check.')

  const context = await browser.newContext({ viewport: { width: 390, height: 844 } })
  try {
    const credentials = { email: `zone-archive-${randomUUID()}@example.test`, password: 'real-zone-archive-owner-passphrase' }
    await write(context, '/api/v1/auth/sign-up', credentials, 202)
    await write(context, '/api/v1/auth/sign-in', credentials, 200)
    const organization = await write(context, '/api/v1/organizations', { requestId: randomUUID(), name: 'Zone impact cooperative' }, 201) as { id: string }
    const originStore = await createStore(context, organization.id, 'NORTH')
    const destinationStore = await createStore(context, organization.id, 'SOUTH')
    const originZone = await createZone(context, organization.id, originStore.id, 'North ambient')
    const destinationZone = await createZone(context, organization.id, destinationStore.id, 'South ambient')
    const lane = await write(context, `/api/v1/organizations/${organization.id}/transport-lanes`, {
      originStoreId: originStore.id, destinationStoreId: destinationStore.id,
      originZoneId: originZone.id, destinationZoneId: destinationZone.id,
      reason: 'Configure an approved ambient route',
    }, 201) as components['schemas']['LaneResponse']
    const lanePath = `/api/v1/organizations/${organization.id}/transport-lanes/${lane.id}`
    const laneUiPath = `/organizations/${organization.id}/transport-lanes/${lane.id}`
    const current = await write(context, `${lanePath}/revisions`, {
      laneVersion: lane.version, expectedRevision: lane.latestRevision,
      costAmount: '12.3400', currencyCode: 'PLN', durationMinutes: 30,
      reason: 'Approve the current route cost',
    }, 201) as components['schemas']['LaneResponse']
    const future = await write(context, `${lanePath}/revisions`, {
      laneVersion: current.version, expectedRevision: current.latestRevision,
      costAmount: '14.5000', currencyCode: 'EUR', durationMinutes: 35,
      effectiveAt: new Date(Date.now() + 2 * 86400000).toISOString(),
      reason: 'Schedule the next carrier cost',
    }, 201) as components['schemas']['LaneResponse']

    const page = await context.newPage()
    const zonePage = `/organizations/${organization.id}/stores/${originStore.id}/storage-zones`
    await page.goto(zonePage)
    await expect(page.getByRole('heading', { name: originZone.name })).toBeVisible()
    await page.getByRole('button', { name: `Edit ${originZone.name}` }).click()
    await page.getByRole('button', { name: 'Review impact before deactivation' }).click()
    const impact = page.getByRole('region', { name: 'Zone archive impact' })
    await expect(impact).toBeVisible()
    await expect(impact).toContainText(/Active lanes affected\s*1/)
    await expect(impact).toContainText(/Inactive lanes affected\s*0/)
    await expect(impact).toContainText(/Future scheduled revisions\s*1/)
    await expect(impact).toContainText('Future revisions remain in history and are not cancelled automatically.')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)

    await page.getByRole('button', { name: 'Cancel deactivation review' }).click()
    const unchangedBeforeConfirmation = await getZonePage(context, organization.id, originStore.id, true)
    expect(unchangedBeforeConfirmation.items.find(item => item.id === originZone.id)?.active).toBe(true)
    await expect(page.getByRole('button', { name: 'Confirm zone deactivation' })).toHaveCount(0)

    await page.getByRole('button', { name: 'Review impact before deactivation' }).click()
    await expect(impact).toContainText(/Future scheduled revisions\s*1/)
    await page.getByLabel('Reason for deactivation').fill('Reviewed affected lane and future carrier schedule')
    const confirmation = page.getByLabel('I reviewed this impact and confirm permanent deactivation.')
    await confirmation.check()
    await expect(page.getByRole('button', { name: 'Confirm zone deactivation' })).toBeEnabled()
    await page.setViewportSize({ width: 1280, height: 900 })
    await expect(impact).toContainText(/Active lanes affected\s*1/)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.setViewportSize({ width: 390, height: 844 })
    await page.getByRole('button', { name: 'Confirm zone deactivation' }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Storage zone configuration saved.' })).toHaveText('Storage zone configuration saved.')

    const archivedZone = await getZonePage(context, organization.id, originStore.id, true)
    expect(archivedZone.items.find(item => item.id === originZone.id)?.active).toBe(false)
    const laneAfterArchive = await (await context.request.get(lanePath)).json() as components['schemas']['LaneResponse']
    expect(laneAfterArchive.status).toBe('INACTIVE')
    expect(laneAfterArchive.missing).toContain('INACTIVE_ZONE')
    const historyPath = `${lanePath}/revisions?offset=0&limit=25`
    const beforeCancellation = await (await context.request.get(historyPath)).json() as components['schemas']['RevisionPageResponse']
    expect(beforeCancellation.items).toHaveLength(2)
    const scheduled = beforeCancellation.items.find(revision => revision.revision === future.latestRevision)
    expect(scheduled).toBeDefined()
    expect(scheduled?.cancellation).toBeNull()

    await write(context, `${lanePath}/scheduled-revisions/cancel`, {
      laneVersion: laneAfterArchive.version, expectedRevision: laneAfterArchive.latestRevision,
      reason: 'Cancel the future cost after reviewing zone archival',
    }, 200)
    const afterCancellation = await (await context.request.get(historyPath)).json() as components['schemas']['RevisionPageResponse']
    expect(afterCancellation.items).toHaveLength(2)
    expect(afterCancellation.items.find(revision => revision.id === scheduled?.id)?.cancellation).not.toBeNull()
    await page.goto(laneUiPath)
    await expect(page.getByText('INACTIVE', { exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  } finally { await context.close() }
})

async function createStore(context: BrowserContext, organizationId: string, code: string) {
  return await write(context, `/api/v1/organizations/${organizationId}/stores`, {
    requestId: randomUUID(), code, name: `${code} shop`, zoneId: 'Europe/Warsaw',
  }, 201) as components['schemas']['StoreResponse']
}

async function createZone(context: BrowserContext, organizationId: string, storeId: string, name: string) {
  return await write(context, `/api/v1/organizations/${organizationId}/stores/${storeId}/storage-zones`, {
    name, temperatureClass: 'AMBIENT',
  }, 201) as components['schemas']['ZoneResponse']
}

async function getZonePage(context: BrowserContext, organizationId: string, storeId: string, includeInactive: boolean) {
  const response = await context.request.get(`/api/v1/organizations/${organizationId}/stores/${storeId}/storage-zones?includeInactive=${includeInactive}&offset=0&limit=100`)
  expect(response.status()).toBe(200)
  return await response.json() as components['schemas']['ZonePageResponse']
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
    result = response.status() === 204 ? undefined : await response.json()
    return true
  }, { timeout: 12000, intervals: [1100, 2100, 4100] }).toBe(true)
  return result
}
