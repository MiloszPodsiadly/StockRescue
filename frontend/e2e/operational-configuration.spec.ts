import { expect, test } from '@playwright/test'
import type { BrowserContext, Page } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { components } from '../src/api/schema'
import { clickWhenAdmitted } from './support/admission'

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..')

test('scheduled lane costs can be reviewed, cancelled and reconciled across a Compose restart', async ({ browser }) => {
  test.setTimeout(180000)
  test.skip(!process.env.STOCKRESCUE_TEST_PROJECT || !process.env.STOCKRESCUE_TEST_ENV_FILE,
    'Run this browser scenario through the Compose integration check.')

  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
  try {
    const credentials = { email: `configuration-${randomUUID()}@example.test`, password: 'real-configuration-owner-passphrase' }
    await write(context, '/api/v1/auth/sign-up', credentials, 202)
    await write(context, '/api/v1/auth/sign-in', credentials, 200)
    const org = await write(context, '/api/v1/organizations', { requestId: randomUUID(), name: 'Configured cooperative' }, 201) as { id: string }
    const stores: components['schemas']['StoreResponse'][] = []
    for (const code of ['NORTH', 'SOUTH', 'EAST', 'WEST']) {
      stores.push(await write(context, `/api/v1/organizations/${org.id}/stores`, {
        requestId: randomUUID(), code, name: `${code} shop`, zoneId: 'Europe/Warsaw',
      }, 201) as components['schemas']['StoreResponse'])
    }

    const page = await context.newPage()
    for (const [index, store] of stores.entries()) {
      if (!index) await page.goto(`/organizations/${org.id}/stores/${store.id}/storage-zones`)
      else await openZones(page, store.name)
      await expect(page.getByRole('heading', { name: 'No storage zones' })).toBeVisible()
      await page.getByLabel('Zone name', { exact: true }).fill(`${store.code} ambient`)
      await page.getByLabel('Temperature class').selectOption('AMBIENT')
      await clickWhenAdmitted(page, page.getByRole('button', { name: 'Create zone', exact: true }), page.getByRole('heading', { name: `${store.code} ambient`, exact: true }))
      await expect(page.getByRole('status')).toHaveText('Storage zone configuration saved.')
      await expect(page.getByLabel('Zone name', { exact: true })).toHaveValue('')
    }

    const chilledZones: string[] = []
    for (const [index, store] of stores.slice(0, 2).entries()) {
      await openZones(page, store.name)
      const name = `${store.code} chilled`
      await page.getByLabel('Zone name', { exact: true }).fill(name)
      await page.getByLabel('Temperature class').selectOption('CHILLED')
      await clickWhenAdmitted(page, page.getByRole('button', { name: 'Create zone', exact: true }), page.getByRole('heading', { name, exact: true }))
      const zones = await context.request.get(`/api/v1/organizations/${org.id}/stores/${store.id}/storage-zones?offset=0&limit=100`)
      const pageOfZones = await zones.json() as components['schemas']['ZonePageResponse']
      chilledZones[index] = pageOfZones.items.find(zone => zone.name === name)!.id
    }

    await page.getByRole('navigation', { name: 'Workspace navigation', exact: true }).getByRole('link', { name: 'Transport lanes', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'No transport lanes' })).toBeVisible()
    await page.getByLabel('Origin store', { exact: true }).selectOption(stores[0]!.id)
    await page.getByLabel('Destination store', { exact: true }).selectOption(stores[1]!.id)
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
      await expect(page.getByText(`${amount === '12.34' ? '12.3400' : amount} PLN`, { exact: true }).first()).toBeVisible()
    }
    await expect(page.getByRole('heading', { name: 'Revision 1', exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Revision 2', exact: true })).toBeVisible()

    await page.getByLabel('Cost amount', { exact: true }).fill('99.99')
    await page.getByLabel('Currency', { exact: true }).selectOption('EUR')
    await page.getByLabel('Journey duration (minutes)').fill('60')
    await page.getByLabel('Schedule a future revision in UTC').check()
    const scheduledAt = new Date(Date.now() + 86400000).toISOString().slice(0, 19)
    await page.getByLabel('Effective time (UTC)', { exact: true }).fill(scheduledAt)
    await page.getByLabel('Reason for cost revision').fill('Tomorrow approved carrier quote')
    await page.getByRole('button', { name: 'Approve cost revision', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Revision 3', exact: true })).toBeVisible()
    await expect(page.getByText('Scheduled future revision', { exact: true })).toBeVisible()
    await expect(page.getByText('12.3400 PLN', { exact: true }).first()).toBeVisible()

    const laneUrl = page.url()
    const lanePath = new URL(laneUrl).pathname
    const laneId = lanePath.split('/').at(-1)!
    const apiLanePath = `/api/v1/organizations/${org.id}/transport-lanes/${laneId}`
    const beforeUnsafeEdit = await (await context.request.get(apiLanePath)).json() as components['schemas']['LaneResponse']
    const rejected = await write(context, apiLanePath, {
      originZoneId: chilledZones[0], destinationZoneId: chilledZones[1], active: true,
      version: beforeUnsafeEdit.version, reason: 'Verify pending schedule blocks a zone edit',
    }, 409, 'PATCH') as components['schemas']['ApiProblem']
    expect(rejected.code).toBe('SCHEDULED_REVISIONS_PENDING')

    const historyPath = `${apiLanePath}/revisions`
    const scheduledHistory = await (await context.request.get(`${historyPath}?offset=0&limit=25`)).json() as components['schemas']['RevisionPageResponse']
    const scheduledRevision = scheduledHistory.items.find(revision => revision.revision === 3)!
    expect(scheduledRevision.cancellation).toBeNull()
    expect((await context.request.get(apiLanePath)).status()).toBe(200)

    await page.reload()
    for (const revision of [1, 2, 3]) await expect(page.getByRole('heading', { name: `Revision ${revision}`, exact: true })).toBeVisible()
    await page.setViewportSize({ width: 390, height: 844 })
    await page.getByLabel('Origin zone', { exact: true }).selectOption({ label: 'NORTH chilled · CHILLED' })
    await page.getByLabel('Destination zone', { exact: true }).selectOption({ label: 'SOUTH chilled · CHILLED' })
    await page.getByLabel('Reason for lane configuration').fill('Use compatible chilled storage areas')
    await page.getByRole('button', { name: 'Save lane configuration' }).click()

    await expect(page.getByRole('heading', { name: 'Review future costs before changing lane zones' })).toBeVisible()
    await expect(page.getByText(`Revision 3 · ${scheduledRevision.id}`, { exact: true })).toBeVisible()
    await expect(page.getByRole('list', { name: 'Scheduled cost revisions to cancel' }).getByText(`Effective time (UTC): ${scheduledRevision.effectiveAt}`, { exact: true })).toBeVisible()
    const laneDuringReview = await (await context.request.get(apiLanePath)).json() as components['schemas']['LaneResponse']
    expect(laneDuringReview.originZoneId).toBe(beforeUnsafeEdit.originZoneId)
    expect(laneDuringReview.destinationZoneId).toBe(beforeUnsafeEdit.destinationZoneId)
    const stillScheduled = await (await context.request.get(historyPath)).json() as components['schemas']['RevisionPageResponse']
    expect(stillScheduled.items.find(revision => revision.id === scheduledRevision.id)?.cancellation).toBeNull()

    await page.getByLabel('Reason for cancelling scheduled costs').fill('Carrier agreement changed before the planned start')
    await page.getByLabel('I reviewed the listed revisions and confirm cancelling all of them.').check()
    await page.getByRole('button', { name: 'Cancel all 1 scheduled revision' }).click()
    await expect(page.locator('[role="status"]').filter({ hasText: 'Cancelled 1 scheduled revision' })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Review future costs before changing lane zones' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Save lane configuration' })).toBeEnabled()
    const afterCancellation = await (await context.request.get(historyPath)).json() as components['schemas']['RevisionPageResponse']
    const cancellation = afterCancellation.items.find(revision => revision.id === scheduledRevision.id)?.cancellation
    expect(cancellation).not.toBeNull()
    expect(cancellation?.reason).toBe('Carrier agreement changed before the planned start')

    await page.getByRole('button', { name: 'Save lane configuration' }).click()
    await expect(page.getByText('UNCONFIGURED', { exact: true })).toBeVisible()
    await expect(page.getByText('The current zones differ from the cost evidence. Approve a new cost revision.', { exact: true })).toBeVisible()
    await expect(page.getByText('Historical zone evidence differs from the lane\'s current zone pair.', { exact: true }).first()).toBeVisible()
    await expect(page.getByText('Current cost and duration: unavailable.', { exact: true })).toBeVisible()
    await expect(page.getByLabel('Origin zone', { exact: true })).toHaveValue(chilledZones[0]!)
    await expect(page.getByLabel('Destination zone', { exact: true })).toHaveValue(chilledZones[1]!)

    await page.getByLabel('Cost amount', { exact: true }).fill('16.75')
    await page.getByLabel('Currency', { exact: true }).selectOption('PLN')
    await page.getByLabel('Journey duration (minutes)').fill('50')
    await page.getByLabel('Reason for cost revision').fill('Approved chilled route quote')
    await page.getByRole('button', { name: 'Approve cost revision', exact: true }).click()
    await expect(page.getByText('CONFIGURED', { exact: true })).toBeVisible()
    await expect(page.getByText('Current compatible effective cost: 16.7500 PLN · 50 minutes · Revision 4', { exact: true })).toBeVisible()
    await expect(page.getByText('Cancelled scheduled revision', { exact: true })).toBeVisible()
    await expect(page.getByText('Carrier agreement changed before the planned start', { exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)

    restartBackend()
    await expect.poll(async () => (await context.request.get('/api/v1/auth/me')).status(), { timeout: 60000, intervals: [1000, 2000, 4000] }).toBe(200)
    await page.reload()
    await expect(page.getByText('CONFIGURED', { exact: true })).toBeVisible()
    await expect(page.getByText('Current compatible effective cost: 16.7500 PLN · 50 minutes · Revision 4', { exact: true })).toBeVisible()
    await expect(page.getByText('Cancelled scheduled revision', { exact: true })).toBeVisible()
    await expect(page.getByText('Carrier agreement changed before the planned start', { exact: true })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Revision 3', exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)

    await page.setViewportSize({ width: 1280, height: 900 })
    await expect(page.getByRole('heading', { name: 'Review future costs before changing lane zones' })).toHaveCount(0)
    await expect(page.getByText('Current compatible effective cost: 16.7500 PLN · 50 minutes · Revision 4', { exact: true })).toBeVisible()

    const outsider = await browser.newContext({ viewport: { width: 390, height: 844 } })
    try {
      const otherCredentials = { email: `read-only-${randomUUID()}@example.test`, password: 'real-read-only-account-passphrase' }
      await write(outsider, '/api/v1/auth/sign-up', otherCredentials, 202)
      await write(outsider, '/api/v1/auth/sign-in', otherCredentials, 200)
      const latestLane = await (await context.request.get(apiLanePath)).json() as components['schemas']['LaneResponse']
      const denied = await write(outsider, `${apiLanePath}/scheduled-revisions/cancel`, {
        laneVersion: latestLane.version, expectedRevision: latestLane.latestRevision,
        reason: 'This account has no lane management authority',
      }, 404) as components['schemas']['ApiProblem']
      expect(denied.code).toBe('RESOURCE_NOT_FOUND')
      const outsiderPage = await outsider.newPage()
      await outsiderPage.goto(laneUrl)
      await expect(outsiderPage.getByRole('heading', { name: 'Organization unavailable' })).toBeVisible()
      await expect(outsiderPage.getByRole('button', { name: /Cancel all .* scheduled revision/ })).toHaveCount(0)
      expect(await outsiderPage.evaluate(() => Object.keys(localStorage).length + Object.keys(sessionStorage).length)).toBe(0)
    } finally { await outsider.close() }

    expect(await page.evaluate(() => Object.keys(localStorage).length + Object.keys(sessionStorage).length)).toBe(0)
  } finally { await context.close() }
})

async function openZones(page: Page, storeName: string) {
  await page.getByRole('navigation', { name: 'Workspace navigation', exact: true }).getByRole('link', { name: 'Stores', exact: true }).click()
  await page.getByRole('link', { name: storeName, exact: true }).click()
  await page.getByRole('link', { name: 'Storage zones', exact: true }).click()
}

function restartBackend() {
  execFileSync('docker', ['compose', '--project-name', process.env.STOCKRESCUE_TEST_PROJECT!, '--env-file', process.env.STOCKRESCUE_TEST_ENV_FILE!, 'restart', 'backend'], {
    cwd: repository, stdio: 'ignore', timeout: 90000,
  })
}

async function write(context: BrowserContext, path: string, data: unknown, expected: number, method = 'POST'): Promise<unknown> {
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
    const response = await context.request.fetch(path, { method, headers: { 'X-CSRF-TOKEN': token }, data })
    if (response.status() === 429) return false
    expect(response.status()).toBe(expected)
    result = await response.json()
    return true
  }, { timeout: 12000, intervals: [1100, 2100, 4100] }).toBe(true)
  return result
}
