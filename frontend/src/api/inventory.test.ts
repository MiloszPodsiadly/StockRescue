import { afterEach, expect, it, vi } from 'vitest'
import { clearCsrf } from './client'
import { getInventoryCommandOutcome, getInventoryReceiptDocumentOutcome, receiveInventoryGoods, receiveInventoryGoodsDocument, repairInventoryProjection } from './inventory'

const json = (body: unknown) => new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })
afterEach(() => { clearCsrf(); vi.unstubAllGlobals() })

it('retries a lost physical receipt acknowledgement with the same key and exact versioned payload', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ token: 'csrf-inventory' })).mockRejectedValueOnce(new TypeError('Lost response'))
    .mockResolvedValueOnce(json({ movementId: 'movement', positionId: 'position', positionVersion: 1, onHand: 10, reserved: 0, quarantined: 0, otherBlocked: 0, available: 10, allocationId: null, receiptId: 'receipt' }))
  vi.stubGlobal('fetch', fetcher)
  const signal = new AbortController().signal
  const body = { requestId: 'receipt-request', expectedVersion: 0, locationId: 'location', productId: 'product', lotId: 'lot', quantity: 10, supplierKey: 'supplier', documentType: 'DELIVERY_NOTE', documentReference: 'DN-10', documentLineReference: '1', physicallyReceivedAt: '2026-11-12T10:00:00Z', reason: 'Physical goods checked', evidenceReference: 'https://supplier.example.test/dn-10' }
  await expect(receiveInventoryGoods('org', 'store', body, signal)).rejects.toThrow('Lost response')
  const result = await receiveInventoryGoods('org', 'store', body, signal)
  expect(result.positionVersion).toBe(1)
  expect(await (fetcher.mock.calls[1][0] as Request).json()).toEqual(body)
  expect(await (fetcher.mock.calls[2][0] as Request).json()).toEqual(body)
  expect(fetcher.mock.calls[1][0].headers.get('X-CSRF-TOKEN')).toBe('csrf-inventory')
})

it('looks up a committed result using the original command key and operation', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ movementId: 'movement', positionId: 'position', positionVersion: 4, onHand: 4, reserved: 0, quarantined: 0, otherBlocked: 0, available: 4, allocationId: null, receiptId: null }))
  vi.stubGlobal('fetch', fetcher)
  await getInventoryCommandOutcome('org', 'store', 'RESERVE', 'same-request', new AbortController().signal)
  expect(new URL((fetcher.mock.calls[0][0] as Request).url).pathname).toContain('/inventory/commands/RESERVE/same-request')
})

it('submits one versioned physical receipt document through the CSRF protected API', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ token: 'csrf-document' })).mockResolvedValueOnce(new Response(JSON.stringify({
    receiptId: 'receipt', movementId: 'movement', lines: [{ documentLineReference: 'line-a', positionId: 'position', version: 1,
      onHand: 4, reserved: 0, quarantined: 0, otherBlocked: 0, available: 4, allocationId: null }],
  }), { status: 201, headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', fetcher)
  const body = { requestId: 'receipt-document-request', supplierKey: 'supplier', documentType: 'DELIVERY_NOTE',
    documentReference: 'DN-20', physicallyReceivedAt: '2026-11-12T10:00:00Z', reason: 'Physical delivery checked',
    evidenceReference: 'supplier-document:20', lines: [{ documentLineReference: 'line-a', locationId: 'location',
      productId: 'product', lotId: 'lot', quantity: 4, expectedVersion: 0 }] }
  const response = await receiveInventoryGoodsDocument('org', 'store', body, new AbortController().signal)
  expect(response.created).toBe(true)
  expect(response.result.receiptId).toBe('receipt')
  expect(new URL((fetcher.mock.calls[1][0] as Request).url).pathname).toContain('/inventory/receipt-documents')
  expect(await (fetcher.mock.calls[1][0] as Request).json()).toEqual(body)
  expect((fetcher.mock.calls[1][0] as Request).headers.get('X-CSRF-TOKEN')).toBe('csrf-document')
})

it('recovers the complete multi-line receipt result by its original command key', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ receiptId: 'receipt', movementId: 'movement', lines: [] }))
  vi.stubGlobal('fetch', fetcher)
  await getInventoryReceiptDocumentOutcome('org', 'store', 'receipt-request', new AbortController().signal)
  expect(new URL((fetcher.mock.calls[0][0] as Request).url).pathname)
    .toContain('/inventory/receipt-documents/commands/receipt-request')
})

it('submits an authorized projection repair with the captured version, evidence and request key', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ token: 'csrf-repair' })).mockResolvedValueOnce(json({ movementId: 'repair-movement', positionId: 'position', positionVersion: 7, onHand: 4, reserved: 0, quarantined: 0, otherBlocked: 0, available: 4, allocationId: null, receiptId: null }))
  vi.stubGlobal('fetch', fetcher)
  const body = { requestId: 'repair-request', expectedVersion: 6, reason: 'Rebuild verified projection', evidenceReference: 'count-sheet:123' }
  const result = await repairInventoryProjection('org', 'store', 'run', 'position', body, new AbortController().signal)
  expect(result.positionVersion).toBe(7)
  expect(new URL((fetcher.mock.calls[1][0] as Request).url).pathname).toContain('/inventory/reconciliations/run/positions/position/projection-repair')
  expect(await (fetcher.mock.calls[1][0] as Request).json()).toEqual(body)
  expect((fetcher.mock.calls[1][0] as Request).headers.get('X-CSRF-TOKEN')).toBe('csrf-repair')
})
