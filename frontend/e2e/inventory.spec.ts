import { expect, test } from '@playwright/test'
import type { Browser, BrowserContext } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { basename, dirname, resolve } from 'node:path'
import type { components } from '../src/api/schema'

type Product = components['schemas']['ProductResponse']
type Lot = components['schemas']['LotResponse']
type Store = components['schemas']['StoreResponse']
type InventoryPosition = components['schemas']['StockSnapshotResponse']
type InventoryCommand = components['schemas']['InventoryCommandResponse']
type AllocationPage = components['schemas']['AllocationPageResponse']
type MovementPage = components['schemas']['MovementPageResponse']
type MemberPage = components['schemas']['MemberPage']
type BrowserAccount = { context: BrowserContext, email: string, id: string }

test('store operator books, reserves, reviews and reconciles authoritative inventory across recovery', async ({ browser }) => {
  test.setTimeout(180000)
  const owner = await account(browser)
  const organization = await write<{ id: string }>(owner.context, '/api/v1/organizations', { requestId: randomUUID(), name: `Inventory cooperative ${randomUUID().slice(0, 8)}` }, 201)
  const organizationPath = `/api/v1/organizations/${organization.id}`
  const store = await write<Store>(owner.context, `${organizationPath}/stores`, { requestId: randomUUID(), code: 'NORTH', name: 'Inventory receiving store', zoneId: 'Europe/Warsaw' }, 201)
  const zone = await write<components['schemas']['ZoneResponse']>(owner.context, `${organizationPath}/stores/${store.id}/storage-zones`, { name: 'Ambient stockroom', temperatureClass: 'AMBIENT' }, 201)
  const suffix = randomUUID().slice(0, 8).toUpperCase()
  const product = await write<Product>(owner.context, `${organizationPath}/products`, { requestId: randomUUID(), sku: `INV-${suffix}`, name: `Inventory test product ${suffix}`, baseUnit: 'EA', category: 'BAKERY', temperatureClass: 'AMBIENT' }, 201)
  await write(owner.context, `${organizationPath}/products/${product.id}/classification-evidence/operator-confirmations`, {
    requestId: randomUUID(), expectedProductVersion: product.version, category: 'BAKERY', temperatureClass: 'AMBIENT',
    productProfile: 'PACKAGED_SHELF_STABLE_DRY_BAKERY', sourceType: 'ORIGINAL_PACKAGE', evidenceReference: 'package:ambient-bread-label',
    reason: 'Reviewed the declared ambient storage instructions on the original package',
  }, 201)
  const date = new Date(Date.now() + 45 * 86400000).toISOString().slice(0, 10)
  const lot = await write<Lot>(owner.context, `${organizationPath}/lots`, {
    requestId: randomUUID(), productId: product.id, storeId: store.id, lotCode: `LOT-${suffix}`, identityState: 'CONFIRMED',
    manufacturerVerified: true, manufacturerIdentityKey: `maker:${suffix}`, manufacturerProvenance: { source: 'Manufacturer document', reference: `maker-record:${suffix}` },
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
  const page = await owner.context.newPage()
  await page.setViewportSize({ width: 390, height: 844 })
  try {
    await page.goto(`/organizations/${organization.id}/stores/${store.id}/inventory`)
    await expect(page.getByRole('heading', { name: 'Inventory', exact: true })).toBeVisible()
    const receipt = page.getByRole('region', { name: 'Physical goods receipt' })
    await chooseByKeyboard(page, receipt.getByLabel('Product'), product.id)
    await chooseByKeyboard(page, receipt.getByLabel('Lot'), lot.id)
    await chooseByKeyboard(page, receipt.getByLabel('Storage location'), location.id)
    await expect(receipt.getByText(/No position exists for this product/)).toBeVisible()
    await keyboardFill(page, receipt.getByLabel('Received quantity (EA)'), '10')
    await keyboardFill(page, receipt.getByLabel('Supplier identity key'), `supplier:${suffix}`)
    await keyboardFill(page, receipt.getByLabel('Source document reference'), `DELIVERY-${suffix}`)
    await keyboardFill(page, receipt.getByLabel('Document line reference'), '1')
    await receipt.getByLabel('Physically received at').fill(localDateTime(new Date(Date.now() - 60000)))
    await keyboardFill(page, receipt.getByLabel('Reason for receipt'), 'Physically checked ten sealed units at receiving')
    await keyboardFill(page, receipt.getByLabel('Evidence source link or reference'), `https://supplier.example.test/delivery/${suffix}`)
    const receiptConfirmation = receipt.getByLabel(/I confirmed these units are already physically present/)
    await receiptConfirmation.focus()
    await page.keyboard.press('Space')
    const confirmReceipt = receipt.getByRole('button', { name: 'Confirm physical receipt' })
    await confirmReceipt.focus()
    await page.keyboard.press('Enter')
    await expect(receipt.getByRole('status')).toContainText('Receipt confirmed in the inventory ledger')
    const positionRow = page.getByRole('row').filter({ hasText: lot.lotCode })
    await expect(positionRow).toContainText('10 EA')
    const reviewPosition = positionRow.getByRole('button', { name: 'Review position' })
    await reviewPosition.focus()
    await page.keyboard.press('Enter')
    const operations = page.getByRole('region', { name: 'Inventory operations' })
    await expect(operations.getByText(/version 1/)).toBeVisible()
    await submitOperation(page, 'reserve', '6', 'Reserve six picked units for an order')
    await expect(positionRow).toContainText('6 EA')

    await submitOperation(page, 'reserve', '5', 'Request more than the remaining available quantity')
    const rejected = operations.getByRole('alert')
    await expect(rejected).toContainText(/quantity|available stock/i)
    const reviewRejectedCommand = operations.getByRole('button', { name: 'Review rejected command' })
    await reviewRejectedCommand.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('region', { name: 'Selected inventory position' })).toBeVisible()

    let position = await read<InventoryPosition>(owner.context, `${positionPath}/${(await read<{ items: InventoryPosition[] }>(owner.context, positionPath)).items[0].id}`)
    const allocations = await read<AllocationPage>(owner.context, `${positionPath}/${position.id}/allocations?offset=0&limit=100`)
    const reservation = allocations.items.find(item => item.bucket === 'RESERVED' && item.state === 'ACTIVE')!
    let result = await command(owner.context, `${positionPath}/${position.id}/reservations/${reservation.id}/release`, position, 2, 'Released two units from a verified reservation')
    expect(result.reserved).toBe(4)
    position = await snapshot(owner.context, positionPath, position.id)
    result = await command(owner.context, `${positionPath}/${position.id}/quarantines`, position, 3, 'Quarantined three units pending internal review')
    expect(result.quarantined).toBe(3)
    position = await snapshot(owner.context, positionPath, position.id)
    result = await command(owner.context, `${positionPath}/${position.id}/holds`, position, 1, 'Held one unit for a store investigation')
    const holdId = result.allocationId!
    position = await snapshot(owner.context, positionPath, position.id)
    result = await command(owner.context, `${positionPath}/${position.id}/holds/${holdId}/release`, position, 1, 'Released the investigated unit after review')
    position = await snapshot(owner.context, positionPath, position.id)
    result = await writeOff(owner.context, `${positionPath}/${position.id}/write-offs`, position, 1, 'Disposed of one damaged unit')
    position = await snapshot(owner.context, positionPath, position.id)
    result = await command(owner.context, `${positionPath}/${position.id}/adjustments`, position, 1, 'Corrected the physical count upward')
    position = await snapshot(owner.context, positionPath, position.id)
    result = await command(owner.context, `${positionPath}/${position.id}/adjustments`, position, -1, 'Corrected the physical count downward')
    position = await snapshot(owner.context, positionPath, position.id)
    result = await command(owner.context, `${positionPath}/${position.id}/sales`, position, 1, 'Recorded one verified walk-in sale')
    expect(result.available).toBe(1)
    position = await snapshot(owner.context, positionPath, position.id)

    const movements = await read<MovementPage>(owner.context, `${positionPath}/${position.id}/movements?afterVersion=0&limit=100`)
    expect(movements.items.map(item => item.operation)).toEqual(expect.arrayContaining(['RECEIVE', 'RESERVE', 'RELEASE', 'QUARANTINE', 'HOLD', 'RELEASE_HOLD', 'WRITE_OFF', 'ADJUST', 'SALE']))
    const stale = await write<components['schemas']['ApiProblem']>(owner.context, `${positionPath}/${position.id}/adjustments`, commandBody(0, 1, 'Stale position must be rejected'), 409)
    expect(stale.code).toBe('INVENTORY_CONFLICT')
    const duplicate = await write<components['schemas']['ApiProblem']>(owner.context, `${inventoryPath}/receipts`, {
      requestId: randomUUID(), expectedVersion: position.version, locationId: location.id, productId: product.id, lotId: lot.id, quantity: 1,
      supplierKey: `supplier:${suffix}`, documentType: 'DELIVERY_NOTE', documentReference: `DELIVERY-${suffix}`, documentLineReference: '1',
      physicallyReceivedAt: new Date(Date.now() - 60000).toISOString(), reason: 'Duplicate document probe', evidenceReference: `delivery:${suffix}`,
    }, 409)
    expect(duplicate.code).toBe('RECEIPT_ALREADY_RECORDED')

    const recalledLot = await write<Lot>(owner.context, `${organizationPath}/lots`, {
      requestId: randomUUID(), productId: product.id, storeId: store.id, lotCode: `RECALL-${suffix}`, identityState: 'CONFIRMED',
      manufacturerVerified: true, manufacturerIdentityKey: `maker:${suffix}`, manufacturerProvenance: { source: 'Manufacturer document', reference: `maker-record:${suffix}` },
      dateMarking: { labeledValue: date, type: 'USE_BY', precision: 'DAY', provenance: { source: 'Original package label', reference: `label:recall-${suffix}` } },
    }, 201)
    await write(owner.context, `${organizationPath}/lots/${recalledLot.id}/safety-status-evidence`, {
      requestId: randomUUID(), expectedVersion: recalledLot.version, recallState: 'RECALLED', damageState: 'NOT_REPORTED',
      provenance: { source: 'Manufacturer recall notice', reference: `recall:${suffix}` }, reason: 'Recorded the manufacturer recall notice',
    }, 201)
    const recalledReceipt = await write<InventoryCommand>(owner.context, `${inventoryPath}/receipts`, {
      requestId: randomUUID(), expectedVersion: 0, locationId: location.id, productId: product.id, lotId: recalledLot.id, quantity: 5,
      supplierKey: `supplier:${suffix}`, documentType: 'DELIVERY_NOTE', documentReference: `RECALL-DELIVERY-${suffix}`, documentLineReference: '1',
      physicallyReceivedAt: new Date(Date.now() - 60000).toISOString(), reason: 'Received recalled units into quarantine for traceability', evidenceReference: `recall-delivery:${suffix}`,
    }, 200)
    expect(recalledReceipt.quarantined).toBe(5)
    const saleBlocked = await write<components['schemas']['ApiProblem']>(owner.context, `${positionPath}/${recalledReceipt.positionId}/sales`, commandBody(1, 1, 'Recall must block sale'), 422)
    expect(saleBlocked.code).toBe('LOT_NOT_ELIGIBLE')

    await page.reload()
    const reconciliation = page.getByRole('region', { name: 'Inventory reconciliation' })
    let reconciliationPosts = 0
    await page.route(`**${inventoryPath}/reconciliations`, async route => {
      if (route.request().method() !== 'POST') { await route.continue(); return }
      reconciliationPosts += 1
      const response = await route.fetch()
      expect(response.status()).toBe(201)
      await route.abort('connectionreset')
    })
    await reconciliation.getByRole('button', { name: 'Run inventory reconciliation' }).click()
    await expect(reconciliation.getByRole('alert')).toContainText(/reload the reconciliation history/i)
    await expect(reconciliation.getByRole('button', { name: 'Run inventory reconciliation' })).toBeDisabled()
    await reconciliation.getByRole('button', { name: 'Reload reconciliation history' }).click()
    await expect(reconciliation).toContainText('Latest run: CLEAN')
    await expect(reconciliation.getByRole('button', { name: 'Run inventory reconciliation' })).toBeEnabled()
    await page.unroute(`**${inventoryPath}/reconciliations`)
    expect(reconciliationPosts).toBe(1)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.getByRole('link', { name: 'Skip to content', exact: true }).focus()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('main')).toBeFocused()

    await restartIsolated(owner.context)
    await page.reload()
    const persisted = await snapshot(owner.context, positionPath, position.id)
    expect(persisted.onHand).toBe(8)
    expect(persisted.reserved).toBe(4)
    expect(persisted.quarantined).toBe(3)
    expect(persisted.available).toBe(1)

    const lostResponseRequests: Record<string, unknown>[] = []
    let firstAcknowledgementDropped = false
    const adjustmentPath = `${positionPath}/${position.id}/adjustments`
    await page.route(`**${adjustmentPath}`, async route => {
      if (route.request().method() !== 'POST') { await route.continue(); return }
      lostResponseRequests.push(route.request().postDataJSON() as Record<string, unknown>)
      if (!firstAcknowledgementDropped) {
        firstAcknowledgementDropped = true
        const response = await route.fetch()
        expect(response.status()).toBe(200)
        await route.abort('connectionreset')
      } else await route.continue()
    })
    const mainRow = page.getByRole('row').filter({ hasText: lot.lotCode })
    await mainRow.getByRole('button', { name: 'Review position' }).click()
    const retryOperations = page.getByRole('region', { name: 'Inventory operations' })
    await retryOperations.getByLabel('Operation').selectOption('adjust')
    await retryOperations.getByLabel('Adjustment amount (EA)').fill('1')
    await retryOperations.getByLabel('Reason', { exact: true }).fill('Recorded a final count correction after recovery')
    await retryOperations.getByLabel('Evidence link or source reference').fill(`count-sheet:${suffix}`)
    await retryOperations.getByLabel(/I reviewed this position/).check()
    await retryOperations.getByRole('button', { name: 'Adjust physical count' }).click()
    await expect(retryOperations.getByRole('button', { name: 'Retry same operation' })).toBeVisible()
    await expect(retryOperations.getByRole('alert')).toContainText(/may have been committed/i)
    await retryOperations.getByRole('button', { name: 'Retry same operation' }).click()
    await expect(page.getByRole('region', { name: 'Selected inventory position' })).toContainText('9 EA')
    await page.unroute(`**${adjustmentPath}`)
    expect(lostResponseRequests).toHaveLength(2)
    expect(lostResponseRequests[0]).toEqual(lostResponseRequests[1])
    const afterRetry = await snapshot(owner.context, positionPath, position.id)
    expect(afterRetry.version).toBe(persisted.version + 1)

    const analyst = await account(browser)
    const invitation = await write<{ token: string }>(owner.context, `${organizationPath}/invitations`, {
      email: analyst.email, reason: 'Read-only stock review', storeGrants: [{ storeId: store.id, role: 'READ_ONLY_ANALYST' }],
    }, 201)
    await write(analyst.context, '/api/v1/invitations/accept', { token: invitation.token }, 202)
    const members = await read<MemberPage>(owner.context, `${organizationPath}/members`)
    const invited = members.items.find(member => member.userId === analyst.id)!
    await write(owner.context, `${organizationPath}/members/${invited.id}/approve`, { version: invited.version, verifiedUserId: analyst.id, reason: 'Verified the analyst account' }, 200)
    const denied = await write<components['schemas']['ApiProblem']>(analyst.context, `${positionPath}/${position.id}/reservations`, commandBody(afterRetry.version, 1, 'Read-only role must not reserve stock'), 403)
    expect(denied.code).toBe('ACCESS_DENIED')
    const analystPage = await analyst.context.newPage()
    try {
      await analystPage.goto(`/organizations/${organization.id}/stores/${store.id}/inventory`)
      const analystPosition = analystPage.getByRole('row').filter({ hasText: lot.lotCode })
      await analystPosition.getByRole('button', { name: 'Review position' }).click()
      await expect(analystPage.getByRole('region', { name: 'Selected inventory position' })).toContainText('Your current role has no stock-changing actions for this position.')
      await expect(analystPage.getByRole('region', { name: 'Inventory operations' })).toHaveCount(0)
    } finally { await analystPage.close(); await analyst.context.close() }
  } finally { await page.close(); await owner.context.close() }
})

