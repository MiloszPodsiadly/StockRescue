import { expect, test } from '@playwright/test'
import type { Browser, BrowserContext } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { components } from '../src/api/schema'

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
type Product = components['schemas']['ProductResponse']
type Lot = components['schemas']['LotResponse']
type Store = components['schemas']['StoreResponse']
type Zone = components['schemas']['ZoneResponse']
type Location = components['schemas']['StorageLocationResponse']
type Transfer = components['schemas']['TransferResponse']
type StockPage = components['schemas']['StockPageResponse']
type MemberPage = components['schemas']['MemberPage']
type BrowserAccount = { context: BrowserContext, email: string, id: string }

test('store operators complete, recover and reconcile a physical stock transfer across Compose recovery', async ({ browser }) => {
  test.setTimeout(360000)
  test.skip(!process.env.STOCKRESCUE_TEST_PROJECT || !process.env.STOCKRESCUE_TEST_ENV_FILE,
    'Run this browser release proof through the Compose integration check.')

  const owner = await account(browser)
  let recipient: BrowserAccount | undefined
  const suffix = randomUUID().slice(0, 8).toUpperCase()
  try {
    const organization = await createOrganization(owner.context, `Transfer cooperative ${suffix}`)
    const organizationPath = `/api/v1/organizations/${organization}`
    const source = await write<Store>(owner.context, `${organizationPath}/stores`, {
      requestId: randomUUID(), code: `SRC_${suffix}`, name: 'Transfer source store', zoneId: 'Europe/Warsaw',
    }, 201)
    const destination = await write<Store>(owner.context, `${organizationPath}/stores`, {
      requestId: randomUUID(), code: `DST_${suffix}`, name: 'Transfer destination store', zoneId: 'Europe/Warsaw',
    }, 201)
    const sourceZone = await write<Zone>(owner.context, `${organizationPath}/stores/${source.id}/storage-zones`, {
      name: `Source ambient ${suffix}`, temperatureClass: 'AMBIENT',
    }, 201)
    const destinationZone = await write<Zone>(owner.context, `${organizationPath}/stores/${destination.id}/storage-zones`, {
      name: `Destination ambient ${suffix}`, temperatureClass: 'AMBIENT',
    }, 201)
    const sourceLocation = await write<Location>(owner.context, `${organizationPath}/stores/${source.id}/storage-locations`, {
      requestId: randomUUID(), code: `SRC_${suffix}`, name: 'Transfer source shelf', zoneId: sourceZone.id,
    }, 201)
    const destinationLocation = await write<Location>(owner.context, `${organizationPath}/stores/${destination.id}/storage-locations`, {
      requestId: randomUUID(), code: `DST_${suffix}`, name: 'Transfer receiving shelf', zoneId: destinationZone.id,
    }, 201)
    const product = await write<Product>(owner.context, `${organizationPath}/products`, {
      requestId: randomUUID(), sku: `XFER-${suffix}`, name: `Transfer test goods ${suffix}`,
      baseUnit: 'EA', category: 'BAKERY', temperatureClass: 'AMBIENT',
    }, 201)
    await write(owner.context, `${organizationPath}/products/${product.id}/classification-evidence/operator-confirmations`, {
      requestId: randomUUID(), expectedProductVersion: product.version, category: 'BAKERY', temperatureClass: 'AMBIENT',
      productProfile: 'PACKAGED_SHELF_STABLE_DRY_BAKERY', sourceType: 'ORIGINAL_PACKAGE',
      evidenceReference: 'package:transfer-goods-label', reason: 'Reviewed original storage and product classification evidence',
    }, 201)
    const expiry = new Date(Date.now() + 60 * 86400000).toISOString().slice(0, 10)
    const lot = await write<Lot>(owner.context, `${organizationPath}/lots`, {
      requestId: randomUUID(), productId: product.id, storeId: source.id, lotCode: `LOT-${suffix}`,
      identityState: 'CONFIRMED', manufacturerVerified: true, manufacturerIdentityKey: `manufacturer:${suffix}`,
      manufacturerProvenance: { source: 'Manufacturer transfer record', reference: `maker:${suffix}` },
      dateMarking: { labeledValue: expiry, type: 'USE_BY', precision: 'DAY', provenance: { source: 'Original package label', reference: `expiry:${suffix}` } },
    }, 201)
    const safety = await write<{ lot: Lot }>(owner.context, `${organizationPath}/lots/${lot.id}/safety-status-evidence`, {
      requestId: randomUUID(), expectedVersion: lot.version, recallState: 'NOT_REPORTED', damageState: 'NOT_REPORTED',
      provenance: { source: 'Reviewed supplier status', reference: `safety:${suffix}` }, reason: 'Reviewed supplier status for the transfer lot',
    }, 201)
    const lotAfterSafety = safety.lot
    const receipt = await write<{ lines: { positionId: string, version: number }[] }>(owner.context,
      `${organizationPath}/stores/${source.id}/inventory/receipt-documents`, {
        requestId: randomUUID(), supplierKey: `supplier:${suffix}`, documentType: 'DELIVERY_NOTE',
        documentReference: `DELIVERY-${suffix}`, physicallyReceivedAt: new Date().toISOString(),
        reason: 'Counted sealed source inventory before transfer release', evidenceReference: `supplier-document:${suffix}`,
        lines: [{ documentLineReference: `line-${suffix}`, locationId: sourceLocation.id, productId: product.id,
          lotId: lot.id, quantity: 50, expectedVersion: 0 }],
      }, 201)
    const sourcePositionId = receipt.lines[0]!.positionId
    await write(owner.context, `${organizationPath}/stores/${destination.id}/inventory/receipt-documents`, {
      requestId: randomUUID(), supplierKey: `supplier:${suffix}`, documentType: 'DELIVERY_NOTE',
      documentReference: `DESTINATION-DELIVERY-${suffix}`, physicallyReceivedAt: new Date().toISOString(),
      reason: 'Counted the destination opening balance before transfer receipt', evidenceReference: `destination-opening:${suffix}`,
      lines: [{ documentLineReference: `destination-line-${suffix}`, locationId: destinationLocation.id,
        productId: product.id, lotId: lot.id, quantity: 10, expectedVersion: 0 }],
    }, 201)

    const lane = await write<{ id: string, version: number, latestRevision: number }>(owner.context,
      `${organizationPath}/transport-lanes`, {
        originStoreId: source.id, destinationStoreId: destination.id,
        originZoneId: sourceZone.id, destinationZoneId: destinationZone.id,
        reason: 'Configure the reviewed route for physical stock transfers',
      }, 201)
    await write(owner.context, `${organizationPath}/transport-lanes/${lane.id}/revisions`, {
      laneVersion: lane.version, expectedRevision: lane.latestRevision, costAmount: '1.00', currencyCode: 'PLN',
      durationMinutes: 45, reason: 'Approve the current carrier route and duration',
    }, 201)

    const page = await owner.context.newPage()
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(`/organizations/${organization}/transfers`)
    await expect(page.getByRole('heading', { name: 'Stock transfers', exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'New transfer request' }).click()
    await page.getByLabel('Source store').selectOption(source.id)
    await page.getByLabel('Destination store').selectOption(destination.id)
    const sourceChoice = page.getByLabel('Source product and lot')
    await expect(sourceChoice.locator(`option[value="${sourcePositionId}"]`)).toHaveCount(1)
    await sourceChoice.selectOption(sourcePositionId)
    await page.getByLabel('Destination receiving location').selectOption(destinationLocation.id)
    await page.getByLabel('Quantity (EA)').fill('20')
    await page.getByLabel('Reason for transfer').fill('Rebalance verified seasonal stock between stores')
    await page.getByLabel('Evidence reference').fill(`stock-plan:${suffix}`)
    await expect(page.getByText('Transport route: Configured and available')).toBeVisible()
    await page.getByRole('button', { name: 'Create transfer request' }).click()

    const transferHeading = page.getByRole('heading', { name: /^Transfer [0-9a-f-]+$/i })
    await expect(transferHeading).toBeVisible()
    const transferId = (await transferHeading.innerText()).replace('Transfer ', '')
    const transferPath = `${organizationPath}/transfers/${transferId}`
    let transfer = await read<Transfer>(owner.context, transferPath)
    expect(transfer.status).toBe('REQUESTED')
    expect(transfer.lines).toHaveLength(1)
    const line = transfer.lines[0]!

    const approval = page.getByRole('form', { name: 'Review request' })
    await approval.getByLabel('Reason').fill('Verified source balance, lot evidence and configured destination route')
    await approval.getByLabel('Evidence reference').fill(`approval-review:${suffix}`)
    await approval.getByRole('checkbox').check()
    await approval.getByRole('button', { name: 'Approve and reserve stock' }).click()
    await page.getByRole('form', { name: 'Begin physical picking' }).getByLabel('Reason').fill('Release the approved request to the source picking team')
    const startPicking = page.getByRole('form', { name: 'Begin physical picking' })
    await startPicking.getByLabel('Evidence reference').fill(`pick-release:${suffix}`)
    await startPicking.getByRole('checkbox').check()
    await startPicking.getByRole('button', { name: 'Start picking' }).click()

    const picking = page.getByRole('form', { name: 'Pick line 1' })
    await picking.getByLabel('Pick document reference').fill(`PICK-${suffix}`)
    await picking.getByLabel('Reason for pick').fill('Counted and scanned the complete transfer line at source')
    await picking.getByLabel('Evidence reference').fill(`pick-evidence:${suffix}`)
    await picking.getByRole('checkbox').check()
    await picking.getByRole('button', { name: 'Confirm full-line pick' }).click()
    await expect(page.getByRole('button', { name: 'Confirm goods left the source store' })).toBeVisible()

    const dispatch = page.getByRole('form', { name: 'Confirm physical dispatch' })
    await dispatch.getByLabel('Dispatch document reference').fill(`DISPATCH-${suffix}`)
    await dispatch.getByLabel('Reason for dispatch').fill('Carrier collected the verified goods from the source store')
    await dispatch.getByLabel('Evidence reference').fill(`dispatch-proof:${suffix}`)
    await dispatch.getByRole('checkbox').check()
    let droppedDispatchResponses = 0
    await page.route(`**${transferPath}/dispatch`, async route => {
      const committed = await route.fetch()
      expect(committed.status()).toBe(200)
      droppedDispatchResponses++
      await route.abort('connectionreset')
    })
    await dispatch.getByRole('button', { name: 'Confirm goods left the source store' }).click()
    await expect.poll(() => droppedDispatchResponses, { timeout: 20000, intervals: [100, 250, 500] }).toBe(1)
    await expect(page.getByRole('region', { name: 'Recover saved transfer command' })).toBeVisible()
    await page.getByRole('button', { name: 'Inspect saved outcome' }).click()
    await expect(page.getByText('Recovered In transit from the saved command record.')).toBeVisible()
    await page.unroute(`**${transferPath}/dispatch`)
    transfer = await read<Transfer>(owner.context, transferPath)
    expect(transfer.status).toBe('IN_TRANSIT')
    expect(transfer.lines[0]!.quantities.dispatched).toBe(20)
    expect(transfer.outstandingQuantity).toBe(20)

    recipient = await account(browser)
    const invitation = await write<{ token: string }>(owner.context, `${organizationPath}/invitations`, {
      email: recipient.email, storeGrants: [{ storeId: destination.id, role: 'READ_ONLY_ANALYST' }],
      reason: 'Add an organization member for destination authorization review',
    }, 201)
    await write(recipient.context, '/api/v1/invitations/accept', { token: invitation.token }, 202)
    const members = await read<MemberPage>(owner.context, `${organizationPath}/members?offset=0&limit=100`)
    const member = members.items.find(item => item.userId === recipient!.id)!
    await write(owner.context, `${organizationPath}/members/${member.id}/approve`, {
      verifiedUserId: recipient.id, version: member.version, reason: 'Verified the invited recipient account reference',
    }, 200)
    await write(recipient.context, '/api/v1/auth/active-organization', { organizationId: organization }, 200)
    const recipientPage = await recipient.context.newPage()
    await recipientPage.goto(`/organizations/${organization}/transfers/${transferId}`)
    await expect(recipientPage.getByRole('heading', { name: /^Transfer [0-9a-f-]+$/i })).toBeVisible()
    await expect(recipientPage.getByRole('form', { name: 'Receive line 1' })).toHaveCount(0)
    const unauthorizedReceive = await write<{ code: string }>(recipient.context, `${transferPath}/receive`, {
      requestId: randomUUID(), expectedVersion: transfer.version, documentReference: `UNAUTHORIZED-${suffix}`,
      physicallyReceivedAt: new Date().toISOString(),
      lines: [{ lineId: line.id, quantity: 1, expectedDestinationVersion: 0, documentLineReference: 'unassigned-line', observedSku: product.sku, observedLotCode: lot.lotCode }],
      reason: 'Attempted receiving without destination authority', evidenceReference: `unauthorized:${suffix}`,
    }, 403)
    expect(unauthorizedReceive.code).toBe('ACCESS_DENIED')

    const receiving = page.getByRole('form', { name: 'Receive line 1' })
    await receiving.getByLabel('Physical quantity received (EA)').fill('18')
    await receiving.getByLabel('Receiving document reference').fill(`RECEIVE-${suffix}`)
    await receiving.getByLabel('Document line reference').fill(`receive-line-${suffix}`)
    await receiving.getByLabel('Reason for receipt').fill('Counted 18 sound packages at the destination dock')
    await receiving.getByLabel('Evidence reference').fill(`receiving-proof:${suffix}`)
    await receiving.getByRole('checkbox').check()
    await receiving.getByRole('button', { name: 'Record 18 physical units received' }).click()
    await expect(page.getByText('18 EA', { exact: true })).toBeVisible()

    const loss = page.getByRole('form', { name: 'Resolve loss or damage' })
    await loss.getByLabel('Loss or damage classification').selectOption('LOST_WITH_EVIDENCE')
    await loss.getByLabel('Quantity affected').fill('2')
    await loss.getByLabel('Damage document reference').fill(`LOSS-${suffix}`)
    await loss.getByLabel('Document line reference').fill(`loss-line-${suffix}`)
    await loss.getByLabel('Reason and evidence description').fill('Carrier investigation confirmed two units lost in transit')
    await loss.getByLabel('Evidence reference').fill(`carrier-claim:${suffix}`)
    await loss.getByRole('checkbox').check()
    await loss.getByRole('button', { name: 'Record documented loss or damage' }).click()
    await expect(page.getByText(/Discrepancy review · version/)).toBeVisible()
    const close = page.getByRole('form', { name: 'Close documented discrepancy' })
    await close.getByLabel('Reason').fill('Reconciled 18 received units and closed the evidenced two-unit transit loss')
    await close.getByLabel('Evidence reference').fill(`reconciliation:${suffix}`)
    await close.getByRole('checkbox').check()
    await close.getByRole('button', { name: 'Close discrepancy' }).click()
    await expect(page.getByText(/Closed with discrepancy · version/)).toBeVisible()
    await expect(page.getByText(/Actor .* outstanding 0 → 20/)).toBeVisible()
    await expect(page.getByText('DISPATCH · 20 units')).toBeVisible()
    await expect(page.getByText('RECEIPT · 18 units')).toBeVisible()
    await expect(page.getByText('LOSS · 2 units')).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)

    restartBackend()
    await expect.poll(async () => (await owner.context.request.get('/api/v1/auth/me')).status(), { timeout: 60000 }).toBe(200)
    await page.reload()
    await expect(page.getByText(/Closed with discrepancy · version/)).toBeVisible()
    transfer = await read<Transfer>(owner.context, transferPath)
    expect(transfer.status).toBe('CLOSED_WITH_DISCREPANCY')
    expect(transfer.outstandingQuantity).toBe(0)
    expect(transfer.lines[0]!.quantities).toMatchObject({ dispatched: 20, physicallyReceived: 18, confirmedLoss: 2, outstanding: 0 })
    const sourceStock = await read<StockPage>(owner.context,
      `${organizationPath}/stores/${source.id}/inventory/positions?productId=${product.id}&lotId=${lot.id}&locationId=${sourceLocation.id}&offset=0&limit=100`)
    const destinationStock = await read<StockPage>(owner.context,
      `${organizationPath}/stores/${destination.id}/inventory/positions?productId=${product.id}&lotId=${lot.id}&locationId=${destinationLocation.id}&offset=0&limit=100`)
    expect(sourceStock.items.find(item => item.id === sourcePositionId)?.onHand).toBe(30)
    expect(sourceStock.items.find(item => item.id === sourcePositionId)?.reserved).toBe(0)
    expect(destinationStock.items).toHaveLength(1)
    expect(destinationStock.items[0]!.onHand).toBe(28)
    const custody = await read<{ items: { type: string, quantity: number, outstandingAfter: number, stockMovementId: string | null, actorId: string, evidenceReference: string }[] }>(owner.context, `${transferPath}/custody?offset=0&limit=50`)
    expect(custody.items.map(item => item.type).sort()).toEqual(['DISPATCH', 'LOSS', 'RECEIPT'])
    expect(custody.items.every(item => item.actorId === owner.id && item.evidenceReference.length > 0)).toBe(true)
    expect(custody.items.find(item => item.type === 'DISPATCH')?.stockMovementId).toBeTruthy()
    const discrepancies = await read<{ items: { transferId: string, type: string, evidenceReference: string }[] }>(owner.context,
      `${organizationPath}/transfers/discrepancies?storeId=${destination.id}&offset=0&limit=50`)
    expect(discrepancies.items.some(item => item.transferId === transferId && item.evidenceReference === `carrier-claim:${suffix}`)).toBe(true)

    const inventoryTableName = 'Stock by product, lot and storage location'
    await page.goto(`/organizations/${organization}/stores/${source.id}/inventory`)
    await expect(page.getByRole('heading', { name: 'Inventory', exact: true })).toBeVisible()
    const sourceInventoryRow = page.getByRole('table', { name: inventoryTableName }).getByRole('row')
      .filter({ hasText: product.sku }).filter({ hasText: lot.lotCode })
    await expect(sourceInventoryRow.getByRole('cell').nth(3)).toHaveText('30 EA')
    await expect(sourceInventoryRow.getByRole('cell').nth(4)).toHaveText('0 EA')
    await page.goto(`/organizations/${organization}/stores/${destination.id}/inventory`)
    await expect(page.getByRole('heading', { name: 'Inventory', exact: true })).toBeVisible()
    const destinationInventoryRow = page.getByRole('table', { name: inventoryTableName }).getByRole('row')
      .filter({ hasText: product.sku }).filter({ hasText: lot.lotCode })
    await expect(destinationInventoryRow.getByRole('cell').nth(3)).toHaveText('28 EA')
    await expect(destinationInventoryRow.getByRole('cell').nth(7)).toHaveText('28 EA')

    await verifyRecallBlocksInTransitReceipt(owner.context, organizationPath, source, destination, destinationLocation, product, lotAfterSafety, sourcePositionId, suffix)
    await page.goto(`/organizations/${organization}/stores/${destination.id}/inventory`)
    await expect(page.getByRole('heading', { name: 'Inventory', exact: true })).toBeVisible()
    const recalledInventoryRow = page.getByRole('table', { name: inventoryTableName }).getByRole('row')
      .filter({ hasText: product.sku }).filter({ hasText: lot.lotCode })
    await expect(recalledInventoryRow.getByRole('cell').nth(3)).toHaveText('33 EA')
    await expect(recalledInventoryRow.getByRole('cell').nth(5)).toHaveText('5 EA')
    await expect(recalledInventoryRow.getByRole('cell').nth(7)).toHaveText('28 EA')
  } finally {
    await recipient?.context.close()
    await owner.context.close()
  }
})

