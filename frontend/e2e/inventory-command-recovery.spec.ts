import { expect, test } from '@playwright/test'
import type { Browser, BrowserContext } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import type { components } from '../src/api/schema'

type Account = { context: BrowserContext, email: string, id: string }
type MemberPage = components['schemas']['MemberPage']
type InventoryCommand = components['schemas']['InventoryCommandResponse']
type InventoryPosition = components['schemas']['StockSnapshotResponse']

test('store operators can read and acknowledge a committed inventory result after their write access is downgraded', async ({ browser }) => {
  test.setTimeout(120000)
  const owner = await account(browser)
  const operator = await account(browser)
  try {
    const organization = await write<{ id: string }>(owner.context, '/api/v1/organizations', {
      requestId: randomUUID(), name: `Recovery cooperative ${randomUUID().slice(0, 8)}`,
    }, 201)
    const organizationPath = `/api/v1/organizations/${organization.id}`
    const store = await write<components['schemas']['StoreResponse']>(owner.context, `${organizationPath}/stores`, {
      requestId: randomUUID(), code: 'NORTH', name: 'Recovery test store', zoneId: 'Europe/Warsaw',
    }, 201)
    const zone = await write<components['schemas']['ZoneResponse']>(owner.context, `${organizationPath}/stores/${store.id}/storage-zones`, {
      name: 'Ambient stockroom', temperatureClass: 'AMBIENT',
    }, 201)
    const suffix = randomUUID().slice(0, 8).toUpperCase()
    const product = await write<components['schemas']['ProductResponse']>(owner.context, `${organizationPath}/products`, {
      requestId: randomUUID(), sku: `REC-${suffix}`, name: `Recovery product ${suffix}`, baseUnit: 'EA', category: 'BAKERY', temperatureClass: 'AMBIENT',
    }, 201)
    await write(owner.context, `${organizationPath}/products/${product.id}/classification-evidence/operator-confirmations`, {
      requestId: randomUUID(), expectedProductVersion: product.version, category: 'BAKERY', temperatureClass: 'AMBIENT',
      productProfile: 'PACKAGED_SHELF_STABLE_DRY_BAKERY', sourceType: 'ORIGINAL_PACKAGE', evidenceReference: `package:${suffix}`,
      reason: 'Reviewed the declared ambient storage instructions on the original package',
    }, 201)
    const date = new Date(Date.now() + 45 * 86400000).toISOString().slice(0, 10)
    const lot = await write<components['schemas']['LotResponse']>(owner.context, `${organizationPath}/lots`, {
      requestId: randomUUID(), productId: product.id, storeId: store.id, lotCode: `LOT-${suffix}`, identityState: 'CONFIRMED',
      manufacturerVerified: true, manufacturerIdentityKey: `maker:${suffix}`, manufacturerProvenance: { source: 'Manufacturer document', reference: `maker:${suffix}` },
      dateMarking: { labeledValue: date, type: 'USE_BY', precision: 'DAY', provenance: { source: 'Original package label', reference: `label:${suffix}` } },
    }, 201)
    await write(owner.context, `${organizationPath}/lots/${lot.id}/safety-status-evidence`, {
      requestId: randomUUID(), expectedVersion: lot.version, recallState: 'NOT_REPORTED', damageState: 'NOT_REPORTED',
      provenance: { source: 'Reviewed supplier safety statement', reference: `safety:${suffix}` }, reason: 'Reviewed the supplier statement for this lot',
    }, 201)
    const location = await write<components['schemas']['StorageLocationResponse']>(owner.context, `${organizationPath}/stores/${store.id}/storage-locations`, {
      requestId: randomUUID(), code: 'A-01', name: 'Ambient receiving shelf', zoneId: zone.id,
    }, 201)
    const inventoryPath = `${organizationPath}/stores/${store.id}/inventory`
    const positionPath = `${inventoryPath}/positions`
    const receipt = await write<InventoryCommand>(owner.context, `${inventoryPath}/receipts`, {
      requestId: randomUUID(), expectedVersion: 0, locationId: location.id, productId: product.id, lotId: lot.id, quantity: 10,
      supplierKey: `supplier:${suffix}`, documentType: 'DELIVERY_NOTE', documentReference: `DELIVERY-${suffix}`, documentLineReference: '1',
      physicallyReceivedAt: new Date(Date.now() - 60000).toISOString(), reason: 'Recorded a verified physical receipt', evidenceReference: `delivery:${suffix}`,
    }, 200)

    const invitation = await write<{ token: string }>(owner.context, `${organizationPath}/invitations`, {
      email: operator.email, reason: 'Inventory command recovery verification', storeGrants: [{ storeId: store.id, role: 'READ_ONLY_ANALYST' }],
    }, 201)
    await write(operator.context, '/api/v1/invitations/accept', { token: invitation.token }, 202)
    const member = (await read<MemberPage>(owner.context, `${organizationPath}/members`)).items.find(item => item.userId === operator.id)!
    await write(owner.context, `${organizationPath}/members/${member.id}/approve`, {
      version: member.version, verifiedUserId: operator.id, reason: 'Verified the operator account',
    }, 200)

    await setStoreRole(owner.context, organizationPath, operator.id, store.id, 'INVENTORY_OPERATOR')
    const page = await operator.context.newPage()
    try {
      await page.setViewportSize({ width: 390, height: 844 })
      await page.goto(`/organizations/${organization.id}/stores/${store.id}/inventory`)
      await expect(page.getByRole('heading', { name: 'Inventory', exact: true })).toBeVisible()
      const row = page.getByRole('row').filter({ hasText: lot.lotCode }).filter({ hasText: location.code })
      await row.getByRole('button', { name: 'Review position' }).click()
      const form = page.getByRole('region', { name: 'Inventory operations' })
      await form.getByLabel('Operation').selectOption('reserve')
      await form.getByLabel('Quantity (EA)').fill('1')
      await form.getByLabel('Reason', { exact: true }).fill('Reserve one unit before a permission downgrade')
      await form.getByLabel('Evidence link or source reference').fill(`recovery:${suffix}`)
      await form.getByLabel(/I reviewed this position/).check()

      let committedIntentId = ''
      page.on('request', request => {
        if (request.method() !== 'POST' || new URL(request.url()).pathname !== `${inventoryPath}/command-intents`) return
        committedIntentId = (request.postDataJSON() as components['schemas']['PrepareRequest']).prepareId
      })
      let executePosts = 0
      let savedMovementId = ''
      await page.route(`**${inventoryPath}/command-intents/*/execute`, async route => {
        if (route.request().method() !== 'POST') { await route.continue(); return }
        executePosts++
        const response = await route.fetch()
        expect(response.status()).toBe(200)
        const execution = await response.json() as components['schemas']['ExecutionResponse']
        savedMovementId = (execution.outcome as InventoryCommand).movementId
        await route.abort('connectionreset')
      })
      await form.getByRole('button', { name: 'Reserve stock' }).click()
      const recovery = page.getByRole('region', { name: 'Recover pending operation' })
      await expect(recovery).toBeVisible()
      expect(executePosts).toBe(1)
      expect(savedMovementId).toBeTruthy()
      expect(committedIntentId).toBeTruthy()
      await page.unroute(`**${inventoryPath}/command-intents/*/execute`)

      await setStoreRole(owner.context, organizationPath, operator.id, store.id, 'READ_ONLY_ANALYST')
      await page.reload()
      const downgradedRecovery = page.getByRole('region', { name: 'Recover pending operation' })
      const statusResponse = await operator.context.request.get(`${inventoryPath}/command-intents/${committedIntentId}/status`)
      expect(statusResponse.status()).toBe(200)
      expect(statusResponse.headers()['cache-control']).toContain('no-store')
      const status = await statusResponse.json() as components['schemas']['IntentStatusResponse']
      expect(status.executionStatus).toBe('COMMITTED_AWAITING_ACKNOWLEDGEMENT')
      expect(status.committedOutcome?.movementId).toBe(savedMovementId)
      expect(JSON.stringify(status)).not.toContain('Reserve one unit before a permission downgrade')
      expect(JSON.stringify(status)).not.toContain(`recovery:${suffix}`)
      await downgradedRecovery.getByRole('button', { name: 'Inspect saved request' }).click()
      await expect(downgradedRecovery.getByRole('status')).toContainText('committed successfully')
      await expect(downgradedRecovery.getByRole('status')).toContainText(savedMovementId)
      await expect(downgradedRecovery.getByRole('button', { name: 'Execute reviewed command' })).toHaveCount(0)
      await downgradedRecovery.getByRole('button', { name: 'Acknowledge saved result' }).click()
      await expect(downgradedRecovery).toHaveCount(0)
      expect(executePosts).toBe(1)
      expect((await read<InventoryPosition>(operator.context, `${positionPath}/${receipt.positionId}`)).version).toBe(2)

      await setStoreRole(owner.context, organizationPath, operator.id, store.id, 'INVENTORY_OPERATOR')
      await page.reload()
      const beforePreparedStatus = await read<InventoryPosition>(operator.context, `${positionPath}/${receipt.positionId}`)
      const preparedIntentId = randomUUID()
      const preparedReason = 'Prepared but not executed before permission downgrade'
      await write(operator.context, `${inventoryPath}/command-intents`, {
        prepareId: preparedIntentId,
        operation: 'RESERVE',
        payload: {
          requestId: randomUUID(), positionId: receipt.positionId, allocationId: null, expectedVersion: beforePreparedStatus.version,
          quantity: 1, reason: preparedReason, evidenceReference: `prepared:${suffix}`,
        },
      }, 200)
      await setStoreRole(owner.context, organizationPath, operator.id, store.id, 'READ_ONLY_ANALYST')
      await page.reload()
      const preparedRecovery = page.getByRole('region', { name: 'Recover pending operation' })
      await preparedRecovery.getByRole('button', { name: 'Inspect saved request' }).click()
      await expect(preparedRecovery.getByRole('status')).toContainText('Not executed. You no longer have permission to perform this operation.')
      await expect(preparedRecovery.getByRole('button', { name: 'Execute reviewed command' })).toHaveCount(0)
      await expect(preparedRecovery.getByRole('button', { name: 'Acknowledge saved result' })).toHaveCount(0)
      await expect(preparedRecovery.getByRole('button', { name: 'Review rejected request' })).toHaveCount(0)
      const preparedStatus = await read<components['schemas']['IntentStatusResponse']>(operator.context,
        `${inventoryPath}/command-intents/${preparedIntentId}/status`)
      expect(preparedStatus.executionStatus).toBe('PREPARED')
      expect(preparedStatus.committedOutcome).toBeNull()
      expect(JSON.stringify(preparedStatus)).not.toContain(preparedReason)
      expect((await read<InventoryPosition>(operator.context, `${positionPath}/${receipt.positionId}`)).version).toBe(beforePreparedStatus.version)
      expect(executePosts).toBe(1)
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    } finally {
      await page.close()
    }
  } finally {
    await operator.context.close()
    await owner.context.close()
  }
})