async function submitOperation(page: import('@playwright/test').Page, operation: string, quantity: string, reason: string) {
  const form = page.getByRole('region', { name: 'Inventory operations' })
  await expect(form.getByLabel('Operation')).toHaveValue(operation)
  await keyboardFill(page, form.getByLabel('Quantity (EA)'), quantity)
  await keyboardFill(page, form.getByLabel('Reason', { exact: true }), reason)
  await keyboardFill(page, form.getByLabel('Evidence link or source reference'), `inventory:test:${randomUUID()}`)
  const reviewed = form.getByLabel(/I reviewed this position/)
  await reviewed.focus()
  await page.keyboard.press('Space')
  const label = operation === 'reserve' ? 'Reserve stock' : 'Save inventory operation'
  const submit = form.getByRole('button', { name: label })
  await submit.focus()
  await page.keyboard.press('Enter')
}

async function keyboardFill(page: import('@playwright/test').Page, field: import('@playwright/test').Locator, value: string) {
  await field.focus()
  await page.keyboard.press('Control+A')
  await page.keyboard.type(value)
}

async function chooseByKeyboard(page: import('@playwright/test').Page, field: import('@playwright/test').Locator, value: string) {
  await expect(field).toBeEnabled()
  await expect(field.locator(`option[value="${value}"]`)).toHaveCount(1)
  await field.focus()
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')
  await expect(field).toHaveValue(value)
}

