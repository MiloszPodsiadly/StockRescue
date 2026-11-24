import { expect, test } from '@playwright/test'
import type { Browser, BrowserContext, Page } from '@playwright/test'
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
type BrowserAccount = { context: BrowserContext, email: string, password: string, id: string }

test('store operators complete, recover and reconcile a physical stock transfer across Compose recovery', async ({ browser }) => {
  test.setTimeout(360000)
  test.skip(!process.env.STOCKRESCUE_TEST_PROJECT || !process.env.STOCKRESCUE_TEST_ENV_FILE,
    'Run this browser release proof through the Compose integration check.')

  const owner = await account(browser)
  let recipient: BrowserAccount | undefined
  let recoveryContext: BrowserContext | undefined
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
    const secondSourceLocation = await write<Location>(owner.context, `${organizationPath}/stores/${source.id}/storage-locations`, {
      requestId: randomUUID(), code: `SRC2_${suffix}`, name: 'Second transfer source shelf', zoneId: sourceZone.id,
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
    await write<{ lines: { positionId: string, version: number }[] }>(owner.context,
      `${organizationPath}/stores/${source.id}/inventory/receipt-documents`, {
        requestId: randomUUID(), supplierKey: `supplier:${suffix}`, documentType: 'DELIVERY_NOTE',
        documentReference: `DELIVERY-${suffix}`, physicallyReceivedAt: new Date().toISOString(),
        reason: 'Counted sealed source inventory before transfer release', evidenceReference: `supplier-document:${suffix}`,
        lines: [
          { documentLineReference: `line-${suffix}-1`, locationId: sourceLocation.id, productId: product.id,
            lotId: lot.id, quantity: 50, expectedVersion: 0 },
          { documentLineReference: `line-${suffix}-2`, locationId: secondSourceLocation.id, productId: product.id,
            lotId: lot.id, quantity: 30, expectedVersion: 0 },
        ],
      }, 201)
    const initialSourceStock = await read<StockPage>(owner.context,
      `${organizationPath}/stores/${source.id}/inventory/positions?productId=${product.id}&lotId=${lot.id}&locationId=${sourceLocation.id}&offset=0&limit=100`)
    const initialSecondSourceStock = await read<StockPage>(owner.context,
      `${organizationPath}/stores/${source.id}/inventory/positions?productId=${product.id}&lotId=${lot.id}&locationId=${secondSourceLocation.id}&offset=0&limit=100`)
    const sourcePositionId = initialSourceStock.items[0]!.id
    const secondSourcePositionId = initialSecondSourceStock.items[0]!.id
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

    let page = await owner.context.newPage()
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(`/organizations/${organization}/transfers`)
    await expect(page.getByRole('heading', { name: 'Stock transfers', exact: true })).toBeVisible()
    await page.getByRole('button', { name: 'New transfer request' }).click()
    await page.getByLabel('Source store', { exact: true }).selectOption(source.id)
    await page.getByLabel('Destination store', { exact: true }).selectOption(destination.id)
    const sourceChoice = page.getByLabel('Source product and lot')
    await expect(sourceChoice.locator(`option[value="${sourcePositionId}"]`)).toHaveCount(1)
    await sourceChoice.selectOption(sourcePositionId)
    await page.getByLabel('Destination receiving location').selectOption(destinationLocation.id)
    await page.getByLabel('Quantity (EA)').fill('20')
    await page.getByRole('button', { name: 'Add transfer line' }).click()
    const secondSourceChoice = page.getByLabel('Source product and lot for line 2')
    await expect(secondSourceChoice.locator(`option[value="${sourcePositionId}"]`)).toBeDisabled()
    await expect(secondSourceChoice.locator(`option[value="${secondSourcePositionId}"]`)).toHaveCount(1)
    await secondSourceChoice.selectOption(secondSourcePositionId)
    await page.getByLabel('Destination receiving location for line 2').selectOption(destinationLocation.id)
    await page.getByLabel('Quantity for line 2 (EA)').fill('5')
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
    expect(transfer.lines).toHaveLength(2)
    const line = transfer.lines[0]!
    expect(new Set(transfer.lines.map(item => item.sourcePositionId))).toEqual(new Set([sourcePositionId, secondSourcePositionId]))
    const twentyUnitLine = transfer.lines.find(item => item.requestedQuantity === 20)!
    const fiveUnitLine = transfer.lines.find(item => item.requestedQuantity === 5)!

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

    const firstPicking = page.getByRole('form', { name: `Pick line ${transfer.lines[0]!.lineNumber}` })
    await firstPicking.getByLabel('Pick document reference').fill(`PICK-${suffix}-1`)
    await firstPicking.getByLabel('Reason for pick').fill('Counted and scanned the complete first transfer line at source')
    await firstPicking.getByLabel('Evidence reference').fill(`pick-evidence:${suffix}-1`)
    await firstPicking.getByRole('checkbox').check()
    await firstPicking.getByRole('button', { name: 'Confirm full-line pick' }).click()
    const secondPicking = page.getByRole('form', { name: `Pick line ${transfer.lines[1]!.lineNumber}` })
    await secondPicking.getByLabel('Pick document reference').fill(`PICK-${suffix}-2`)
    await secondPicking.getByLabel('Reason for pick').fill('Counted and scanned the complete second transfer line at source')
    await secondPicking.getByLabel('Evidence reference').fill(`pick-evidence:${suffix}-2`)
    await secondPicking.getByRole('checkbox').check()
    await secondPicking.getByRole('button', { name: 'Confirm full-line pick' }).click()
    await expect(page.getByRole('button', { name: 'Confirm goods left the source store' })).toBeVisible()

    const dispatch = page.getByRole('form', { name: 'Confirm physical dispatch' })
    await dispatch.getByLabel('Dispatch document reference').fill(`DISPATCH-${suffix}`)
    await dispatch.getByLabel('Reason for dispatch').fill('Carrier collected the verified goods from the source store')
    await dispatch.getByLabel('Evidence reference').fill(`dispatch-proof:${suffix}`)
    await dispatch.getByRole('checkbox').check()
    await recoverCommittedTransferCommand(page, `${transferPath}/dispatch`,
      () => dispatch.getByRole('button', { name: 'Confirm goods left the source store' }).click(), 'In transit')
    transfer = await read<Transfer>(owner.context, transferPath)
    expect(transfer.status).toBe('IN_TRANSIT')
    expect(transfer.lines.find(item => item.sourcePositionId === sourcePositionId)?.quantities.dispatched).toBe(20)
    expect(transfer.lines.find(item => item.sourcePositionId === secondSourcePositionId)?.quantities.dispatched).toBe(5)
    expect(transfer.outstandingQuantity).toBe(25)

    recipient = await account(browser)
    const invitation = await write<{ token: string }>(owner.context, `${organizationPath}/invitations`, {
      email: recipient.email, storeGrants: [
        { storeId: destination.id, role: 'READ_ONLY_ANALYST' },
        { storeId: source.id, role: 'INVENTORY_OPERATOR' },
      ],
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
    await test.step('Restrict receiving and return requests by the destination grant', async () => {
      await recipientPage.goto(`/organizations/${organization}/transfers/${transferId}`)
      await expect(recipientPage.getByRole('heading', { name: /^Transfer [0-9a-f-]+$/i })).toBeVisible()
      await expect(recipientPage.getByRole('form', { name: /^Receive line / })).toHaveCount(0)
      await expect(recipientPage.getByRole('form', { name: 'Request physical return' })).toHaveCount(0)
      const unauthorizedReturn = await write<{ code: string }>(recipient!.context, `${transferPath}/request-return`, {
        requestId: randomUUID(), expectedVersion: transfer.version, requestReference: `UNAUTHORIZED-RETURN-${suffix}`,
        lines: [{ lineId: line.id }], reason: 'Attempted a return request without destination authorization',
        evidenceReference: `unauthorized-return:${suffix}`,
      }, 403)
      expect(unauthorizedReturn.code).toBe('ACCESS_DENIED')
    })

    const receiving = page.getByRole('form', { name: `Receive line ${twentyUnitLine.lineNumber}` })
    let receiptPayload: Record<string, unknown> | undefined
    page.on('request', request => {
      if (request.method() === 'POST' && request.url().endsWith(`/transfers/${transferId}/receive`)) {
        receiptPayload = request.postDataJSON() as Record<string, unknown>
      }
    })
    await receiving.getByLabel('Physical quantity received (EA)').fill('10')
    await receiving.getByLabel('Receiving document reference').fill(`RECEIVE-${suffix}`)
    await receiving.getByLabel('Document line reference').fill(`receive-line-${suffix}`)
    await receiving.getByLabel('Reason for receipt').fill('Counted ten sound packages at the destination dock')
    await receiving.getByLabel('Evidence reference').fill(`receiving-proof:${suffix}`)
    await receiving.getByRole('checkbox').check()
    await page.clock.setFixedTime(new Date(Date.now() + 2 * 60_000))
    await recoverCommittedTransferCommand(page, `${transferPath}/receive`,
      () => receiving.getByRole('button', { name: 'Record 10 physical units received' }).click(), 'Partially received')
    expect(receiptPayload).toBeDefined()
    expect(receiptPayload).not.toHaveProperty('physicallyReceivedAt')
    const twentyUnitLineView = page.getByRole('listitem').filter({
      hasText: `Line ${twentyUnitLine.lineNumber} · ${twentyUnitLine.sku} · lot ${twentyUnitLine.lotCode}`,
    })
    await expect(twentyUnitLineView.getByText('Received', { exact: true }).locator('..')).toContainText('10 EA')

    await expect.poll(async () => {
      const current = await read<Transfer>(owner.context, transferPath)
      return current.lines.find(item => item.id === twentyUnitLine.id)?.quantities.physicallyReceived
    }).toBe(10)

    const returnRequest = page.getByRole('form', { name: 'Request physical return' })
    await expect(returnRequest.getByText(new RegExp(`Line ${twentyUnitLine.lineNumber}: 10 EA outstanding`))).toBeVisible()
    await expect(returnRequest.getByText(new RegExp(`Line ${fiveUnitLine.lineNumber}: 5 EA outstanding`))).toBeVisible()
    await returnRequest.getByLabel('Return request reference').fill(`RETURN-${suffix}`)
    await returnRequest.getByLabel('Reason for return request').fill('Return the two packages that remained with the carrier after partial destination receipt')
    await returnRequest.getByLabel('Evidence reference').fill(`return-request:${suffix}`)
    await returnRequest.getByRole('checkbox', { name: /I understand this request records intent only/ }).check()
    await expect(returnRequest.getByRole('button', { name: 'Record return request' })).toBeEnabled()
    await returnRequest.getByRole('button', { name: 'Record return request' }).click()
    await expect(page.getByText(/10 EA remain unsettled under return request RETURN-/)).toBeVisible()
    const activeAfterRequest = await read<{ items: { lineId: string, requestReference: string, requestedQuantity: number, unsettledQuantity: number }[] }>(
      recipient!.context, `${transferPath}/active-returns`)
    expect(activeAfterRequest.items).toHaveLength(2)
    expect(activeAfterRequest.items.map(item => [item.lineId, item.requestReference, item.requestedQuantity, item.unsettledQuantity]))
      .toEqual(expect.arrayContaining([
        [twentyUnitLine.id, `RETURN-${suffix}`, 10, 10],
        [fiveUnitLine.id, `RETURN-${suffix}`, 5, 5],
      ]))

    transfer = await read<Transfer>(owner.context, transferPath)
    const destinationBeforeLateReceipt = await read<StockPage>(owner.context,
      `${organizationPath}/stores/${destination.id}/inventory/positions?productId=${product.id}&lotId=${lot.id}&locationId=${destinationLocation.id}&offset=0&limit=100`)
    const lateReceipt = await write<components['schemas']['CommandResponse']>(owner.context, `${transferPath}/receive`, {
      requestId: randomUUID(), expectedVersion: transfer.version, documentReference: `LATE-RECEIVE-${suffix}`,
      physicallyReceivedAt: new Date().toISOString(),
      lines: [{ lineId: fiveUnitLine.id, quantity: 1, expectedDestinationVersion: destinationBeforeLateReceipt.items[0]!.version,
        documentLineReference: `late-receive-line-${suffix}`, observedSku: fiveUnitLine.sku, observedLotCode: fiveUnitLine.lotCode }],
      reason: 'A package from the original request arrived after the return request was recorded',
      evidenceReference: `late-receipt:${suffix}`,
    }, 200)
    expect(lateReceipt.status).toBe('RECONCILIATION_REQUIRED')
    const supersededHistory = await read<{ items: { lineId: string | null, evidenceReference: string, resolutionScope: string }[] }>(
      owner.context, `${transferPath}/discrepancies?limit=50`)
    expect(supersededHistory.items).toEqual(expect.arrayContaining([
      expect.objectContaining({ lineId: fiveUnitLine.id, evidenceReference: `return-request:${suffix}`, resolutionScope: 'SUPERSEDED' }),
    ]))
    expect((await read<{ items: { lineId: string }[] }>(recipient!.context, `${transferPath}/active-returns`)).items)
      .toEqual([expect.objectContaining({ lineId: twentyUnitLine.id })])

    await page.reload()
    const replacementRequest = page.getByRole('form', { name: 'Request physical return' })
    await expect(replacementRequest.getByText(new RegExp(`Line ${fiveUnitLine.lineNumber}: 4 EA outstanding`))).toBeVisible()
    await replacementRequest.getByLabel('Return request reference').fill(`RETURN-${suffix}-REMAINDER`)
    await replacementRequest.getByLabel('Reason for return request').fill('Request the four packages still in transit after one late physical receipt')
    await replacementRequest.getByLabel('Evidence reference').fill(`return-request-remainder:${suffix}`)
    await replacementRequest.getByRole('checkbox', { name: /I understand this request records intent only/ }).check()
    await replacementRequest.getByRole('button', { name: 'Record return request' }).click()
    await expect.poll(async () => (await read<{ items: unknown[] }>(recipient!.context, `${transferPath}/active-returns`)).items)
      .toHaveLength(2)

    await recipientPage.reload()
    const firstPhysicalReturn = recipientPage.getByRole('form', { name: `Confirm physical return request RETURN-${suffix}`, exact: true })
    await expect(firstPhysicalReturn).toBeVisible()
    await expect(recipientPage.getByRole('form', { name: 'Request physical return' })).toHaveCount(0)
    await firstPhysicalReturn.getByLabel('Physical receipt reference').fill(`RETURN-${suffix}-A1`)
    await firstPhysicalReturn.getByLabel(`Quantity physically returned for line ${twentyUnitLine.lineNumber}`).fill('4')
    await firstPhysicalReturn.getByLabel(`Return document line for line ${twentyUnitLine.lineNumber}`).fill(`return-line-${suffix}-A`)
    await firstPhysicalReturn.getByLabel('Reason for physical return').fill('Physically counted four returned packages for line A at the source store')
    await firstPhysicalReturn.getByLabel('Evidence reference').fill(`source-return-receipt:${suffix}:A1`)
    await firstPhysicalReturn.getByRole('checkbox', { name: /I physically received and verified every selected/ }).check()
    await expect(firstPhysicalReturn.getByRole('button', { name: 'Confirm physical return received' })).toBeEnabled()
    await recoverCommittedTransferCommand(recipientPage, `${transferPath}/confirm-return`,
      () => firstPhysicalReturn.getByRole('button', { name: 'Confirm physical return received' }).click(), 'Reconciliation required')
    await expect.poll(async () => {
      const current = await read<Transfer>(recipient!.context, transferPath)
      return current.lines.map(item => [item.requestedQuantity, item.quantities.physicallyReturned, item.quantities.outstanding]).sort((left, right) => left[0]! - right[0]!)
    }).toEqual([[5, 0, 4], [20, 4, 6]])
    const activeAfterFirstReceipt = await read<{ items: { lineId: string, requestedQuantity: number,
      requestReference: string, settledQuantity: number, unsettledQuantity: number, state: string }[] }>(
      recipient!.context, `${transferPath}/active-returns`)
    expect(activeAfterFirstReceipt.items).toHaveLength(2)
    expect(activeAfterFirstReceipt.items).toEqual(expect.arrayContaining([
      expect.objectContaining({ lineId: twentyUnitLine.id, requestedQuantity: 10, settledQuantity: 4, unsettledQuantity: 6, state: 'PARTIALLY_FULFILLED' }),
      expect.objectContaining({ lineId: fiveUnitLine.id, requestReference: `RETURN-${suffix}-REMAINDER`,
        requestedQuantity: 4, settledQuantity: 0, unsettledQuantity: 4, state: 'PENDING' }),
    ]))
    await recipientPage.reload()
    const secondPhysicalReturn = recipientPage.getByRole('form', { name: `Confirm physical return request RETURN-${suffix}`, exact: true })
    await expect(secondPhysicalReturn).toBeVisible()
    await secondPhysicalReturn.getByLabel('Physical receipt reference').fill(`RETURN-${suffix}-A2`)
    await secondPhysicalReturn.getByLabel(`Quantity physically returned for line ${twentyUnitLine.lineNumber}`).fill('6')
    await secondPhysicalReturn.getByLabel(`Return document line for line ${twentyUnitLine.lineNumber}`).fill(`return-line-${suffix}-A2`)
    await secondPhysicalReturn.getByLabel('Reason for physical return').fill('Physically counted the remaining six packages for line A on a later receipt')
    await secondPhysicalReturn.getByLabel('Evidence reference').fill(`source-return-receipt:${suffix}:A2`)
    await secondPhysicalReturn.getByRole('checkbox', { name: /I physically received and verified every selected/ }).check()
    await expect(secondPhysicalReturn.getByRole('button', { name: 'Confirm physical return received' })).toBeEnabled()
    await recoverCommittedTransferCommand(recipientPage, `${transferPath}/confirm-return`,
      () => secondPhysicalReturn.getByRole('button', { name: 'Confirm physical return received' }).click(), 'Reconciliation required')
    await expect.poll(async () => {
      const current = await read<Transfer>(recipient!.context, transferPath)
      return current.lines.map(item => [item.requestedQuantity, item.quantities.physicallyReturned, item.quantities.outstanding]).sort((left, right) => left[0]! - right[0]!)
    }).toEqual([[5, 0, 4], [20, 10, 0]])
    expect((await read<{ items: { lineId: string }[] }>(recipient!.context, `${transferPath}/active-returns`)).items)
      .toEqual([expect.objectContaining({ lineId: fiveUnitLine.id })])
    await recipientPage.reload()
    const finalPhysicalReturn = recipientPage.getByRole('form', { name: `Confirm physical return request RETURN-${suffix}-REMAINDER`, exact: true })
    await expect(finalPhysicalReturn).toBeVisible()
    await finalPhysicalReturn.getByLabel('Physical receipt reference').fill(`RETURN-${suffix}-B`)
    await finalPhysicalReturn.getByLabel(`Quantity physically returned for line ${fiveUnitLine.lineNumber}`).fill('4')
    await finalPhysicalReturn.getByLabel(`Return document line for line ${fiveUnitLine.lineNumber}`).fill(`return-line-${suffix}-B`)
    await finalPhysicalReturn.getByLabel('Reason for physical return').fill('Physically counted the four remaining packages for line B at the source store')
    await finalPhysicalReturn.getByLabel('Evidence reference').fill(`source-return-receipt:${suffix}:B`)
    await finalPhysicalReturn.getByRole('checkbox', { name: /I physically received and verified every selected/ }).check()
    await recoverCommittedTransferCommand(recipientPage, `${transferPath}/confirm-return`,
      () => finalPhysicalReturn.getByRole('button', { name: 'Confirm physical return received' }).click(), 'Reconciliation required')
    await expect.poll(async () => {
      const current = await read<Transfer>(recipient!.context, transferPath)
      return current.lines.map(item => [item.requestedQuantity, item.quantities.physicallyReceived,
        item.quantities.physicallyReturned, item.quantities.outstanding]).sort((left, right) => left[0]! - right[0]!)
    }).toEqual([[5, 1, 4, 0], [20, 10, 10, 0]])
    expect((await read<{ items: unknown[] }>(recipient!.context, `${transferPath}/active-returns`)).items).toHaveLength(0)
    await expect(recipientPage.getByText(/RETURN.*4 units/)).toHaveCount(2)
    await expect(recipientPage.getByText(/RETURN.*6 units/)).toBeVisible()
    await page.reload()
    await expect(page.getByText(/Reconciliation required.*version/)).toBeVisible()
    const reconciliation = page.getByRole('form', { name: 'Confirm final reconciliation' })
    await reconciliation.getByLabel('Reason').fill('Reconciled the staged physical returns and eleven units received at the destination')
    await reconciliation.getByLabel('Evidence reference').fill(`reconciliation:${suffix}`)
    await reconciliation.getByRole('checkbox').check()
    await expect(reconciliation.getByRole('button', { name: 'Confirm reconciliation' })).toBeEnabled()
    const closePayloads: unknown[] = []
    const captureClose = (request: import('@playwright/test').Request) => {
      if (request.method() === 'POST' && request.url().endsWith('/close-discrepancy')) closePayloads.push(request.postDataJSON())
    }
    const closeRequestPage = page
    closeRequestPage.on('request', captureClose)
    page = await recoverCommittedTransferCommand(page, `${transferPath}/close-discrepancy`,
      () => reconciliation.getByRole('button', { name: 'Confirm reconciliation' }).click(), 'Closed with discrepancy', async () => {
        recoveryContext = await signIn(browser, owner.email, owner.password)
        await write(recoveryContext, '/api/v1/auth/active-organization', { organizationId: organization }, 200)
        const reopened = await recoveryContext.newPage()
        await reopened.goto(`/organizations/${organization}/transfers/${transferId}`)
        return reopened
      })
    closeRequestPage.off('request', captureClose)
    expect(closePayloads).toHaveLength(1)
    const closePayload = closePayloads[0] as Record<string, unknown>
    const closedBeforeRetry = await read<Transfer>(owner.context, transferPath)
    expect(closedBeforeRetry.status).toBe('CLOSED_WITH_DISCREPANCY')
    const exactRetry = await write<components['schemas']['CommandResponse']>(owner.context,
      `${transferPath}/close-discrepancy`, closePayload, 200)
    expect(exactRetry.version).toBe(closedBeforeRetry.version)
    expect((await read<Transfer>(owner.context, transferPath)).version).toBe(closedBeforeRetry.version)

    const isolatedPending = await write<components['schemas']['TransferCommandIntentSummary']>(owner.context,
      `${organizationPath}/transfer-command-intents`, {
        prepareId: randomUUID(), operation: 'CLOSE', transferId,
        payload: { requestId: randomUUID(), expectedVersion: closedBeforeRetry.version,
          reason: 'Keep an owner-only command draft for account boundary verification', evidenceReference: `owner-only:${suffix}` },
      }, 200)
    const isolatedRequestId = isolatedPending.requestId
    if (isolatedPending.redacted || !isolatedRequestId) throw new Error('Owner command summary was unexpectedly redacted')
    expect((await read<components['schemas']['TransferCommandIntentPage']>(recipient!.context,
      `${organizationPath}/transfer-command-intents`)).items).toHaveLength(0)
    if (!recoveryContext) throw new Error('Owner recovery session was not created')
    await page.evaluate(({ organizationId, intent }) => {
      sessionStorage.setItem(`stockrescue.transfer-command.${organizationId}`, JSON.stringify({ intent, savedAt: new Date().toISOString() }))
    }, { organizationId: organization, intent: { operation: 'CLOSE', transferId,
      body: { requestId: isolatedRequestId, expectedVersion: closedBeforeRetry.version,
        reason: 'Legacy browser-only transfer draft', evidenceReference: `legacy:${suffix}` } } })
    await switchSession(page, recipient!.context, `/organizations/${organization}/transfers/${transferId}`)
    await expect(page.getByRole('heading', { name: /^Transfer [0-9a-f-]+$/i })).toBeVisible()
    await expect(page.getByRole('region', { name: 'Recover saved transfer command' })).toHaveCount(0)
    await expect(page.getByText(isolatedRequestId)).toHaveCount(0)
    expect(await page.evaluate(() => Array.from({ length: sessionStorage.length }, (_, index) => sessionStorage.key(index))
      .filter(key => key?.startsWith('stockrescue.transfer-command.')))).toHaveLength(0)
    await switchSession(page, owner.context, `/organizations/${organization}/transfers/${transferId}`)
    await expect(page.getByRole('region', { name: 'Recover saved transfer command' })).toBeVisible()
    await expect(page.getByText(isolatedRequestId)).toBeVisible()
    await write(owner.context, `${organizationPath}/transfer-command-intents/${isolatedPending.id}/resolve-failure`,
      { resolution: 'REJECTED' }, 200)
    await page.reload()
    await expect(page.getByRole('region', { name: 'Recover saved transfer command' })).toHaveCount(0)

    await expect(page.getByText(/Closed with discrepancy · version/)).toBeVisible()
    await expect(page.getByText(/Actor .* outstanding 4 → 0/)).toBeVisible()
    await expect(page.getByText(/Actor .* outstanding 6 → 0/)).toBeVisible()
    await expect(page.getByText('DISPATCH · 20 units')).toBeVisible()
    await expect(page.getByText('DISPATCH · 5 units')).toBeVisible()
    await expect(page.getByText('RECEIPT · 10 units')).toBeVisible()
    await expect(page.getByText('RECEIPT · 1 units')).toBeVisible()
    await expect(page.getByText('RETURN · 4 units')).toHaveCount(2)
    await expect(page.getByText('RETURN · 6 units')).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)

    restartBackend()
    await expect.poll(async () => (await owner.context.request.get('/api/v1/auth/me')).status(), { timeout: 60000 }).toBe(200)
    await page.reload()
    await expect(page.getByText(/Closed with discrepancy · version/)).toBeVisible()
    transfer = await read<Transfer>(owner.context, transferPath)
    expect(transfer.status).toBe('CLOSED_WITH_DISCREPANCY')
    expect(transfer.outstandingQuantity).toBe(0)
    expect(transfer.lines.find(item => item.id === twentyUnitLine.id)!.quantities).toMatchObject({ dispatched: 20, physicallyReceived: 10, physicallyReturned: 10, confirmedLoss: 0, outstanding: 0 })
    expect(transfer.lines.find(item => item.id === fiveUnitLine.id)!.quantities).toMatchObject({ dispatched: 5, physicallyReceived: 1, physicallyReturned: 4, confirmedLoss: 0, outstanding: 0 })
    const sourceStock = await read<StockPage>(owner.context,
      `${organizationPath}/stores/${source.id}/inventory/positions?productId=${product.id}&lotId=${lot.id}&locationId=${sourceLocation.id}&offset=0&limit=100`)
    const secondSourceStock = await read<StockPage>(owner.context,
      `${organizationPath}/stores/${source.id}/inventory/positions?productId=${product.id}&lotId=${lot.id}&locationId=${secondSourceLocation.id}&offset=0&limit=100`)
    const destinationStock = await read<StockPage>(owner.context,
      `${organizationPath}/stores/${destination.id}/inventory/positions?productId=${product.id}&lotId=${lot.id}&locationId=${destinationLocation.id}&offset=0&limit=100`)
    expect(sourceStock.items.find(item => item.id === sourcePositionId)?.onHand).toBe(40)
    expect(sourceStock.items.find(item => item.id === sourcePositionId)?.reserved).toBe(0)
    expect(secondSourceStock.items.find(item => item.id === secondSourcePositionId)?.onHand).toBe(29)
    expect(secondSourceStock.items.find(item => item.id === secondSourcePositionId)?.reserved).toBe(0)
    expect(destinationStock.items).toHaveLength(1)
    expect(destinationStock.items[0]!.onHand).toBe(21)
    const custody = await read<{ items: { type: string, quantity: number, outstandingAfter: number, stockMovementId: string | null, actorId: string, evidenceReference: string }[] }>(owner.context, `${transferPath}/custody?offset=0&limit=50`)
    expect(custody.items.map(item => `${item.type}:${item.quantity}`).sort()).toEqual(
      ['DISPATCH:20', 'DISPATCH:5', 'RECEIPT:1', 'RECEIPT:10', 'RETURN:4', 'RETURN:4', 'RETURN:6'])
    expect(custody.items.filter(item => item.type !== 'RETURN').every(item => item.actorId === owner.id && item.evidenceReference.length > 0)).toBe(true)
    expect(custody.items.filter(item => item.type === 'RETURN').every(item => item.actorId === recipient!.id && item.evidenceReference.length > 0)).toBe(true)
    expect(custody.items.find(item => item.type === 'DISPATCH')?.stockMovementId).toBeTruthy()
    const discrepancies = await read<{ items: { transferId: string, type: string, evidenceReference: string }[] }>(owner.context,
      `${organizationPath}/transfers/discrepancies?storeId=${destination.id}&offset=0&limit=50`)
    expect(discrepancies.items.some(item => item.transferId === transferId && item.evidenceReference === `return-request:${suffix}`)).toBe(true)

    const inventoryTableName = 'Stock by product, lot and storage location'
    await page.goto(`/organizations/${organization}/stores/${source.id}/inventory`)
    await expect(page.getByRole('heading', { name: 'Inventory', exact: true })).toBeVisible()
    const sourceInventoryRow = page.getByRole('table', { name: inventoryTableName }).getByRole('row')
      .filter({ hasText: product.sku }).filter({ hasText: lot.lotCode }).filter({ hasText: sourceLocation.code })
    const secondSourceInventoryRow = page.getByRole('table', { name: inventoryTableName }).getByRole('row')
      .filter({ hasText: product.sku }).filter({ hasText: lot.lotCode }).filter({ hasText: secondSourceLocation.code })
    await expect(sourceInventoryRow.getByRole('cell').nth(3)).toHaveText('40 EA')
    await expect(sourceInventoryRow.getByRole('cell').nth(4)).toHaveText('0 EA')
    await expect(secondSourceInventoryRow.getByRole('cell').nth(3)).toHaveText('29 EA')
    await expect(secondSourceInventoryRow.getByRole('cell').nth(4)).toHaveText('0 EA')
    await page.goto(`/organizations/${organization}/stores/${destination.id}/inventory`)
    await expect(page.getByRole('heading', { name: 'Inventory', exact: true })).toBeVisible()
    const destinationInventoryRow = page.getByRole('table', { name: inventoryTableName }).getByRole('row')
      .filter({ hasText: product.sku }).filter({ hasText: lot.lotCode })
    await expect(destinationInventoryRow.getByRole('cell').nth(3)).toHaveText('21 EA')
    await expect(destinationInventoryRow.getByRole('cell').nth(7)).toHaveText('21 EA')

    await verifyRecallBlocksInTransitReceipt(owner.context, organizationPath, source, destination, destinationLocation, product, lotAfterSafety, sourcePositionId, suffix)
    await page.goto(`/organizations/${organization}/stores/${destination.id}/inventory`)
    await expect(page.getByRole('heading', { name: 'Inventory', exact: true })).toBeVisible()
    const recalledInventoryRow = page.getByRole('table', { name: inventoryTableName }).getByRole('row')
      .filter({ hasText: product.sku }).filter({ hasText: lot.lotCode })
    await expect(recalledInventoryRow.getByRole('cell').nth(3)).toHaveText('26 EA')
    await expect(recalledInventoryRow.getByRole('cell').nth(5)).toHaveText('5 EA')
    await expect(recalledInventoryRow.getByRole('cell').nth(7)).toHaveText('21 EA')

    await exerciseArchivedDestinationReturnRecovery(page, owner.context, organization, source, destination,
      sourceLocation, destinationLocation, product, suffix)
  } finally {
    const cleanup = await Promise.allSettled([recoveryContext?.close() ?? Promise.resolve(), recipient?.context.close() ?? Promise.resolve(), owner.context.close()])
    for (const result of cleanup) {
      if (result.status === 'rejected') console.warn('Transfer browser context cleanup failed:', result.reason)
    }
  }
})

async function exerciseArchivedDestinationReturnRecovery(page: Page, context: BrowserContext, organization: string,
  source: Store, destination: Store, sourceLocation: Location, destinationLocation: Location, product: Product, suffix: string) {
  const organizationPath = `/api/v1/organizations/${organization}`
  const historicalSuffix = `${suffix}-HISTORY`
  const expiry = new Date(Date.now() + 60 * 86400000).toISOString().slice(0, 10)
  const lot = await write<Lot>(context, `${organizationPath}/lots`, {
    requestId: randomUUID(), productId: product.id, storeId: source.id, lotCode: `HISTORY-${historicalSuffix}`,
    identityState: 'CONFIRMED', manufacturerVerified: true, manufacturerIdentityKey: `manufacturer:${historicalSuffix}`,
    manufacturerProvenance: { source: 'Manufacturer transfer record', reference: `maker:${historicalSuffix}` },
    dateMarking: { labeledValue: expiry, type: 'USE_BY', precision: 'DAY',
      provenance: { source: 'Original package label', reference: `expiry:${historicalSuffix}` } },
  }, 201)
  await write(context, `${organizationPath}/lots/${lot.id}/safety-status-evidence`, {
    requestId: randomUUID(), expectedVersion: lot.version, recallState: 'NOT_REPORTED', damageState: 'NOT_REPORTED',
    provenance: { source: 'Reviewed supplier status', reference: `safety:${historicalSuffix}` },
    reason: 'Reviewed supplier status for historical settlement verification',
  }, 201)
  await write(context, `${organizationPath}/stores/${source.id}/inventory/receipt-documents`, {
    requestId: randomUUID(), supplierKey: `supplier:${historicalSuffix}`, documentType: 'DELIVERY_NOTE',
    documentReference: `DELIVERY-${historicalSuffix}`, physicallyReceivedAt: new Date().toISOString(),
    reason: 'Counted the dedicated historical settlement test quantity at source',
    evidenceReference: `supplier-document:${historicalSuffix}`,
    lines: [{ documentLineReference: `line-${historicalSuffix}`, locationId: sourceLocation.id,
      productId: product.id, lotId: lot.id, quantity: 5, expectedVersion: 0 }],
  }, 201)
  const stock = await read<StockPage>(context,
    `${organizationPath}/stores/${source.id}/inventory/positions?productId=${product.id}&lotId=${lot.id}&locationId=${sourceLocation.id}&offset=0&limit=100`)
  expect(stock.items).toHaveLength(1)
  const sourcePosition = stock.items[0]!
  const created = await write<components['schemas']['CommandResponse']>(context, `${organizationPath}/transfers`, {
    requestId: randomUUID(), sourceStoreId: source.id, destinationStoreId: destination.id,
    lines: [{ sourcePositionId: sourcePosition.id, destinationLocationId: destinationLocation.id,
      quantity: 5, expectedSourceVersion: sourcePosition.version }],
    reason: 'Create a transfer to verify archived destination settlement recovery',
    evidenceReference: `historical-transfer:${historicalSuffix}`,
  }, 200)
  const transferPath = `${organizationPath}/transfers/${created.transferId}`
  let transfer = await read<Transfer>(context, transferPath)
  const line = transfer.lines[0]!
  await write(context, `${transferPath}/approve`, { requestId: randomUUID(), expectedVersion: transfer.version,
    reason: 'Approve the dedicated historical settlement transfer', evidenceReference: `approval:${historicalSuffix}` }, 200)
  transfer = await read<Transfer>(context, transferPath)
  await write(context, `${transferPath}/start-pick`, { requestId: randomUUID(), expectedVersion: transfer.version,
    reason: 'Release the historical settlement line for physical picking', evidenceReference: `pick-start:${historicalSuffix}` }, 200)
  transfer = await read<Transfer>(context, transferPath)
  await write(context, `${transferPath}/pick`, { requestId: randomUUID(), expectedVersion: transfer.version,
    lineId: line.id, documentReference: `PICK-${historicalSuffix}`, quantity: 5, observedSku: product.sku,
    observedLotCode: lot.lotCode, reason: 'Counted all five units at source', evidenceReference: `pick:${historicalSuffix}` }, 200)
  transfer = await read<Transfer>(context, transferPath)
  await write(context, `${transferPath}/dispatch`, { requestId: randomUUID(), expectedVersion: transfer.version,
    documentReference: `DISPATCH-${historicalSuffix}`, lines: [{ lineId: line.id, quantity: 5,
      observedSku: product.sku, observedLotCode: lot.lotCode }], reason: 'Carrier accepted the counted units',
    evidenceReference: `dispatch:${historicalSuffix}` }, 200)
  transfer = await read<Transfer>(context, transferPath)
  expect(transfer.status).toBe('IN_TRANSIT')

  setStoreActiveInCompose(organization, destination.id, false)
  await page.goto(`/organizations/${organization}/transfers/historical`)
  await expect(page.getByRole('heading', { name: 'Historical transfers', exact: true })).toBeVisible()
  const historicalRecord = page.getByRole('listitem').filter({ hasText: `Transfer ${created.transferId}` })
  await expect(historicalRecord).toHaveCount(1)
  await expect(historicalRecord).toContainText(`${destination.code} · ${destination.name}`)
  await expect(historicalRecord).toContainText('Archived')
  await historicalRecord.getByRole('link', { name: 'Open historical transfer' }).click()
  await expect(page.getByRole('region', { name: 'Recover saved transfer command' })).toHaveCount(0)
  const participantSummary = page.getByRole('heading', { name: `Transfer ${created.transferId}`, exact: true })
    .locator('xpath=following-sibling::p[1]')
  await expect(participantSummary).toContainText(`${source.code} · ${source.name}`)
  await expect(participantSummary).toContainText(`${destination.code} · ${destination.name}`)
  await expect(page.getByText('Archived')).toHaveCount(1)
  await expect(page.getByRole('note', { name: 'Administrative historical settlement' })).toBeVisible()
  const returnRequest = page.getByRole('form', { name: 'Request physical return' })
  await expect(returnRequest).toBeVisible()
  await returnRequest.getByLabel('Return request reference').fill(`RETURN-${historicalSuffix}`)
  await returnRequest.getByLabel('Reason for return request').fill('Document the physical return of all outstanding historical custody')
  await returnRequest.getByLabel('Evidence reference').fill(`return-request:${historicalSuffix}`)
  await returnRequest.getByRole('checkbox', { name: /I understand this request records intent only/ }).check()
  await expect(returnRequest.getByRole('button', { name: 'Record return request' })).toBeEnabled()
  const returnBodies: unknown[] = []
  const captureReturn = (request: import('@playwright/test').Request) => {
    if (request.method() === 'POST' && request.url().endsWith('/request-return')) returnBodies.push(request.postDataJSON())
  }
  page.on('request', captureReturn)
  try {
    page = await recoverCommittedTransferCommand(page, `${transferPath}/request-return`,
      () => returnRequest.getByRole('button', { name: 'Record return request' }).click(), 'Reconciliation required')
  } finally {
    page.off('request', captureReturn)
  }
  expect(returnBodies.length).toBeGreaterThan(0)
  const returnBody = returnBodies[0] as { requestId: string, requestReference: string }
  transfer = await read<Transfer>(context, transferPath)
  expect(transfer.status).toBe('RECONCILIATION_REQUIRED')
  expect(transfer.outstandingQuantity).toBe(5)
  const exactRetry = await write<components['schemas']['CommandResponse']>(context,
    `${transferPath}/request-return`, returnBody, 200)
  expect(exactRetry.version).toBe(transfer.version)

  const currentPosition = await read<components['schemas']['StockSnapshotResponse']>(context,
    `${organizationPath}/stores/${source.id}/inventory/positions/${sourcePosition.id}`)
  const returned = await write<components['schemas']['CommandResponse']>(context, `${transferPath}/confirm-return`, {
    requestId: randomUUID(), expectedVersion: transfer.version, requestReference: returnBody.requestReference,
    documentReference: `RETURN-RECEIPT-${historicalSuffix}`,
    lines: [{ lineId: line.id, quantity: 5, expectedSourceVersion: currentPosition.version,
      documentLineReference: `return-line-${historicalSuffix}`, observedSku: product.sku, observedLotCode: lot.lotCode }],
    reason: 'Physically counted all five units returned to the active source location',
    evidenceReference: `return-receipt:${historicalSuffix}`,
  }, 200)
  expect(returned.status).toBe('RECONCILIATION_REQUIRED')
  transfer = await read<Transfer>(context, transferPath)
  expect(transfer.outstandingQuantity).toBe(0)
  await page.reload()
  const close = page.getByRole('form', { name: 'Confirm final reconciliation' })
  await expect(close).toBeVisible()
  await close.getByLabel('Reason').fill('Reconciled the verified physical return and zero outstanding custody')
  await close.getByLabel('Evidence reference').fill(`reconciliation:${historicalSuffix}`)
  await close.getByRole('checkbox').check()
  await recoverCommittedTransferCommand(page, `${transferPath}/close-discrepancy`,
    () => close.getByRole('button', { name: 'Confirm reconciliation' }).click(), 'Closed with discrepancy')

  transfer = await read<Transfer>(context, transferPath)
  expect(transfer.status).toBe('CLOSED_WITH_DISCREPANCY')
  expect(transfer.lines[0]!.quantities).toMatchObject({ dispatched: 5, physicallyReturned: 5, outstanding: 0 })
  const custody = await read<{ items: { type: string, quantity: number, outstandingAfter: number }[] }>(context,
    `${transferPath}/custody?offset=0&limit=50`)
  expect(custody.items.map(item => `${item.type}:${item.quantity}`).sort()).toEqual(['DISPATCH:5', 'RETURN:5'])
  expect(custody.items).toEqual(expect.arrayContaining([
    expect.objectContaining({ type: 'DISPATCH', quantity: 5, outstandingAfter: 5 }),
    expect.objectContaining({ type: 'RETURN', quantity: 5, outstandingAfter: 0 }),
  ]))
  const sourceAfterReturn = await read<components['schemas']['StockSnapshotResponse']>(context,
    `${organizationPath}/stores/${source.id}/inventory/positions/${sourcePosition.id}`)
  expect(sourceAfterReturn.onHand).toBe(5)
  expect(readStoreActiveInCompose(organization, destination.id)).toBe('f')
}

function setStoreActiveInCompose(organization: string, storeId: string, active: boolean) {
  const ids = [organization, storeId]
  if (ids.some(id => !/^[0-9a-f-]{36}$/i.test(id))) throw new Error('Expected database fixture identifiers to be UUIDs')
  const output = execFileSync('docker', ['compose', '--project-name', process.env.STOCKRESCUE_TEST_PROJECT!,
    '--env-file', process.env.STOCKRESCUE_TEST_ENV_FILE!, 'exec', '-T', 'postgres', 'psql', '-U',
    'stockrescue_admin', '-d', 'stockrescue', '-v', 'ON_ERROR_STOP=1', '-c',
    `UPDATE stockrescue.store SET active=${active ? 'TRUE' : 'FALSE'},version=version+1,updated_at=clock_timestamp() WHERE org_id='${organization}'::uuid AND id='${storeId}'::uuid;`],
  { cwd: repository, encoding: 'utf8', timeout: 30000 })
  if (!output.includes('UPDATE 1')) throw new Error('The isolated Compose fixture did not update exactly one store')
}

function readStoreActiveInCompose(organization: string, storeId: string) {
  const output = execFileSync('docker', ['compose', '--project-name', process.env.STOCKRESCUE_TEST_PROJECT!,
    '--env-file', process.env.STOCKRESCUE_TEST_ENV_FILE!, 'exec', '-T', 'postgres', 'psql', '-U',
    'stockrescue_admin', '-d', 'stockrescue', '-Atc',
    `SELECT active FROM stockrescue.store WHERE org_id='${organization}'::uuid AND id='${storeId}'::uuid;`],
  { cwd: repository, encoding: 'utf8', timeout: 30000 })
  return output.trim()
}

async function verifyRecallBlocksInTransitReceipt(context: BrowserContext, organizationPath: string, source: Store, destination: Store,
  destinationLocation: Location, product: Product, lot: Lot, positionId: string, suffix: string) {
  const snapshot = await read<components['schemas']['StockSnapshotResponse']>(context,
    `${organizationPath}/stores/${source.id}/inventory/positions/${positionId}`)
  expect(snapshot.onHand).toBe(40)
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
  expect(afterReceipt.items[0]).toMatchObject({ onHand: 26, quarantined: 5, available: 21 })
  const sourceAfter = await read<components['schemas']['StockSnapshotResponse']>(context,
    `${organizationPath}/stores/${source.id}/inventory/positions/${positionId}`)
  expect(sourceAfter.onHand).toBe(35)
}

async function account(browser: Browser): Promise<BrowserAccount> {
  const context = await browser.newContext()
  const email = `transfer-${randomUUID()}@example.test`
  const password = 'real-transfer-owner-passphrase'
  await write(context, '/api/v1/auth/sign-up', { email, password }, 202)
  const actor = await write<{ id: string }>(context, '/api/v1/auth/sign-in', { email, password }, 200)
  csrfTokens.delete(context)
  return { context, email, password, id: actor.id }
}

async function signIn(browser: Browser, email: string, password: string) {
  const context = await browser.newContext()
  await write(context, '/api/v1/auth/sign-in', { email, password }, 200)
  csrfTokens.delete(context)
  return context
}

async function switchSession(page: Page, sourceContext: BrowserContext, returnPath: string) {
  await page.getByRole('button', { name: 'Sign out', exact: true }).click()
  await expect(page).toHaveURL(/\/sign-in$/)
  const context = page.context()
  const sessionCookie = (await sourceContext.cookies()).find(cookie => cookie.name === 'SRSESSION')
  if (!sessionCookie) throw new Error('The target account has no authenticated session cookie')
  await context.addCookies([sessionCookie])
  csrfTokens.delete(context)
  await admitted(() => context.request.get('/api/v1/auth/me'), 200)
  await page.goto(returnPath)
}

async function recoverCommittedTransferCommand(page: Page, path: string, submit: () => Promise<void>, expectedStatus: string,
  reopen?: () => Promise<Page>) {
  let committedResponses = 0
  const routePattern = `**${path}`
  const dropResponse = async (route: import('@playwright/test').Route) => {
    const response = await route.fetch()
    expect(response.status()).toBe(200)
    committedResponses++
    await route.abort('connectionreset')
  }
  await page.route(routePattern, dropResponse)
  try {
    await submit()
    await expect.poll(() => committedResponses, { timeout: 20000, intervals: [100, 250, 500] }).toBe(1)
    const recovery = page.getByRole('region', { name: 'Recover saved transfer command' })
    await expect(recovery).toBeVisible()
    let recoveryPage = page
    if (reopen) recoveryPage = await reopen()
    else await page.reload()
    const reopenedRegion = recoveryPage.getByRole('region', { name: 'Recover saved transfer command' })
    await expect(reopenedRegion).toBeVisible()
    const intentResponses: { method: string, path: string, status: number, details: Record<string, unknown> }[] = []
    const pendingIntentResponses: Promise<void>[] = []
    const captureIntentResponse = (response: import('@playwright/test').Response) => {
      const url = new URL(response.url())
      if (!url.pathname.includes('/transfer-command-intents/')) return
      const capture = (async () => {
        const details: Record<string, unknown> = {}
        try {
          const body = await response.json() as Record<string, unknown>
          for (const key of ['code', 'state', 'executionStatus', 'redacted']) {
            if (key in body) details[key] = body[key]
          }
          const outcome = body.committedOutcome
          if (typeof outcome === 'object' && outcome !== null && 'status' in outcome) {
            details.committedOutcomeStatus = (outcome as { status: unknown }).status
          }
        } catch { /* Keep status and route when a problem response has no JSON body. */ }
        intentResponses.push({ method: response.request().method(), path: url.pathname, status: response.status(), details })
      })()
      pendingIntentResponses.push(capture)
    }
    recoveryPage.on('response', captureIntentResponse)
    try {
      await recoveryPage.getByRole('button', { name: 'Inspect saved outcome' }).click()
      await expect(recoveryPage.getByText(`Recovered ${expectedStatus} from the saved command record.`)).toBeVisible()
    } catch (failure) {
      await Promise.all(pendingIntentResponses)
      const visibleMessages = await recoveryPage.locator('[role="alert"], [role="status"]').allTextContents()
      throw new Error(`${String(failure)}\nRecovery intent API responses: ${JSON.stringify(intentResponses)}\nVisible messages: ${JSON.stringify(visibleMessages)}`, { cause: failure })
    } finally {
      recoveryPage.off('response', captureIntentResponse)
    }
    return recoveryPage
  } finally {
    if (!page.isClosed()) await page.unroute(routePattern, dropResponse)
  }
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
    const responseBody = await response.json() as unknown
    if (response.status() !== expectedStatus) {
      const code = typeof responseBody === 'object' && responseBody !== null && 'code' in responseBody
        ? String((responseBody as { code?: unknown }).code ?? 'unknown') : 'unknown'
      expect(response.status(), `API problem code: ${code}`).toBe(expectedStatus)
    }
    result = responseBody as T
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
