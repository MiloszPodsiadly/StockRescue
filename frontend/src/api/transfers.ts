import type { components, operations } from './schema'
import { api, ApiError, csrf, requestSignal } from './client'

export type Transfer = components['schemas']['TransferResponse']
export type TransferQueueItem = components['schemas']['TransferQueueItem']
export type TransferStatus = Transfer['status']
export type CreateTransfer = components['schemas']['CreateTransferRequest']
export type TransferDecision = components['schemas']['TransferDecisionRequest']
export type TransferPick = components['schemas']['TransferPickRequest']
export type TransferDispatch = components['schemas']['TransferDispatchRequest']
export type TransferReceive = components['schemas']['TransferReceiveRequest']
export type TransferDamage = components['schemas']['TransferDamageRequest']
export type TransferDiscrepancy = components['schemas']['TransferDiscrepancyRequest']
export type TransferReturn = components['schemas']['TransferReturnRequest']
export type TransferConfirmReturn = components['schemas']['TransferConfirmReturnRequest']
export type TransferCommand = components['schemas']['CommandResponse']
export type TransferRecovery = components['schemas']['RecoveryResponse']
export type TransferCommandIntent =
  | { operation: 'CREATE', transferId: null, body: CreateTransfer }
  | { operation: 'APPROVE' | 'REJECT' | 'CANCEL' | 'START_PICK' | 'CLOSE', transferId: string, body: TransferDecision }
  | { operation: 'PICK', transferId: string, body: TransferPick }
  | { operation: 'DISPATCH', transferId: string, body: TransferDispatch }
  | { operation: 'RECEIVE', transferId: string, body: TransferReceive }
  | { operation: 'CONFIRM_LOSS', transferId: string, body: TransferDamage }
  | { operation: 'OPEN_DISCREPANCY', transferId: string, body: TransferDiscrepancy }
  | { operation: 'REQUEST_RETURN', transferId: string, body: TransferReturn }
  | { operation: 'CONFIRM_RETURN', transferId: string, body: TransferConfirmReturn }

type JsonResponse<Operation extends keyof operations> = operations[Operation] extends {
  responses: { 200: { content: { 'application/json': infer Response } } }
} ? Response : never
type PageOf<T> = { items: T[], hasMore: boolean }
export type TransferCustodyMovement = JsonResponse<'getTransferCustodyTimeline'> extends PageOf<infer Item> ? Item : never
export type TransferInTransitLine = JsonResponse<'listTransfersInTransit'> extends PageOf<infer Item> ? Item : never
export type TransferDiscrepancyRecord = JsonResponse<'listTransferDiscrepancies'> extends PageOf<infer Item> ? Item : never

const base = '/api/v1/organizations/{organizationId}/transfers' as const