async function verifyRecallBlocksInTransitReceipt(context: BrowserContext, organizationPath: string, source: Store, destination: Store,
  destinationLocation: Location, product: Product, lot: Lot, positionId: string, suffix: string) {
  const snapshot = await read<components['schemas']['StockSnapshotResponse']>(context,
    `${organizationPath}/stores/${source.id}/inventory/positions/${positionId}`)
  expect(snapshot.onHand).toBe(30)
  const created = await write<components['schemas']['CommandResponse']>(context, `${organizationPath}/transfers`, {
    requestId: randomUUID(), sourceStoreId: source.id, destinationStoreId: destination.id,
    lines: [{ sourcePositionId: positionId, destinationLocationId: destinationLocation.id, quantity: 5, expectedSourceVersion: snapshot.version }],
    reason: 'Verify a newly recorded recall blocks stock already in transit', evidenceReference: `recall-test:${suffix}`,
  }, 200)
  const transferPath = `${organizationPath}/transfers/${created.transferId}`
  let transfer = await read<Transfer>(context, transferPath)
  const line = transfer.lines[0]!
  await write(context, `${transferPath}/approve`, { requestId: randomUUID(), expectedVersion: transfer.version, reason: 'Approve before the recall evidence is recorded', evidenceReference: `recall-approval:${suffix}` }, 200)
  transfer = await read<Transfer>(context, transferPath)
  await write(context, `${transferPath}/start-pick`, { requestId: randomUUID(), expectedVersion: transfer.version, reason: 'Release the reserved line to source picking', evidenceReference: `recall-picking:${suffix}` }, 200)
  transfer = await read<Transfer>(context, transferPath)
  await write(context, `${transferPath}/pick`, { requestId: randomUUID(), expectedVersion: transfer.version, lineId: line.id,
    documentReference: `RECALL-PICK-${suffix}`, quantity: 5, observedSku: product.sku, observedLotCode: lot.lotCode,
    reason: 'Verified the full line before recall notice', evidenceReference: `recall-pick:${suffix}` }, 200)
  transfer = await read<Transfer>(context, transferPath)
  await write(context, `${transferPath}/dispatch`, { requestId: randomUUID(), expectedVersion: transfer.version,
    documentReference: `RECALL-DISPATCH-${suffix}`, lines: [{ lineId: line.id, quantity: 5, observedSku: product.sku, observedLotCode: lot.lotCode }],
    reason: 'Carrier accepted the goods before recall notice', evidenceReference: `recall-dispatch:${suffix}` }, 200)
  transfer = await read<Transfer>(context, transferPath)
  expect(transfer.status).toBe('IN_TRANSIT')
  const currentLot = await read<Lot>(context, `${organizationPath}/lots/${lot.id}`)
  await write(context, `${organizationPath}/lots/${lot.id}/safety-status-evidence`, {
    requestId: randomUUID(), expectedVersion: currentLot.version, recallState: 'RECALLED', damageState: 'NOT_REPORTED',
    provenance: { source: 'Supplier recall notice', reference: `recall-notice:${suffix}` }, reason: 'Recorded the supplier recall notice while the line was in transit',
  }, 201)
  const destinationPositions = await read<StockPage>(context,
    `${organizationPath}/stores/${destination.id}/inventory/positions?productId=${product.id}&lotId=${lot.id}&locationId=${destinationLocation.id}&offset=0&limit=100`)
  const receive = await tryWrite<components['schemas']['CommandResponse']>(context, `${transferPath}/receive`, {
    requestId: randomUUID(), expectedVersion: transfer.version, documentReference: `RECALLED-RECEIPT-${suffix}`,
    physicallyReceivedAt: new Date().toISOString(),
    lines: [{ lineId: line.id, quantity: 5, expectedDestinationVersion: destinationPositions.items[0]?.version ?? 0,
      documentLineReference: 'recalled-line', observedSku: product.sku, observedLotCode: lot.lotCode }],
    reason: 'Attempted receipt after supplier recall notice', evidenceReference: `recall-block:${suffix}`,
  })
  expect(receive.status).toBe(200)
  expect(receive.body.status).toBe('RECEIVED')
  expect((await read<Transfer>(context, transferPath)).outstandingQuantity).toBe(0)
  const afterReceipt = await read<StockPage>(context,
    `${organizationPath}/stores/${destination.id}/inventory/positions?productId=${product.id}&lotId=${lot.id}&locationId=${destinationLocation.id}&offset=0&limit=100`)
  expect(afterReceipt.items).toHaveLength(1)
  expect(afterReceipt.items[0]).toMatchObject({ onHand: 33, quarantined: 5, available: 28 })
  const sourceAfter = await read<components['schemas']['StockSnapshotResponse']>(context,
    `${organizationPath}/stores/${source.id}/inventory/positions/${positionId}`)
  expect(sourceAfter.onHand).toBe(25)
}