async function setStoreRole(context: BrowserContext, organizationPath: string, userId: string, storeId: string, role: 'INVENTORY_OPERATOR' | 'READ_ONLY_ANALYST') {
  const members = await read<MemberPage>(context, `${organizationPath}/members`)
  const member = members.items.find(item => item.userId === userId)!
  const grant = member.grants.find(item => item.storeId === storeId)!
  await write(context, `${organizationPath}/members/${member.id}/store-grants/${storeId}`, {
    memberVersion: member.version, grantVersion: grant.version, role, reason: `Reviewed inventory access change to ${role}`,
  }, 200, 'PUT')
}

async function account(browser: Browser): Promise<Account> {
  const context = await browser.newContext()
  const email = `recovery-${randomUUID()}@example.test`
  const credentials = { email, password: 'real-inventory-recovery-passphrase' }
  await write(context, '/api/v1/auth/sign-up', credentials, 202)
  const actor = await write<{ id: string }>(context, '/api/v1/auth/sign-in', credentials, 200)
  csrfTokens.delete(context)
  return { context, email, id: actor.id }
}

const csrfTokens = new WeakMap<BrowserContext, string>()
async function write<T = unknown>(context: BrowserContext, path: string, data: unknown, expected: number, method = 'POST'): Promise<T> {
  let csrf = csrfTokens.get(context)
  if (!csrf) {
    csrf = (await admitted<{ token: string }>(() => context.request.get('/api/v1/auth/csrf'), 200)).token
    csrfTokens.set(context, csrf)
  }
  return admitted(() => context.request.fetch(path, { method, headers: { 'X-CSRF-TOKEN': csrf }, data }), expected)
}

async function read<T>(context: BrowserContext, path: string): Promise<T> {
  return admitted(() => context.request.get(path), 200)
}

async function admitted<T>(operation: () => Promise<{ status(): number, json(): Promise<T> }>, expected: number): Promise<T> {
  let data: T | undefined
  await expect.poll(async () => {
    const response = await operation()
    if (response.status() === 429) return false
    expect(response.status()).toBe(expected)
    data = await response.json()
    return true
  }, { timeout: 12000, intervals: [700, 1400, 2800] }).toBe(true)
  return data!
}