export async function listTransfers(organizationId: string, storeId: string, status: TransferStatus | '', signal: AbortSignal) {
  const { data, error, response } = await api.GET(base, { params: {
    path: { organizationId }, query: { storeId, ...(status ? { status } : {}), offset: 0, limit: 100 },
  }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function getTransfer(organizationId: string, transferId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET(`${base}/{transferId}`, { params: { path: { organizationId, transferId } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function listTransferCustody(organizationId: string, transferId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET(`${base}/{transferId}/custody`, { params: { path: { organizationId, transferId }, query: { offset: 0, limit: 100 } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function listTransfersInTransit(organizationId: string, storeId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET(`${base}/in-transit`, { params: { path: { organizationId }, query: { storeId, offset: 0, limit: 100 } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function listTransferDiscrepancies(organizationId: string, storeId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET(`${base}/discrepancies`, { params: { path: { organizationId }, query: { storeId, offset: 0, limit: 100 } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function recoverTransferCommand(organizationId: string, operation: string, requestId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET(`${base}/commands/{operation}/{requestId}`, { params: { path: { organizationId, operation, requestId } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function createTransfer(organizationId: string, body: CreateTransfer, signal: AbortSignal) {
  const result = await api.POST(base, { params: { path: { organizationId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!result.data) throw new ApiError(result.response, result.error)
  return result.data
}

export async function decideTransfer(organizationId: string, transferId: string,
  action: 'approve' | 'reject' | 'cancel' | 'start-pick' | 'close-discrepancy', body: TransferDecision, signal: AbortSignal) {
  const params = { path: { organizationId, transferId }, ...await csrf(signal) }
  const options = { params, body, signal: requestSignal(signal) }
  const result = action === 'approve' ? await api.POST(`${base}/{transferId}/approve`, options)
    : action === 'reject' ? await api.POST(`${base}/{transferId}/reject`, options)
      : action === 'cancel' ? await api.POST(`${base}/{transferId}/cancel`, options)
        : action === 'start-pick' ? await api.POST(`${base}/{transferId}/start-pick`, options)
          : await api.POST(`${base}/{transferId}/close-discrepancy`, options)
  signal.throwIfAborted()
  if (!result.data) throw new ApiError(result.response, result.error)
  return result.data
}

export async function recordTransferPick(organizationId: string, transferId: string, body: TransferPick, signal: AbortSignal) {
  const result = await api.POST(`${base}/{transferId}/pick`, { params: { path: { organizationId, transferId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!result.data) throw new ApiError(result.response, result.error)
  return result.data
}

export async function dispatchTransfer(organizationId: string, transferId: string, body: TransferDispatch, signal: AbortSignal) {
  const result = await api.POST(`${base}/{transferId}/dispatch`, { params: { path: { organizationId, transferId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!result.data) throw new ApiError(result.response, result.error)
  return result.data
}

export async function receiveTransfer(organizationId: string, transferId: string, body: TransferReceive, signal: AbortSignal) {
  const result = await api.POST(`${base}/{transferId}/receive`, { params: { path: { organizationId, transferId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!result.data) throw new ApiError(result.response, result.error)
  return result.data
}

export async function recordTransferDamage(organizationId: string, transferId: string, body: TransferDamage, signal: AbortSignal) {
  const result = await api.POST(`${base}/{transferId}/damage`, { params: { path: { organizationId, transferId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!result.data) throw new ApiError(result.response, result.error)
  return result.data
}

export async function openTransferDiscrepancy(organizationId: string, transferId: string, body: TransferDiscrepancy, signal: AbortSignal) {
  const result = await api.POST(`${base}/{transferId}/open-discrepancy`, { params: { path: { organizationId, transferId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!result.data) throw new ApiError(result.response, result.error)
  return result.data
}

export async function requestTransferReturn(organizationId: string, transferId: string, body: TransferReturn, signal: AbortSignal) {
  const result = await api.POST(`${base}/{transferId}/request-return`, { params: { path: { organizationId, transferId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!result.data) throw new ApiError(result.response, result.error)
  return result.data
}

export async function confirmTransferReturn(organizationId: string, transferId: string, body: TransferConfirmReturn, signal: AbortSignal) {
  const result = await api.POST(`${base}/{transferId}/confirm-return`, { params: { path: { organizationId, transferId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!result.data) throw new ApiError(result.response, result.error)
  return result.data
}

export async function executeTransferCommand(organizationId: string, intent: TransferCommandIntent, signal: AbortSignal): Promise<TransferCommand> {
  if (intent.operation === 'CREATE') return createTransfer(organizationId, intent.body, signal)
  if (intent.operation === 'APPROVE' || intent.operation === 'REJECT' || intent.operation === 'CANCEL'
    || intent.operation === 'START_PICK' || intent.operation === 'CLOSE') {
    const action = intent.operation === 'START_PICK' ? 'start-pick' : intent.operation === 'CLOSE' ? 'close-discrepancy'
      : intent.operation.toLowerCase() as 'approve' | 'reject' | 'cancel'
    return decideTransfer(organizationId, intent.transferId, action, intent.body, signal)
  }
  if (intent.operation === 'PICK') return recordTransferPick(organizationId, intent.transferId, intent.body, signal)
  if (intent.operation === 'DISPATCH') return dispatchTransfer(organizationId, intent.transferId, intent.body, signal)
  if (intent.operation === 'RECEIVE') return receiveTransfer(organizationId, intent.transferId, intent.body, signal)
  if (intent.operation === 'CONFIRM_LOSS') return recordTransferDamage(organizationId, intent.transferId, intent.body, signal)
  if (intent.operation === 'OPEN_DISCREPANCY') return openTransferDiscrepancy(organizationId, intent.transferId, intent.body, signal)
  if (intent.operation === 'REQUEST_RETURN') return requestTransferReturn(organizationId, intent.transferId, intent.body, signal)
  if (intent.operation === 'CONFIRM_RETURN') return confirmTransferReturn(organizationId, intent.transferId, intent.body, signal)
  throw new Error('Unsupported transfer command')
}