async function account(browser: Browser): Promise<BrowserAccount> {
  const context = await browser.newContext()
  const email = `transfer-${randomUUID()}@example.test`
  const credentials = { email, password: 'real-transfer-owner-passphrase' }
  await write(context, '/api/v1/auth/sign-up', credentials, 202)
  const actor = await write<{ id: string }>(context, '/api/v1/auth/sign-in', credentials, 200)
  csrfTokens.delete(context)
  return { context, email, id: actor.id }
}

async function createOrganization(context: BrowserContext, name: string) {
  const result = await write<{ id: string }>(context, '/api/v1/organizations', { requestId: randomUUID(), name }, 201)
  return result.id
}

async function write<T = unknown>(context: BrowserContext, path: string, body: unknown, expectedStatus: number): Promise<T> {
  const token = csrfTokens.get(context) ?? (await admitted(() => context.request.get('/api/v1/auth/csrf'), 200) as { token: string }).token
  csrfTokens.set(context, token)
  let result: T | undefined
  await expect.poll(async () => {
    const response = await context.request.fetch(path, { method: 'POST', headers: { 'X-CSRF-TOKEN': token }, data: body })
    if (response.status() === 429) return false
    expect(response.status()).toBe(expectedStatus)
    result = await response.json() as T
    return true
  }, { timeout: 20000, intervals: [800, 1600, 3200] }).toBe(true)
  return result!
}