async function command(context: BrowserContext, path: string, position: InventoryPosition, quantity: number, reason: string) {
  const body = commandBody(position.version, quantity, reason)
  return write<InventoryCommand>(context, path, body, 200)
}

async function writeOff(context: BrowserContext, path: string, position: InventoryPosition, quantity: number, reason: string) {
  return write<InventoryCommand>(context, path, { stock: commandBody(position.version, quantity, reason) }, 200)
}

function commandBody(expectedVersion: number, quantity: number, reason: string) {
  return { requestId: randomUUID(), expectedVersion, quantity, reason, evidenceReference: `inventory:test:${randomUUID()}` }
}

async function snapshot(context: BrowserContext, base: string, positionId: string) {
  return read<InventoryPosition>(context, `${base}/${positionId}`)
}

async function account(browser: Browser): Promise<BrowserAccount> {
  const context = await browser.newContext()
  const email = `inventory-${randomUUID()}@example.test`
  const credentials = { email, password: 'real-inventory-owner-passphrase' }
  await write(context, '/api/v1/auth/sign-up', credentials, 202)
  const actor = await write<{ id: string }>(context, '/api/v1/auth/sign-in', credentials, 200)
  csrfTokens.delete(context)
  return { context, email, id: actor.id }
}

const csrfTokens = new WeakMap<BrowserContext, string>()
async function write<T = unknown>(context: BrowserContext, path: string, data: unknown, expected: number): Promise<T> {
  let csrf = csrfTokens.get(context)
  if (!csrf) {
    csrf = (await admitted<{ token: string }>(() => context.request.get('/api/v1/auth/csrf'), 200)).token
    csrfTokens.set(context, csrf)
  }
  return admitted(() => context.request.fetch(path, { method: 'POST', headers: { 'X-CSRF-TOKEN': csrf }, data }), expected)
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

function localDateTime(date: Date) {
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
}

async function restartIsolated(context: BrowserContext) {
  const root = resolve(process.cwd(), '..')
  const project = process.env.STOCKRESCUE_TEST_PROJECT ?? ''
  const envFile = process.env.STOCKRESCUE_TEST_ENV_FILE ?? ''
  expect(/^stockrescue-check-[a-f0-9]{16}$/.test(project)).toBe(true)
  expect(dirname(resolve(envFile))).toBe(resolve(root, '.tools'))
  expect(basename(envFile)).toBe(`${project}.env`)
  const docker = promisify(execFile)
  const args = ['compose', '--env-file', envFile, '-p', project]
  await docker('docker', [...args, 'restart', 'postgres'], { cwd: root, timeout: 90000 })
  await docker('docker', [...args, 'up', '-d', '--wait', '--wait-timeout', '90', 'postgres'], { cwd: root, timeout: 100000 })
  await docker('docker', [...args, 'up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '90', 'backend'], { cwd: root, timeout: 100000 })
  await expect.poll(async () => { try { return (await context.request.get('/api/health/readiness', { timeout: 5000 })).status() } catch { return 0 } }, { timeout: 30000, intervals: [500, 1000, 2000] }).toBe(200)
}
