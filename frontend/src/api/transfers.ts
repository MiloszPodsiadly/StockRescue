import type { components, operations } from './schema'
import { api, ApiError, csrf, requestSignal } from './client'

export type Transfer = components['schemas']['TransferResponse']
export type TransferQueueItem = components['schemas']['TransferQueueItem']
export type TransferStatus = Transfer['status']
export type CreateTransfer = components['schemas']['CreateTransferRequest']
export type TransferDecision = components['schemas']['TransferDecisionRequest']
export type TransferCancel = components['schemas']['TransferCancelRequest']
export type TransferPick = components['schemas']['TransferPickRequest']
export type TransferDispatch = components['schemas']['TransferDispatchRequest']
export type TransferReceive = components['schemas']['TransferReceiveRequest']
export type TransferDamage = components['schemas']['TransferDamageRequest']
export type TransferDiscrepancy = components['schemas']['TransferDiscrepancyRequest']
export type TransferReturn = components['schemas']['TransferReturnRequest']
export type TransferConfirmReturn = components['schemas']['TransferConfirmReturnRequest']
export type TransferCommand = components['schemas']['CommandResponse']
export type TransferRecovery = components['schemas']['RecoveryResponse']
export type TransferCommandIntentSummary = components['schemas']['TransferCommandIntentSummary']
export type TransferCommandIntentInspection = components['schemas']['TransferCommandIntentInspection']
export const TRANSFER_COMMAND_OPERATIONS = [
  'CREATE', 'APPROVE', 'REJECT', 'CANCEL', 'START_PICK', 'PICK', 'DISPATCH', 'RECEIVE',
  'CONFIRM_LOSS', 'OPEN_DISCREPANCY', 'REQUEST_RETURN', 'CONFIRM_RETURN', 'CLOSE',
] as const
export type TransferCommandIntent =
  | { operation: 'CREATE', transferId: null, body: CreateTransfer }
  | { operation: 'APPROVE' | 'REJECT' | 'START_PICK' | 'CLOSE', transferId: string, body: TransferDecision }
  | { operation: 'CANCEL', transferId: string, body: TransferCancel }
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
const intentsBase = '/api/v1/organizations/{organizationId}/transfer-command-intents' as const

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function isTransferCommandIntent(value: unknown): value is TransferCommandIntent {
  if (!isRecord(value) || !isRecord(value.body) || typeof value.body.requestId !== 'string'
    || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value.body.requestId)
    || typeof value.operation !== 'string' || !TRANSFER_COMMAND_OPERATIONS.includes(value.operation as typeof TRANSFER_COMMAND_OPERATIONS[number])) return false
  return value.operation === 'CREATE' ? value.transferId === null : typeof value.transferId === 'string'
}

export async function listPendingTransferCommandIntents(organizationId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET(intentsBase, {
    params: { path: { organizationId } }, signal: requestSignal(signal),
  })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function prepareTransferCommandIntent(organizationId: string, intent: TransferCommandIntent, signal: AbortSignal) {
  const { data, error, response } = await api.POST(intentsBase, {
    params: { path: { organizationId }, ...await csrf(signal) },
    body: { prepareId: crypto.randomUUID(), operation: intent.operation, transferId: intent.transferId ?? undefined, payload: intent.body },
    signal: requestSignal(signal),
  })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function inspectTransferCommandIntent(organizationId: string, intentId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET(`${intentsBase}/{intentId}`, {
    params: { path: { organizationId, intentId } }, signal: requestSignal(signal),
  })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function markTransferCommandIntentSubmitted(organizationId: string, intentId: string, signal: AbortSignal) {
  const { data, error, response } = await api.POST(`${intentsBase}/{intentId}/submitted`, {
    params: { path: { organizationId, intentId }, ...await csrf(signal) }, signal: requestSignal(signal),
  })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function acknowledgeTransferCommandIntent(organizationId: string, intentId: string, signal: AbortSignal) {
  const { data, error, response } = await api.POST(`${intentsBase}/{intentId}/acknowledge`, {
    params: { path: { organizationId, intentId }, ...await csrf(signal) }, signal: requestSignal(signal),
  })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function resolveFailedTransferCommandIntent(organizationId: string, intentId: string,
  resolution: 'REJECTED' | 'CONFLICTED', signal: AbortSignal) {
  const { data, error, response } = await api.POST(`${intentsBase}/{intentId}/resolve-failure`, {
    params: { path: { organizationId, intentId }, ...await csrf(signal) }, body: { resolution }, signal: requestSignal(signal),
  })
  if (!data) throw new ApiError(response, error)
  return data
}

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
  action: 'approve' | 'reject' | 'start-pick' | 'close-discrepancy', body: TransferDecision, signal: AbortSignal) {
  const params = { path: { organizationId, transferId }, ...await csrf(signal) }
  const options = { params, body, signal: requestSignal(signal) }
  const result = action === 'approve' ? await api.POST(`${base}/{transferId}/approve`, options)
    : action === 'reject' ? await api.POST(`${base}/{transferId}/reject`, options)
      : action === 'start-pick' ? await api.POST(`${base}/{transferId}/start-pick`, options)
        : await api.POST(`${base}/{transferId}/close-discrepancy`, options)
  signal.throwIfAborted()
  if (!result.data) throw new ApiError(result.response, result.error)
  return result.data
}

async function cancelTransfer(organizationId: string, transferId: string, body: TransferCancel, signal: AbortSignal) {
  const result = await api.POST(`${base}/{transferId}/cancel`, {
    params: { path: { organizationId, transferId }, ...await csrf(signal) }, body, signal: requestSignal(signal),
  })
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
  if (intent.operation === 'CANCEL') return cancelTransfer(organizationId, intent.transferId, intent.body, signal)
  if (intent.operation === 'APPROVE' || intent.operation === 'REJECT'
    || intent.operation === 'START_PICK' || intent.operation === 'CLOSE') {
    const action = intent.operation === 'START_PICK' ? 'start-pick' : intent.operation === 'CLOSE' ? 'close-discrepancy'
      : intent.operation.toLowerCase() as 'approve' | 'reject'
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