async function tryWrite<T = { code?: string }>(context: BrowserContext, path: string, body: unknown) {
  const token = csrfTokens.get(context) ?? (await admitted(() => context.request.get('/api/v1/auth/csrf'), 200) as { token: string }).token
  csrfTokens.set(context, token)
  let result: { status: number, body: T } | undefined
  await expect.poll(async () => {
    const response = await context.request.fetch(path, { method: 'POST', headers: { 'X-CSRF-TOKEN': token }, data: body })
    if (response.status() === 429) return false
    result = { status: response.status(), body: await response.json() as T }
    return true
  }, { timeout: 20000, intervals: [800, 1600, 3200] }).toBe(true)
  return result!
}

async function read<T>(context: BrowserContext, path: string): Promise<T> {
  return await admitted(() => context.request.get(path), 200) as T
}

async function admitted<T>(operation: () => Promise<{ status(): number, json(): Promise<T> }>, expectedStatus: number): Promise<T> {
  let result: T | undefined
  await expect.poll(async () => {
    const response = await operation()
    if (response.status() === 429) return false
    expect(response.status()).toBe(expectedStatus)
    result = await response.json()
    return true
  }, { timeout: 20000, intervals: [800, 1600, 3200] }).toBe(true)
  return result!
}

function restartBackend() {
  execFileSync('docker', ['compose', '--project-name', process.env.STOCKRESCUE_TEST_PROJECT!, '--env-file', process.env.STOCKRESCUE_TEST_ENV_FILE!, 'restart', 'backend'], {
    cwd: repository, stdio: 'ignore', timeout: 90000,
  })
}

const csrfTokens = new WeakMap<BrowserContext, string>()
