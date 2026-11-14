import type { components, operations } from './schema'
import { api, ApiError, csrf, requestSignal } from './client'

export type StockPosition = components['schemas']['StockSnapshotResponse']
export type AvailableByLot = components['schemas']['AvailableByLotResponse']
export type InventoryMovement = components['schemas']['MovementResponse']
export type InventoryAllocation = components['schemas']['AllocationResponse']
export type InventoryCommand = components['schemas']['InventoryCommandResponse']
export type InventoryReceipt = components['schemas']['ReceiptRequest']
export type InventoryReceiptDocument = components['schemas']['ReceiptDocumentRequest']
export type InventoryReceiptDocumentResult = components['schemas']['ReceiptDocumentResponse']
export type StockCommand = components['schemas']['StockCommandRequest']
export type StockWriteOff = components['schemas']['WriteOffRequest']
export type Reconciliation = components['schemas']['ReconciliationResponse']
export type ReconciliationFinding = components['schemas']['FindingResponse']
export type ProjectionRepair = components['schemas']['ProjectionRepairRequest']
export type InventoryOperation = NonNullable<operations['getInventoryCommandOutcome']['parameters']['path']>['operation']

const base = '/api/v1/organizations/{organizationId}/stores/{storeId}/inventory' as const

export async function listInventoryPositions(organizationId: string, storeId: string, filters: { productId?: string, lotId?: string, locationId?: string, offset?: number, limit?: number }, signal: AbortSignal) {
  const { data, error, response } = await api.GET(`${base}/positions`, { params: { path: { organizationId, storeId }, query: filters }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function listInventoryAvailableByLot(organizationId: string, storeId: string, filters: { productId?: string, lotId?: string, locationId?: string, offset?: number, limit?: number }, signal: AbortSignal) {
  const { data, error, response } = await api.GET(`${base}/available-by-lot`, { params: { path: { organizationId, storeId }, query: filters }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function getInventorySnapshot(organizationId: string, storeId: string, positionId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET(`${base}/positions/{positionId}`, { params: { path: { organizationId, storeId, positionId } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function listInventoryMovements(organizationId: string, storeId: string, positionId: string, afterVersion: number, signal: AbortSignal) {
  const { data, error, response } = await api.GET(`${base}/positions/{positionId}/movements`, { params: { path: { organizationId, storeId, positionId }, query: { afterVersion, limit: 100 } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function listInventoryAllocations(organizationId: string, storeId: string, positionId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET(`${base}/positions/{positionId}/allocations`, { params: { path: { organizationId, storeId, positionId }, query: { offset: 0, limit: 100 } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function receiveInventoryGoods(organizationId: string, storeId: string, body: InventoryReceipt, signal: AbortSignal) {
  const { data, error, response } = await api.POST(`${base}/receipts`, { params: { path: { organizationId, storeId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}

export async function receiveInventoryGoodsDocument(organizationId: string, storeId: string, body: InventoryReceiptDocument, signal: AbortSignal) {
  const { data, error, response } = await api.POST(`${base}/receipt-documents`, { params: { path: { organizationId, storeId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return { result: data, created: response.status === 201 }
}

export async function getInventoryReceiptDocumentOutcome(organizationId: string, storeId: string, requestId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET(`${base}/receipt-documents/commands/{requestId}`, { params: { path: { organizationId, storeId, requestId } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function runInventoryCommand(organizationId: string, storeId: string, positionId: string, action: 'reservations' | 'adjustments' | 'quarantines' | 'holds' | 'sales', body: StockCommand, signal: AbortSignal) {
  const { data, error, response } = await api.POST(`${base}/positions/{positionId}/${action}`, { params: { path: { organizationId, storeId, positionId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}

export async function releaseInventoryAllocation(organizationId: string, storeId: string, positionId: string, allocationId: string, kind: 'reservations' | 'holds', body: StockCommand, signal: AbortSignal) {
  const { data, error, response } = kind === 'reservations'
    ? await api.POST(`${base}/positions/{positionId}/reservations/{allocationId}/release`, { params: { path: { organizationId, storeId, positionId, allocationId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
    : await api.POST(`${base}/positions/{positionId}/holds/{allocationId}/release`, { params: { path: { organizationId, storeId, positionId, allocationId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}

export async function writeOffInventoryStock(organizationId: string, storeId: string, positionId: string, body: StockWriteOff, signal: AbortSignal) {
  const { data, error, response } = await api.POST(`${base}/positions/{positionId}/write-offs`, { params: { path: { organizationId, storeId, positionId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}

export async function getInventoryCommandOutcome(organizationId: string, storeId: string, operation: InventoryOperation, requestId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET(`${base}/commands/{operation}/{requestId}`, { params: { path: { organizationId, storeId, operation, requestId } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function listInventoryReconciliations(organizationId: string, storeId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET(`${base}/reconciliations`, { params: { path: { organizationId, storeId }, query: { offset: 0, limit: 25 } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function reconcileInventory(organizationId: string, storeId: string, signal: AbortSignal) {
  const { data, error, response } = await api.POST(`${base}/reconciliations`, { params: { path: { organizationId, storeId }, ...await csrf(signal) }, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}

export async function listInventoryFindings(organizationId: string, storeId: string, runId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET(`${base}/reconciliations/{runId}/findings`, { params: { path: { organizationId, storeId, runId }, query: { offset: 0, limit: 100 } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function repairInventoryProjection(organizationId: string, storeId: string, runId: string, positionId: string, body: ProjectionRepair, signal: AbortSignal) {
  const { data, error, response } = await api.POST(`${base}/reconciliations/{runId}/positions/{positionId}/projection-repair`, { params: { path: { organizationId, storeId, runId, positionId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}
