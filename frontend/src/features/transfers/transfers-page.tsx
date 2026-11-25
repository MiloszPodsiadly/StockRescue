import { useEffect, useMemo, useRef, useState } from 'react'
import { useInfiniteQuery, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from '@tanstack/react-router'
import { AppLink } from '@/components/app-link'
import { Button } from '@/components/ui/button'
import { Field, inputClass } from '@/components/form-field'
import { StateMessage } from '@/components/state-message'
import { ApiError, errorMessage } from '@/api/client'
import { getStore, listStores } from '@/api/stores'
import type { Store } from '@/api/stores'
import { getInventorySnapshot, listInventoryAvailableByLot, listInventoryPositions } from '@/api/inventory'
import type { StockPosition } from '@/api/inventory'
import { getStorageLocation, listStorageLocations } from '@/api/storage-locations'
import { getLot } from '@/api/lots'
import { getProduct } from '@/api/products'
import { lookupLane } from '@/api/configuration'
import {
  acknowledgeTransferCommandIntent, executeTransferCommand, getTransfer, inspectTransferCommandIntent,
  getTransferCapabilities, isTransferCommandIntent, listActiveTransferReturns, listPendingTransferCommandIntents, listTransferCustody, listTransferDiscrepancies,
  getTransferSettlementPositions, listHistoricalTransfers, listTransferInTransit, listTransferScopedDiscrepancies, listTransfers, listTransfersInTransit, markTransferCommandIntentSubmitted, prepareTransferCommandIntent,
  resolveFailedTransferCommandIntent,
} from '@/api/transfers'
import type {
  Transfer, TransferCommandIntent, TransferCommandIntentInspection, TransferCommandIntentSummary, TransferCustodyMovement, TransferDiscrepancyRecord,
  HistoricalTransferQueueItem, TransferInTransitLine, TransferQueueItem, TransferStatus,
} from '@/api/transfers'
import { OrganizationPage } from '../organizations/organization-page'
import { useOrganization } from '../organizations/organization-context'
import { useTenantKey, useTenantRequests } from '../organizations/tenant-requests'
import { formatTransferAge, transferStatusLabel, transferStatusOptions } from './transfer-format'
import { loadStoreCursorPages, TransferRequestScheduler } from './transfer-pagination'
import { reconcileTransferSummaries } from './transfer-summary-reconciliation'
import type { StoreCursorStates } from './transfer-pagination'

type DraftTransferLine = { id: string, sourcePositionId: string, destinationLocationId: string, quantity: string }
type PendingReturnLine = { lineId: string, requestId: string, requestReference: string, requestedQuantity: number, settledQuantity: number, unsettledQuantity: number }
type RecoveryLock = { scopeKey: string, intentId: string }
type RecoveryView = {
  scopeKey: string
  intentId: string
  kind: 'checking' | 'inspection' | 'terminal' | 'unknown' | 'acknowledged'
  inspection?: TransferCommandIntentInspection
  executionStatus?: 'REJECTED' | 'CONFLICTED'
  redacted?: boolean
  notice?: string
  error?: string
}
type RecoveryMessage = { scopeKey: string, intentId?: string, notice?: string, error?: string, complete?: boolean }
type PendingReadState = { scopeKey: string, token: number, status: 'loading' | 'success' | 'error' }
type PendingReadResult = {
  scopeKey: string
  token: number
  page: Awaited<ReturnType<typeof listPendingTransferCommandIntents>>
}
type RecoveryOperation = { scopeKey: string, token: number, intentId?: string, signal: AbortSignal }
type TransferCommandPhase = 'mark-submitted' | 'execute'
type AcknowledgementResult =
  | { kind: 'acknowledged', redacted: boolean }
  | { kind: 'committed-awaiting-acknowledgement', inspection: TransferCommandIntentInspection }
  | { kind: 'terminal', inspection: TransferCommandIntentInspection }
  | { kind: 'unknown', redacted: boolean }
const newDraftTransferLine = (): DraftTransferLine => ({ id: crypto.randomUUID(), sourcePositionId: '', destinationLocationId: '', quantity: '' })

class SupersededPendingReadError extends Error {
  constructor() { super('Pending transfer-intent read was superseded.') }
}

class TransferCommandPhaseError extends Error {
  constructor(readonly phase: TransferCommandPhase, readonly original: unknown) {
    super(`Transfer command ${phase} failed.`)
  }
}

function discrepancyStatus(item: TransferDiscrepancyRecord) {
  if (['PENDING', 'PARTIALLY_FULFILLED', 'FULFILLED', 'SUPERSEDED'].includes(item.resolutionScope))
    return `Return ${item.resolutionScope.toLowerCase().replaceAll('_', ' ')}`
  if (item.transferClosed) return 'Transfer closed'
  if (item.lineSettled) return 'Line settled · transfer open'
  return 'Open'
}

function uniqueBy<T>(values: T[], key: (value: T) => string): T[] {
  return [...new Map(values.map(value => [key(value), value])).values()]
}

function transferAccessDenied(error: unknown) {
  return error instanceof ApiError && [401, 403, 404].includes(error.status)
}

function definitiveTransferExecutionResolution(error: unknown): 'REJECTED' | 'CONFLICTED' | null {
  if (!(error instanceof ApiError)) return null
  if ((error.status === 400 || error.status === 422)
    && (error.code === 'INVALID_REQUEST' || error.code === 'LOT_NOT_ELIGIBLE')) return 'REJECTED'
  if (error.status === 409 && [
    'INVENTORY_CONFLICT', 'INSUFFICIENT_STOCK', 'INVENTORY_INACTIVE', 'INVENTORY_FROZEN',
    'INVENTORY_NOT_INITIALIZED', 'IDEMPOTENCY_CONFLICT', 'RECEIPT_ALREADY_RECORDED',
    'ALLOCATION_UNAVAILABLE', 'CONFIGURATION_CONFLICT', 'CONFIGURATION_INACTIVE',
    'INCOMPATIBLE_ZONES',
  ].includes(error.code)) return 'CONFLICTED'
  return null
}

function definitivePrepareRejection(error: unknown) {
  return error instanceof ApiError
    && ((error.status === 400 || error.status === 422) && error.code === 'INVALID_REQUEST'
      || error.status === 409 && ['INVENTORY_CONFLICT', 'IDEMPOTENCY_CONFLICT'].includes(error.code))
}

function terminalIntentStatus(status: TransferCommandIntentInspection['executionStatus']): status is 'REJECTED' | 'CONFLICTED' {
  return status === 'REJECTED' || status === 'CONFLICTED'
}

function inspectionIsConsistent(inspection: TransferCommandIntentInspection) {
  if (inspection.redacted) {
    return !inspection.operation && !inspection.transferId && !inspection.requestId
      && !inspection.payload && !inspection.committedOutcome
  }
  if (typeof inspection.operation !== 'string' || typeof inspection.requestId !== 'string'
    || !inspection.payload || typeof inspection.payload !== 'object') return false
  const intent = { operation: inspection.operation, transferId: inspection.transferId ?? null, body: inspection.payload }
  if (!isTransferCommandIntent(intent) || intent.body.requestId !== inspection.requestId) return false
  if (inspection.executionStatus === 'COMMITTED_AWAITING_ACKNOWLEDGEMENT' || inspection.executionStatus === 'ACKNOWLEDGED') {
    return Boolean(inspection.committedOutcome)
  }
  if (inspection.executionStatus === 'REJECTED' || inspection.executionStatus === 'CONFLICTED') {
    return inspection.committedOutcome == null
  }
  return inspection.committedOutcome == null
}

function inspectionMatchesSummary(inspection: TransferCommandIntentInspection, summary: TransferCommandIntentSummary) {
  return inspection.id === summary.id && inspection.state === summary.state
    && inspection.redacted === summary.redacted
    && (summary.redacted || (inspection.operation === summary.operation
      && inspection.requestId === summary.requestId && inspection.transferId === summary.transferId))
}

function outstanding(line: Transfer['lines'][number]) { return line.quantities.outstanding ?? 0 }

async function hasAnyTransferReadAccess(organizationId: string, signal: AbortSignal): Promise<boolean | null> {
  const pageSize = 100
  for (let offset = 0; offset <= 10_000; offset += pageSize) {
    const page = await listStores(organizationId, 'ACTIVE', '', offset, signal, pageSize)
    if (page.items.some(store => store.active && store.allowedActions.includes('READ_TRANSFERS'))) return true
    if (!page.hasMore) return false
  }
  return null
}

export function TransfersPage() {
  const { organizationId, transferId } = useParams({ strict: false })
  return <OrganizationPage organizationId={organizationId}>{organizationId && <TransferWorkspace key={organizationId} organizationId={organizationId} transferId={transferId} />}</OrganizationPage>
}

export function HistoricalTransfersPage() {
  const { organizationId } = useParams({ strict: false })
  return <OrganizationPage organizationId={organizationId}>{organizationId && <TransferWorkspace key={organizationId} organizationId={organizationId} historicalOnly />}</OrganizationPage>
}

function TransferWorkspace({ organizationId, transferId, historicalOnly = false }: { organizationId: string, transferId?: string, historicalOnly?: boolean }) {
  const client = useQueryClient()
  const navigate = useNavigate()
  const scope = useTenantKey()
  const tenantSignal = useTenantRequests()
  const scopeKey = JSON.stringify(scope)
  const scopeKeyRef = useRef(scopeKey)
  scopeKeyRef.current = scopeKey
  const pendingReadToken = useRef(0)
  const operationToken = useRef(0)
  const transferRequestScheduler = useMemo(() => new TransferRequestScheduler(8), [])
  const organization = useOrganization()
  const [status, setStatus] = useState<TransferStatus | ''>('')
  const [storeFilter, setStoreFilter] = useState('')
  const [storeSearch, setStoreSearch] = useState('')
  const [transferAccessEpoch, setTransferAccessEpoch] = useState(0)
  const transferAccessRevalidation = useRef(false)
  const transferAccessDenialHandled = useRef(false)
  const [commandBusyState, setCommandBusyState] = useState<{ scopeKey: string, token: number } | null>(null)
  const [recoveryLockState, setRecoveryLockState] = useState<RecoveryLock | null>(null)
  const [recoveryViewState, setRecoveryViewState] = useState<RecoveryView | null>(null)
  const [commandMessageState, setCommandMessageState] = useState<RecoveryMessage | null>(null)
  const [pendingReadState, setPendingReadState] = useState<PendingReadState | null>(null)
  const pendingReadStateRef = useRef<PendingReadState | null>(null)
  function recordPendingReadState(next: PendingReadState) {
    pendingReadStateRef.current = next
    setPendingReadState(next)
    if (next.status === 'error') {
      setRecoveryViewState(current => current?.scopeKey === next.scopeKey ? null : current)
    }
  }
  const pendingIntentKey = useMemo(() => [...scope, 'transfer-command-intents', 'pending'] as const, [scope])
  const pendingIntents = useQuery({
    queryKey: pendingIntentKey,
    queryFn: async ({ signal }) => {
      const token = ++pendingReadToken.current
      const requestedScope = scopeKey
      recordPendingReadState({ scopeKey: requestedScope, token, status: 'loading' })
      try {
        const page = await listPendingTransferCommandIntents(organizationId, signal)
        signal.throwIfAborted()
        if (scopeKeyRef.current !== requestedScope || pendingReadToken.current !== token) throw new SupersededPendingReadError()
        recordPendingReadState({ scopeKey: requestedScope, token, status: 'success' })
        return { scopeKey: requestedScope, token, page }
      } catch (error) {
        if (scopeKeyRef.current === requestedScope && pendingReadToken.current === token) {
          recordPendingReadState({ scopeKey: requestedScope, token, status: 'error' })
        }
        throw error
      }
    },
    enabled: !historicalOnly, retry: false, refetchOnWindowFocus: true, staleTime: 0, gcTime: 0,
  })
  const pendingReadStatus = pendingReadState?.scopeKey === scopeKey ? pendingReadState.status : null
  const pendingReadReady = pendingReadStatus === 'success' && pendingReadState?.token === pendingReadToken.current
    && pendingIntents.data?.scopeKey === scopeKey && pendingIntents.data.token === pendingReadState.token
    && pendingIntents.isSuccess
    && !pendingIntents.isError && !pendingIntents.isFetching && !pendingIntents.isPaused
  const pendingSummary = pendingReadReady ? pendingIntents.data.page.items[0] ?? null : null
  const localRecoveryLock = recoveryLockState?.scopeKey === scopeKey ? recoveryLockState : null
  const activeIntentId = localRecoveryLock && pendingSummary && localRecoveryLock.intentId !== pendingSummary.id
    ? pendingSummary.id : localRecoveryLock?.intentId ?? pendingSummary?.id ?? null
  const activeSummary = pendingSummary?.id === activeIntentId ? pendingSummary : null
  const recoveryView = recoveryViewState?.scopeKey === scopeKey && recoveryViewState.intentId === activeIntentId ? recoveryViewState : null
  const recoveryInspection = recoveryView?.kind === 'inspection' ? recoveryView.inspection : null
  const authorizedInspection = recoveryInspection && activeSummary
    && inspectionIsConsistent(recoveryInspection) && inspectionMatchesSummary(recoveryInspection, activeSummary)
    ? recoveryInspection : null
  const recoveryRedacted = Boolean(activeSummary?.redacted || recoveryInspection?.redacted || recoveryView?.redacted)
  const activeIntentIdRef = useRef(activeIntentId)
  activeIntentIdRef.current = activeIntentId
  const commandBusy = commandBusyState?.scopeKey === scopeKey && commandBusyState.token === operationToken.current
  const commandMessage = commandMessageState?.scopeKey === scopeKey
    && (!activeIntentId || commandMessageState.intentId === activeIntentId) ? commandMessageState : null
  const pendingReadFailed = pendingReadStatus === 'error' || pendingIntents.isError
  const commandBlocked = commandBusy || Boolean(activeIntentId) || !pendingReadReady
  const stores = useInfiniteQuery({
    queryKey: [...scope, 'stores', 'transfer-workspace', storeSearch, transferAccessEpoch], enabled: !transferId && !historicalOnly, initialPageParam: 0,
    queryFn: ({ signal, pageParam }) => listStores(organizationId, 'ACTIVE', storeSearch, pageParam, signal),
    getNextPageParam: (page, pages) => page.hasMore ? pages.length * 25 : undefined, refetchOnWindowFocus: true, staleTime: 0,
  })
  const storeItems = (stores.data?.pages ?? []).flatMap(page => page.items)
  const readableStores = uniqueBy(storeItems.filter(store => store.active && store.allowedActions.includes('READ_TRANSFERS')), store => store.id)
  const queueStoreIds = (storeFilter ? readableStores.filter(store => store.id === storeFilter) : readableStores).map(store => store.id)
  const transferReadAccess = useQuery({
    queryKey: [...scope, 'stores', 'transfer-read-access', transferAccessEpoch], enabled: !transferId && !historicalOnly && readableStores.length === 0,
    queryFn: ({ signal }) => hasAnyTransferReadAccess(organizationId, signal), retry: false, refetchOnWindowFocus: true, staleTime: 0,
  })
  const queue = useInfiniteQuery({
    queryKey: [...scope, 'transfers', 'queue', storeSearch, status, queueStoreIds, transferAccessEpoch], enabled: !transferId && !historicalOnly && queueStoreIds.length > 0,
    initialPageParam: {} as StoreCursorStates,
    queryFn: ({ signal, pageParam }) => loadStoreCursorPages(queueStoreIds, pageParam,
      (storeId, cursor, requestSignal) => listTransfers(organizationId, storeId, status, requestSignal ?? signal, cursor, 50),
      transferRequestScheduler, signal),
    getNextPageParam: page => page.hasMore ? page.nextCursors : undefined, refetchOnWindowFocus: true, staleTime: 0,
  })
  const transit = useInfiniteQuery({
    queryKey: [...scope, 'transfers', 'in-transit', storeSearch, queueStoreIds, transferAccessEpoch], enabled: !transferId && !historicalOnly && queueStoreIds.length > 0,
    initialPageParam: {} as StoreCursorStates,
    queryFn: ({ signal, pageParam }) => loadStoreCursorPages(queueStoreIds, pageParam,
      (storeId, cursor, requestSignal) => listTransfersInTransit(organizationId, storeId, requestSignal ?? signal, cursor, 50),
      transferRequestScheduler, signal),
    getNextPageParam: page => page.hasMore ? page.nextCursors : undefined, refetchOnWindowFocus: true, staleTime: 0,
  })
  const evidence = useInfiniteQuery({
    queryKey: [...scope, 'transfers', 'evidence', storeSearch, queueStoreIds, transferAccessEpoch], enabled: !transferId && !historicalOnly && queueStoreIds.length > 0,
    initialPageParam: {} as StoreCursorStates,
    queryFn: ({ signal, pageParam }) => loadStoreCursorPages(queueStoreIds, pageParam,
      (storeId, cursor, requestSignal) => listTransferDiscrepancies(organizationId, storeId, requestSignal ?? signal, cursor, 50),
      transferRequestScheduler, signal),
    getNextPageParam: page => page.hasMore ? page.nextCursors : undefined, refetchOnWindowFocus: true, staleTime: 0,
  })
  const queueItems = reconcileTransferSummaries((queue.data?.pages ?? []).flatMap(page => page.items))
  const transitItems = uniqueBy((transit.data?.pages ?? []).flatMap(page => page.items), item => `${item.transferId}:${item.lineId}`)
  const evidenceItems = uniqueBy((evidence.data?.pages ?? []).flatMap(page => page.items), item => item.id)
  const historical = useInfiniteQuery({
    queryKey: [...scope, 'transfers', 'historical', status], enabled: historicalOnly && organization.role === 'ORG_ADMIN',
    initialPageParam: null as string | null,
    queryFn: ({ signal, pageParam }) => listHistoricalTransfers(organizationId, status, signal, pageParam, 50),
    getNextPageParam: page => page.hasMore ? page.nextCursor ?? undefined : undefined, refetchOnWindowFocus: true, staleTime: 0,
  })
  const historicalItems = (historical.data?.pages ?? []).flatMap(page => page.items)
  const detail = useQuery({ queryKey: [...scope, 'transfers', 'detail', transferId], enabled: Boolean(transferId), queryFn: ({ signal }) => getTransfer(organizationId, transferId!, signal), refetchOnWindowFocus: true, staleTime: 0 })

  const transferAccessDeniedNow = !historicalOnly && (
    transferAccessDenied(stores.error) || transferAccessDenied(transferReadAccess.error)
    || transferAccessDenied(queue.error) || transferAccessDenied(transit.error) || transferAccessDenied(evidence.error)
  )
  const transferPermissionReadComplete = stores.isSuccess && !stores.isFetching && !stores.isPaused
    && (readableStores.length === 0
      ? transferReadAccess.isSuccess && !transferReadAccess.isFetching && !transferReadAccess.isPaused
      : queueStoreIds.length === 0 || [queue, transit, evidence].every(query =>
        query.isSuccess && !query.isFetching && !query.isPaused))
  useEffect(() => {
    transferAccessDenialHandled.current = false
    transferAccessRevalidation.current = false
    const unsubscribe = client.getQueryCache().subscribe(event => {
      if (event.type !== 'updated' || event.action.type !== 'error') return
      if (scopeKeyRef.current !== scopeKey) return
      const queryKey = event.query.queryKey
      if (JSON.stringify(queryKey.slice(0, scope.length)) !== scopeKey) return
      const area = queryKey[scope.length]
      const resource = queryKey[scope.length + 1]
      const transferRead = area === 'stores' && ['transfer-workspace', 'transfer-read-access'].includes(String(resource))
        || area === 'transfers' && ['queue', 'in-transit', 'evidence'].includes(String(resource))
      if (!transferRead || !transferAccessDenied(event.query.state.error) || transferAccessDenialHandled.current) return
      transferAccessDenialHandled.current = true
      transferAccessRevalidation.current = true
      setTransferAccessEpoch(current => current + 1)
    })
    return unsubscribe
  }, [client, scope, scopeKey])
  useEffect(() => {
    if (!transferAccessDeniedNow && transferPermissionReadComplete) {
      transferAccessRevalidation.current = false
      transferAccessDenialHandled.current = false
    }
  }, [transferAccessDeniedNow, transferPermissionReadComplete])
  useEffect(() => {
    const expectedQueryKey = JSON.stringify(pendingIntentKey)
    const unsubscribe = client.getQueryCache().subscribe(event => {
      if (event.type !== 'updated' || JSON.stringify(event.query.queryKey) !== expectedQueryKey) return
      if (scopeKeyRef.current !== scopeKey) return
      const currentScope = scopeKey
      if (event.action.type === 'error') {
        const read = pendingReadStateRef.current
        if (read?.scopeKey !== currentScope || read.status !== 'error' || read.token !== pendingReadToken.current) return
        setRecoveryViewState(current => current?.scopeKey === currentScope ? null : current)
        return
      }
      if (event.action.type !== 'success') return
      const read = pendingReadStateRef.current
      const cached = client.getQueryData<PendingReadResult>(pendingIntentKey)
      if (read?.scopeKey !== currentScope || read.status !== 'success' || read.token !== pendingReadToken.current
        || cached?.scopeKey !== currentScope || cached.token !== read.token) return
      const summary = cached.page.items[0] ?? null
      setRecoveryLockState(current => current?.scopeKey === currentScope && summary && current.intentId !== summary.id
        ? { scopeKey: currentScope, intentId: summary.id } : current)
      setRecoveryViewState(current => {
        if (!current || current.scopeKey !== currentScope) return current
        if (summary && current.intentId !== summary.id) return null
        if (summary?.redacted) return current.kind === 'inspection' && current.inspection?.redacted ? current : null
        if (summary && current.kind === 'inspection' && current.inspection
          && (!inspectionIsConsistent(current.inspection) || !inspectionMatchesSummary(current.inspection, summary))) return null
        if (!summary && current.kind !== 'terminal' && current.kind !== 'acknowledged') return null
        return current
      })
      if (summary?.redacted) {
        setCommandMessageState(current => current?.scopeKey === currentScope && current.intentId === summary.id ? null : current)
      } else if (summary) {
        setCommandMessageState(current => current?.scopeKey === currentScope && current.intentId !== summary.id ? null : current)
      }
    })
    return unsubscribe
  }, [client, pendingIntentKey, scopeKey])

  async function refreshTransferData() {
    await client.invalidateQueries({ queryKey: scope, predicate: query => !query.queryKey.includes('transfer-command-intents') })
  }

  function beginOperation(intentId?: string): RecoveryOperation {
    const operation = { scopeKey, token: ++operationToken.current, intentId, signal: tenantSignal() }
    setCommandBusyState({ scopeKey, token: operation.token })
    return operation
  }

  function operationIsCurrent(operation: RecoveryOperation) {
    return !operation.signal.aborted && scopeKeyRef.current === operation.scopeKey && operationToken.current === operation.token
      && (operation.intentId === undefined || activeIntentIdRef.current === operation.intentId)
  }

  function retainRecoveryLock(operation: RecoveryOperation, intentId: string) {
    if (scopeKeyRef.current !== operation.scopeKey || operationToken.current !== operation.token) return
    operation.intentId = intentId
    activeIntentIdRef.current = intentId
    setRecoveryLockState({ scopeKey: operation.scopeKey, intentId })
  }

  function setRecoveryView(operation: RecoveryOperation, view: Omit<RecoveryView, 'scopeKey' | 'intentId'> | null) {
    if (!operationIsCurrent(operation) || !operation.intentId) return
    setRecoveryViewState(view ? { ...view, scopeKey: operation.scopeKey, intentId: operation.intentId } : null)
  }

  function setCommandMessage(operation: RecoveryOperation, notice?: string, error?: string, complete = false) {
    if (!operationIsCurrent(operation)) return
    setCommandMessageState({ scopeKey: operation.scopeKey, intentId: operation.intentId, notice, error, complete })
  }

  function finishOperation(operation: RecoveryOperation) {
    if (scopeKeyRef.current === operation.scopeKey && operationToken.current === operation.token) setCommandBusyState(current =>
      current?.scopeKey === operation.scopeKey && current.token === operation.token ? null : current)
  }

  function currentPendingRead(): PendingReadResult | null {
    const read = pendingReadStateRef.current
    const query = client.getQueryState(pendingIntentKey)
    if (read?.scopeKey !== scopeKey || read.status !== 'success' || read.token !== pendingReadToken.current
      || query?.status !== 'success' || query.fetchStatus !== 'idle') return null
    const cached = client.getQueryData<PendingReadResult>(pendingIntentKey)
    return cached?.scopeKey === scopeKey && cached.token === read.token ? cached : null
  }

  async function refreshPendingIntents(requestScope = scopeKey, signal = tenantSignal()) {
    if (scopeKeyRef.current !== requestScope || signal.aborted) return null
    const token = ++pendingReadToken.current
    recordPendingReadState({ scopeKey: requestScope, token, status: 'loading' })
    await client.cancelQueries({ queryKey: pendingIntentKey, exact: true })
    if (scopeKeyRef.current !== requestScope || pendingReadToken.current !== token || signal.aborted) return null
    try {
      const page = await listPendingTransferCommandIntents(organizationId, signal)
      signal.throwIfAborted()
      if (scopeKeyRef.current !== requestScope || pendingReadToken.current !== token) return null
      const result = { scopeKey: requestScope, token, page }
      recordPendingReadState({ scopeKey: requestScope, token, status: 'success' })
      client.setQueryData(pendingIntentKey, result)
      return result
    } catch (error) {
      if (scopeKeyRef.current === requestScope && pendingReadToken.current === token && !signal.aborted) {
        recordPendingReadState({ scopeKey: requestScope, token, status: 'error' })
        throw error
      }
      return null
    }
  }

  async function confirmIntentAcknowledgement(intentId: string, signal: AbortSignal): Promise<AcknowledgementResult> {
    try {
      const acknowledged = await acknowledgeTransferCommandIntent(organizationId, intentId, signal)
      if (acknowledged.id === intentId && acknowledged.state === 'ACKNOWLEDGED') {
        return { kind: 'acknowledged', redacted: acknowledged.redacted }
      }
      if (acknowledged.redacted) return { kind: 'unknown', redacted: true }
    } catch {
      try {
        const current = await inspectTransferCommandIntent(organizationId, intentId, signal)
        if (current.id !== intentId || !inspectionIsConsistent(current)) return { kind: 'unknown', redacted: current.redacted }
        if (current.executionStatus === 'ACKNOWLEDGED') return { kind: 'acknowledged', redacted: current.redacted }
        if (current.executionStatus === 'COMMITTED_AWAITING_ACKNOWLEDGEMENT') {
          return { kind: 'committed-awaiting-acknowledgement', inspection: current }
        }
        if (terminalIntentStatus(current.executionStatus)) return { kind: 'terminal', inspection: current }
        return { kind: 'unknown', redacted: current.redacted }
      } catch {
        return { kind: 'unknown', redacted: false }
      }
    }
    return { kind: 'unknown', redacted: false }
  }

  function replaceWithServerPending(operation: RecoveryOperation, intent: TransferCommandIntentSummary) {
    if (!operationIsCurrent(operation)) return
    operationToken.current++
    activeIntentIdRef.current = intent.id
    setRecoveryLockState({ scopeKey: operation.scopeKey, intentId: intent.id })
    setRecoveryViewState(null)
    setCommandMessageState(null)
    setCommandBusyState(null)
  }

  async function reconcilePendingIntent(operation: RecoveryOperation,
    resolution: 'ACKNOWLEDGED' | 'REJECTED' | 'CONFLICTED' | 'UNRESOLVED', notice: string, redacted: boolean) {
    const intentId = operation.intentId
    if (!intentId || !operationIsCurrent(operation)) return false
    let read: PendingReadResult | null
    try {
      read = await refreshPendingIntents(operation.scopeKey, operation.signal)
    } catch {
      if (operationIsCurrent(operation)) {
        setRecoveryView(operation, { kind: 'unknown' })
        setCommandMessage(operation, 'The current recovery state could not be confirmed. New commands remain paused; retry the saved-command check.')
      }
      return false
    }
    if (!read || !operationIsCurrent(operation)) {
      if (operationIsCurrent(operation)) {
        setRecoveryView(operation, { kind: 'unknown' })
        setCommandMessage(operation, 'A newer recovery read took priority. Keep the command blocked and check its current server status again.')
      }
      return false
    }
    const latest = currentPendingRead()
    if (!latest || latest.token < read.token) {
      setRecoveryView(operation, { kind: 'unknown' })
      setCommandMessage(operation, 'The current recovery state could not be confirmed. New commands remain paused; retry the saved-command check.')
      return false
    }
    const current = latest.page.items[0]
    if (current && current.id !== intentId) {
      replaceWithServerPending(operation, current)
      return false
    }
    if (current) {
      if (current.redacted || redacted) {
        setRecoveryView(operation, { kind: 'unknown' })
        setCommandMessage(operation, 'Access to the saved command changed. Its details remain hidden while the server recovery record is reconciled.')
      } else if (resolution === 'REJECTED' || resolution === 'CONFLICTED') {
        setRecoveryView(operation, { kind: 'terminal', executionStatus: resolution, redacted: false })
        setCommandMessage(operation, `${notice} The terminal intent is still listed by the server, so new commands remain paused.`)
      } else if (resolution === 'ACKNOWLEDGED') {
        setRecoveryView(operation, { kind: 'acknowledged', redacted: false })
        setCommandMessage(operation, 'Acknowledgement is confirmed, but the intent remains in the current pending list. New commands remain paused until it disappears.')
      } else {
        setRecoveryView(operation, { kind: 'unknown' })
        setCommandMessage(operation, `${notice} The pending command remains listed. New commands remain paused until recovery is confirmed.`)
      }
      return false
    }
    if (resolution !== 'ACKNOWLEDGED' && resolution !== 'REJECTED' && resolution !== 'CONFLICTED') {
      setRecoveryView(operation, { kind: 'unknown' })
      setCommandMessage(operation, 'The server pending list no longer shows this command, but its terminal outcome has not been confirmed.')
      return false
    }
    setCommandMessage(operation, notice, undefined, true)
    activeIntentIdRef.current = null
    setRecoveryLockState(currentLock => currentLock?.scopeKey === operation.scopeKey && currentLock.intentId === intentId ? null : currentLock)
    setRecoveryViewState(null)
    return true
  }

  async function refreshCommittedCommand(operation: RecoveryOperation, outcome: AcknowledgementResult, transferToOpen?: string) {
    try { await refreshTransferData() }
    catch {
      if (operationIsCurrent(operation)) {
        setRecoveryView(operation, { kind: 'unknown' })
        setCommandMessage(operation, 'The business result is confirmed, but transfer data could not be refreshed. Inspect recovery status before starting another command.')
      }
      return
    }
    if (!operationIsCurrent(operation)) return
    if (outcome.kind === 'acknowledged') {
      const notice = outcome.redacted
        ? 'The command is acknowledged. Transfer details remain hidden because access changed.'
        : 'The command is acknowledged and its pending recovery record has been reconciled.'
      setRecoveryView(operation, { kind: 'acknowledged', redacted: outcome.redacted })
      const reconciled = await reconcilePendingIntent(operation, 'ACKNOWLEDGED', notice, outcome.redacted)
      if (reconciled && transferToOpen && !outcome.redacted && transferId !== transferToOpen) {
        try { await navigate({ to: '/organizations/$organizationId/transfers/$transferId', params: { organizationId, transferId: transferToOpen } }) }
        catch { setCommandMessage(operation, `${notice} The transfer view could not be opened; reload the workspace to continue.`, undefined, true) }
      }
      return
    }
    if (outcome.kind === 'terminal') {
      await applyTerminalInspection(operation, outcome.inspection)
      return
    }
    if (outcome.kind === 'committed-awaiting-acknowledgement') {
      const inspection = outcome.inspection
      const page = currentPendingRead()
      const summary = page?.page.items.find(item => item.id === operation.intentId)
      if (inspection.redacted || summary?.redacted) {
        setRecoveryView(operation, inspection.redacted ? { kind: 'inspection', inspection } : { kind: 'unknown' })
        setCommandMessage(operation, 'The business result is committed, but acknowledgement is not confirmed. Access changed or remains uncertain; inspect the saved record again.')
      } else if (summary && inspectionMatchesSummary(inspection, summary)) {
        setRecoveryView(operation, { kind: 'inspection', inspection })
        setCommandMessage(operation, 'The business result is committed, but acknowledgement is not confirmed. Inspect the saved record again before starting another command.')
      } else {
        setRecoveryView(operation, { kind: 'unknown' })
        setCommandMessage(operation, 'The business result is committed, but its current authorization and acknowledgement could not be confirmed.')
      }
      await reconcilePendingIntent(operation, 'UNRESOLVED', 'The business result is committed, but acknowledgement remains unconfirmed.', inspection.redacted)
      return
    }
    setRecoveryView(operation, { kind: 'unknown' })
    setCommandMessage(operation, 'The business result is confirmed, but acknowledgement and current access could not be confirmed. Inspect the saved record before continuing.')
    await reconcilePendingIntent(operation, 'UNRESOLVED', 'The committed result remains unresolved.', outcome.redacted)
  }

  async function applyTerminalInspection(operation: RecoveryOperation, inspection: TransferCommandIntentInspection) {
    if (!operationIsCurrent(operation) || !operation.intentId || inspection.id !== operation.intentId
      || !inspectionIsConsistent(inspection) || !terminalIntentStatus(inspection.executionStatus)) return
    const status = inspection.executionStatus
    const page = currentPendingRead()
    const summary = page?.page.items.find(item => item.id === operation.intentId)
    if (summary?.redacted && !inspection.redacted) {
      setRecoveryView(operation, { kind: 'unknown' })
      setCommandMessage(operation, 'The server changed access while the command was being checked. Details remain hidden until the next check succeeds.')
      await reconcilePendingIntent(operation, 'UNRESOLVED', 'The terminal result is awaiting a fresh access check.', true)
      return
    }
    setRecoveryView(operation, { kind: 'terminal', executionStatus: status, redacted: inspection.redacted })
    const notice = inspection.redacted
      ? `The saved command is ${status.toLowerCase()}. Transfer details remain hidden.`
      : status === 'REJECTED'
        ? 'The saved command was rejected before committing. No retry or acknowledgement is needed.'
        : 'The saved command conflicted before committing. Review the current transfer before starting a new request.'
    setCommandMessage(operation, notice)
    await reconcilePendingIntent(operation, status, notice, inspection.redacted)
  }

  async function submitPreparedCommand(intent: TransferCommandIntent, intentSummary: TransferCommandIntentSummary,
    operation: RecoveryOperation, alreadySubmitted: boolean) {
    if (!operation.intentId || operation.intentId !== intentSummary.id || !operationIsCurrent(operation)) return
    retainRecoveryLock(operation, intentSummary.id)
    if (intentSummary.redacted || intentSummary.operation !== intent.operation || intentSummary.requestId !== intent.body.requestId) {
      setRecoveryView(operation, { kind: 'unknown' })
      setCommandMessage(operation, 'Current authorization no longer matches the saved request. Keep it blocked and inspect its status.')
      return
    }
    if (!alreadySubmitted) {
      let submitted: Awaited<ReturnType<typeof markTransferCommandIntentSubmitted>>
      try {
        submitted = await markTransferCommandIntentSubmitted(organizationId, intentSummary.id, operation.signal)
      } catch (error) {
        throw new TransferCommandPhaseError('mark-submitted', error)
      }
      if (!operationIsCurrent(operation)) return
      if (submitted.id !== intentSummary.id || submitted.redacted || submitted.state !== 'SUBMITTED') {
        setRecoveryView(operation, { kind: 'unknown' })
        setCommandMessage(operation, 'The saved request changed while being prepared. It was not submitted; inspect the server record before continuing.')
        return
      }
    }
    let outcome: Awaited<ReturnType<typeof executeTransferCommand>>
    try {
      outcome = await executeTransferCommand(organizationId, intent, operation.signal)
    } catch (error) {
      throw new TransferCommandPhaseError('execute', error)
    }
    if (!operationIsCurrent(operation)) return
    const savedMessage = `Saved as ${transferStatusLabel(outcome.status)}. Server record ${outcome.transferId.slice(0, 8)} · version ${outcome.version}.`
    setCommandMessage(operation, `${savedMessage} The server confirmed the business result; confirming its recovery acknowledgement.`)
    const acknowledgement = await confirmIntentAcknowledgement(intentSummary.id, operation.signal)
    if (!operationIsCurrent(operation)) return
    await refreshCommittedCommand(operation, acknowledgement,
      acknowledgement.kind === 'acknowledged' && !acknowledgement.redacted ? outcome.transferId : undefined)
  }

  async function handlePreparedCommandFailure(operation: RecoveryOperation, intentId: string, error: unknown) {
    if (!operationIsCurrent(operation)) return
    const phaseError = error instanceof TransferCommandPhaseError ? error : null
    const original = phaseError?.original ?? error
    const resolution = phaseError?.phase === 'execute' ? definitiveTransferExecutionResolution(original) : null
    if (resolution) {
      try {
        await resolveFailedTransferCommandIntent(organizationId, intentId, resolution, operation.signal)
      } catch { /* Inspect below; the server guards committed receipts transactionally. */ }
      if (!operationIsCurrent(operation)) return
      try {
        await inspectAndApply(operation, intentId)
      } catch (inspectionError) {
        if (operationIsCurrent(operation)) {
          setRecoveryView(operation, { kind: 'unknown', redacted: transferAccessDenied(original) || transferAccessDenied(inspectionError) })
          setCommandMessage(operation, 'The business request returned a definitive rejection response, but the server recovery state could not be confirmed. Keep it blocked and inspect the saved record.')
        }
      }
      return
    }

    const denied = transferAccessDenied(original)
    setRecoveryView(operation, { kind: 'unknown', redacted: denied })
    const notice = phaseError?.phase === 'mark-submitted'
      ? 'The saved command could not be marked as submitted. Its server state is unconfirmed; inspect the saved record before retrying.'
      : phaseError?.phase === 'execute'
        ? 'The exact saved request may still be processing. No replacement request was sent; inspect its outcome before continuing.'
        : denied
          ? 'Current access to the saved command could not be confirmed. Its details remain hidden; retry the status check.'
          : 'The saved command could not be verified. Keep it blocked and inspect its current server state.'
    setCommandMessage(operation, notice)
    if (!phaseError) return
    try {
      const refreshed = await refreshPendingIntents(operation.scopeKey, operation.signal)
      if (!operationIsCurrent(operation)) return
      const current = currentPendingRead()
      if (!current || !refreshed) {
        setRecoveryView(operation, { kind: 'unknown', redacted: denied })
        setCommandMessage(operation, 'The request and current recovery status could not be confirmed. Keep it blocked and retry the saved-command check.')
      } else if (current.page.items[0] && current.page.items[0].id !== intentId) {
        replaceWithServerPending(operation, current.page.items[0])
      }
    } catch {
      if (operationIsCurrent(operation)) {
        setRecoveryView(operation, { kind: 'unknown', redacted: denied })
        setCommandMessage(operation, 'The request and current recovery status could not be confirmed. Keep it blocked and retry the saved-command check.')
      }
    }
  }

  async function runIntent(intent: TransferCommandIntent) {
    if (commandBlocked || commandBusy) return
    const operation = beginOperation()
    setRecoveryViewState(null)
    setCommandMessageState(null)
    let intentId: string | undefined
    try {
      const prepared = await prepareTransferCommandIntent(organizationId, intent, operation.signal)
      intentId = prepared.id
      if (!operationIsCurrent(operation)) return
      retainRecoveryLock(operation, prepared.id)
      setRecoveryView(operation, { kind: 'checking' })
      if (prepared.redacted || prepared.operation !== intent.operation || prepared.requestId !== intent.body.requestId) {
        setRecoveryView(operation, { kind: 'unknown' })
        setCommandMessage(operation, 'The saved command could not be verified under current authorization. Inspect it before continuing.')
        return
      }
      await submitPreparedCommand(intent, prepared, operation, false)
    } catch (error) {
      if (!operationIsCurrent(operation)) return
      if (intentId) {
        retainRecoveryLock(operation, intentId)
        await handlePreparedCommandFailure(operation, intentId, error)
      } else {
        setCommandMessage(operation, undefined, 'The command may have been prepared. Checking its recovery status; no stock operation was sent by this attempt.')
        try {
          await refreshPendingIntents(operation.scopeKey, operation.signal)
          if (!operationIsCurrent(operation)) return
          const current = currentPendingRead()
          if (!current) {
            setCommandMessage(operation, undefined, 'The command may have been prepared, but current recovery status is not confirmed. New commands remain paused; check saved commands again.')
          } else if (current.page.items[0]) {
            replaceWithServerPending(operation, current.page.items[0])
          } else if (definitivePrepareRejection(error)) {
            setCommandMessage(operation, undefined, 'The transfer command was rejected before it could be prepared. No stock operation was sent; review the request before trying again.')
          } else {
            setCommandMessage(operation, undefined, 'No pending command was found. This attempt did not send a stock operation; its preparation outcome remains unconfirmed.')
          }
        } catch {
          if (operationIsCurrent(operation)) setCommandMessage(operation, undefined,
            'The command may have been prepared, but current recovery status could not be read. New commands remain paused; check saved commands again.')
        }
      }
    } finally { finishOperation(operation) }
  }

  async function applyInspection(operation: RecoveryOperation, inspection: TransferCommandIntentInspection) {
    if (!operationIsCurrent(operation) || !operation.intentId) return
    if (inspection.id !== operation.intentId || !inspectionIsConsistent(inspection)) {
      setRecoveryView(operation, { kind: 'unknown' })
      setCommandMessage(operation, 'The server returned an inconsistent recovery record. Keep the command blocked and retry the status check.')
      return
    }
    const page = currentPendingRead()
    if (!page) {
      setRecoveryView(operation, { kind: 'unknown' })
      setCommandMessage(operation, 'Current access and pending status could not be confirmed. Retry the saved-command check before continuing.')
      return
    }
    const summary = page.page.items[0]
    if (summary && summary.id !== operation.intentId) {
      replaceWithServerPending(operation, summary)
      return
    }
    if (summary?.redacted && !inspection.redacted) {
      setRecoveryView(operation, { kind: 'unknown' })
      setCommandMessage(operation, 'The server changed access while the command was being inspected. Transfer details remain hidden.')
      return
    }
    if (inspection.redacted && (inspection.executionStatus === 'PREPARED'
      || inspection.executionStatus === 'SUBMITTED_AWAITING_OUTCOME')) {
      setRecoveryView(operation, { kind: 'inspection', inspection, redacted: true })
      setCommandMessage(operation, 'Current access no longer permits these request details. The saved command remains blocked while recovery is checked.')
      return
    }
    if (inspection.executionStatus === 'REJECTED' || inspection.executionStatus === 'CONFLICTED') {
      await applyTerminalInspection(operation, inspection)
      return
    }
    const requiresPendingSummary = inspection.executionStatus === 'PREPARED'
      || inspection.executionStatus === 'SUBMITTED_AWAITING_OUTCOME'
      || inspection.executionStatus === 'COMMITTED_AWAITING_ACKNOWLEDGEMENT'
    if (requiresPendingSummary && (!summary || !inspectionMatchesSummary(inspection, summary))) {
      setRecoveryView(operation, { kind: 'unknown' })
      setCommandMessage(operation, 'The inspection and current pending record disagree. Keep the command blocked and retry the status check.')
      return
    }
    switch (inspection.executionStatus) {
      case 'PREPARED':
        setRecoveryView(operation, { kind: 'inspection', inspection })
        setCommandMessage(operation, inspection.redacted
          ? 'Access to this transfer changed. The server can safely resolve the saved command without showing its payload or transfer details.'
          : 'This command was saved but not submitted. Its exact request is available under current authorization.')
        return
      case 'SUBMITTED_AWAITING_OUTCOME':
        setRecoveryView(operation, { kind: 'inspection', inspection })
        setCommandMessage(operation, inspection.redacted
          ? 'The submitted command is unresolved. Access changed, so its details remain hidden while recovery stays blocked.'
          : 'No committed outcome is visible yet. Retrying will use the exact saved payload and request key.')
        return
      case 'COMMITTED_AWAITING_ACKNOWLEDGEMENT': {
        setRecoveryView(operation, { kind: 'inspection', inspection })
        setCommandMessage(operation, inspection.redacted
          ? 'The business result is committed. The server is confirming its acknowledgement without exposing transfer details.'
          : `Recovered ${transferStatusLabel(inspection.committedOutcome!.status)} from the saved command record. Confirming recovery acknowledgement.`)
        const acknowledgement = await confirmIntentAcknowledgement(operation.intentId, operation.signal)
        if (operationIsCurrent(operation)) await refreshCommittedCommand(operation, acknowledgement,
          acknowledgement.kind === 'acknowledged' && !acknowledgement.redacted ? inspection.committedOutcome?.transferId : undefined)
        return
      }
      case 'ACKNOWLEDGED':
        setRecoveryView(operation, { kind: 'acknowledged', redacted: inspection.redacted })
        await reconcilePendingIntent(operation, 'ACKNOWLEDGED', inspection.redacted
          ? 'The saved command is acknowledged. Transfer details remain hidden.'
          : 'The saved command is acknowledged and no longer pending.', inspection.redacted)
        return
      default:
        setRecoveryView(operation, { kind: 'unknown' })
        setCommandMessage(operation, 'The saved command has an unknown status. Keep it blocked and retry the status check.')
    }
  }

  async function inspectAndApply(operation: RecoveryOperation, intentId: string) {
    const inspection = await inspectTransferCommandIntent(organizationId, intentId, operation.signal)
    if (operationIsCurrent(operation)) await applyInspection(operation, inspection)
  }

  async function inspectSavedOutcome() {
    const intentId = activeIntentId
    if (!intentId || commandBusy) return
    const operation = beginOperation(intentId)
    retainRecoveryLock(operation, intentId)
    setRecoveryView(operation, { kind: 'checking' })
    setCommandMessage(operation, 'Checking the current server record without repeating the stock operation.')
    try {
      const read = await refreshPendingIntents(operation.scopeKey, operation.signal)
      if (!operationIsCurrent(operation)) return
      const current = currentPendingRead()
      if (!read || !current) {
        setRecoveryView(operation, { kind: 'unknown' })
        setCommandMessage(operation, 'A newer recovery read took priority. The command remains blocked; retry the saved-command check.')
        return
      }
      if (current.page.items[0] && current.page.items[0].id !== intentId) {
        replaceWithServerPending(operation, current.page.items[0])
        return
      }
      await inspectAndApply(operation, intentId)
    } catch (error) {
      if (!operationIsCurrent(operation)) return
      setRecoveryView(operation, { kind: 'unknown', redacted: transferAccessDenied(error) })
      setCommandMessage(operation, transferAccessDenied(error)
        ? 'Current access to the saved command could not be confirmed. Its details remain hidden; retry the status check.'
        : 'The saved command status could not be read. New commands remain paused; retry this read when the service is available.')
    } finally { finishOperation(operation) }
  }

  async function retrySavedCommand() {
    const intentId = activeIntentId
    if (!intentId || commandBusy || !authorizedInspection || recoveryRedacted
      || !['PREPARED', 'SUBMITTED_AWAITING_OUTCOME'].includes(authorizedInspection.executionStatus)) return
    const operation = beginOperation(intentId)
    retainRecoveryLock(operation, intentId)
    setCommandMessage(operation, 'Verifying the saved request and current access before retrying its exact request key and payload.')
    try {
      const read = await refreshPendingIntents(operation.scopeKey, operation.signal)
      if (!operationIsCurrent(operation)) return
      const current = currentPendingRead()
      if (!read || !current) {
        setRecoveryView(operation, { kind: 'unknown' })
        setCommandMessage(operation, 'A newer recovery read took priority. The saved command remains blocked; retry its status check.')
        return
      }
      if (current.page.items[0] && current.page.items[0].id !== intentId) {
        replaceWithServerPending(operation, current.page.items[0])
        return
      }
      const summary = current.page.items.find(item => item.id === intentId)
      if (!summary || summary.redacted) {
        setRecoveryView(operation, { kind: 'unknown' })
        setCommandMessage(operation, 'Current authorization no longer permits this saved request. It remains blocked while recovery is checked.')
        return
      }
      const inspection = await inspectTransferCommandIntent(organizationId, intentId, operation.signal)
      if (!operationIsCurrent(operation)) return
      const latest = currentPendingRead()
      if (!latest) {
        setRecoveryView(operation, { kind: 'unknown' })
        setCommandMessage(operation, 'Current authorization and pending status could not be confirmed. No stock operation was retried.')
        return
      }
      if (latest.page.items[0] && latest.page.items[0].id !== intentId) {
        replaceWithServerPending(operation, latest.page.items[0])
        return
      }
      const latestSummary = latest.page.items.find(item => item.id === intentId)
      if (!latestSummary || latestSummary.redacted) {
        setRecoveryView(operation, { kind: 'unknown', redacted: latestSummary?.redacted })
        setCommandMessage(operation, 'Current authorization no longer permits this saved request. No stock operation was retried.')
        return
      }
      if (inspection.id !== intentId || !inspectionIsConsistent(inspection)) {
        setRecoveryView(operation, { kind: 'unknown' })
        setCommandMessage(operation, 'The current saved request could not be verified. No stock operation was retried.')
        return
      }
      if (inspection.executionStatus !== 'PREPARED' && inspection.executionStatus !== 'SUBMITTED_AWAITING_OUTCOME') {
        await applyInspection(operation, inspection)
        return
      }
      if (inspection.redacted || !inspectionMatchesSummary(inspection, latestSummary)) {
        setRecoveryView(operation, { kind: 'unknown', redacted: inspection.redacted || latestSummary.redacted })
        setCommandMessage(operation, 'The saved request changed or became restricted. No stock operation was retried.')
        return
      }
      const intent = { operation: inspection.operation, transferId: inspection.transferId, body: inspection.payload }
      if (!isTransferCommandIntent(intent)) {
        setRecoveryView(operation, { kind: 'unknown' })
        setCommandMessage(operation, 'The saved transfer request has an unsupported shape. Keep it blocked and contact support.')
        return
      }
      setRecoveryView(operation, { kind: 'inspection', inspection })
      await submitPreparedCommand(intent, latestSummary, operation, inspection.executionStatus === 'SUBMITTED_AWAITING_OUTCOME')
    } catch (error) {
      await handlePreparedCommandFailure(operation, intentId, error)
    } finally { finishOperation(operation) }
  }

  async function resolveRedactedCommand() {
    const intentId = activeIntentId
    if (!intentId || commandBusy || !recoveryRedacted || !recoveryInspection
      || !['PREPARED', 'SUBMITTED_AWAITING_OUTCOME'].includes(recoveryInspection.executionStatus)) return
    const operation = beginOperation(intentId)
    retainRecoveryLock(operation, intentId)
    setCommandMessage(operation, 'Checking whether the server can safely resolve this saved command.')
    try {
      const read = await refreshPendingIntents(operation.scopeKey, operation.signal)
      if (!operationIsCurrent(operation)) return
      const current = currentPendingRead()
      if (!read || !current) {
        setRecoveryView(operation, { kind: 'unknown', redacted: true })
        setCommandMessage(operation, 'A newer recovery read took priority. The redacted command remains blocked; retry its status check.')
        return
      }
      if (current.page.items[0] && current.page.items[0].id !== intentId) {
        replaceWithServerPending(operation, current.page.items[0])
        return
      }
      const inspection = await inspectTransferCommandIntent(organizationId, intentId, operation.signal)
      if (!operationIsCurrent(operation)) return
      const latest = currentPendingRead()
      if (!latest) {
        setRecoveryView(operation, { kind: 'unknown', redacted: true })
        setCommandMessage(operation, 'Current authorization and pending status could not be confirmed. No command was resolved.')
        return
      }
      if (latest.page.items[0] && latest.page.items[0].id !== intentId) {
        replaceWithServerPending(operation, latest.page.items[0])
        return
      }
      if (inspection.id !== intentId || !inspectionIsConsistent(inspection)) {
        setRecoveryView(operation, { kind: 'unknown' })
        setCommandMessage(operation, 'The server could not verify this recovery record. No command was resolved.')
        return
      }
      if (inspection.executionStatus !== 'PREPARED' && inspection.executionStatus !== 'SUBMITTED_AWAITING_OUTCOME') {
        await applyInspection(operation, inspection)
        return
      }
      if (!inspection.redacted || !latest.page.items.some(item => item.id === intentId && item.redacted)) {
        setRecoveryView(operation, { kind: 'unknown' })
        setCommandMessage(operation, 'The saved command authorization changed. Retry inspection before resolving it.')
        return
      }
      const resolution = inspection.executionStatus === 'PREPARED' ? 'REJECTED' : 'CONFLICTED'
      await resolveFailedTransferCommandIntent(organizationId, intentId, resolution, operation.signal)
      if (operationIsCurrent(operation)) await inspectAndApply(operation, intentId)
    } catch (error) {
      if (operationIsCurrent(operation)) {
        setRecoveryView(operation, { kind: 'unknown', redacted: true })
        setCommandMessage(operation, transferAccessDenied(error)
          ? 'The server could not confirm access or safely resolve this command. Keep it blocked and retry inspection.'
          : 'The server could not safely resolve this command yet. It remains blocked; retry inspection when available.')
        try { await refreshPendingIntents(operation.scopeKey, operation.signal) }
        catch { setRecoveryView(operation, { kind: 'unknown' }) }
      }
    } finally { finishOperation(operation) }
  }

  async function retryPendingListSafely() {
    try {
      const requestScope = scopeKey
      const read = await refreshPendingIntents(requestScope, tenantSignal())
      const current = currentPendingRead()
      if (!read || !current || scopeKeyRef.current !== requestScope || current.page.items.length > 0) return
      setCommandMessageState(message => message?.scopeKey === requestScope && !message.intentId ? null : message)
    }
    catch { /* The read state carries the retryable failure into the recovery UI. */ }
  }

  function retryTransferAccess() {
    transferAccessRevalidation.current = true
    transferAccessDenialHandled.current = true
    setTransferAccessEpoch(current => current + 1)
  }

  const storeMap = useMemo(() => new Map(storeItems.map(store => [store.id, store])), [storeItems])
  const transfersHref = `/organizations/${organizationId}/transfers`
  const canRetrySavedCommand = Boolean(authorizedInspection && !recoveryRedacted
    && ['PREPARED', 'SUBMITTED_AWAITING_OUTCOME'].includes(authorizedInspection.executionStatus))
  const canResolveRedactedCommand = Boolean(pendingReadReady && activeSummary?.redacted && recoveryRedacted && recoveryInspection?.redacted
    && ['PREPARED', 'SUBMITTED_AWAITING_OUTCOME'].includes(recoveryInspection.executionStatus))
  const recoveryNotice = recoveryRedacted
    ? 'Access to this saved command changed. Its request details remain hidden while recovery stays blocked.'
    : !pendingReadReady ? 'Current access and recovery status could not be confirmed. Keep the command blocked and retry the saved-command check.'
      : commandMessage?.notice
  const recoveryError = recoveryRedacted || !pendingReadReady ? undefined : commandMessage?.error
  const transferAccessHidden = transferAccessDeniedNow || transferAccessRevalidation.current && !transferPermissionReadComplete
  const transferAccessFetching = stores.isFetching || transferReadAccess.isFetching
    || queue.isFetching || transit.isFetching || evidence.isFetching
  return <section className="space-y-7">
    <header className="space-y-2"><p className="text-xs font-semibold uppercase tracking-[0.16em] text-primary">{historicalOnly ? 'Administrative transfer history' : 'Stock movement between stores'}</p><h2 className="text-3xl font-semibold">{historicalOnly ? 'Historical transfers' : 'Stock transfers'}</h2><p className="max-w-3xl text-sm text-muted-foreground">{historicalOnly ? 'Find existing transfers that involve an archived store, review their custody history, and continue only with settlement actions authorized by the server.' : 'Review each request, physical handoff and receiving event against its saved stock and custody records.'}</p></header>
    {!historicalOnly && pendingReadFailed && !commandMessage?.error && <div role="alert" className="rounded-xl border border-destructive/30 p-4 text-sm text-destructive">The server could not confirm whether a transfer command is awaiting recovery. New transfer commands remain paused until a fresh read succeeds. <Button variant="outline" disabled={pendingIntents.isFetching} onClick={() => { void retryPendingListSafely() }}>Check saved commands again</Button></div>}
    {!historicalOnly && activeIntentId && <section aria-label="Recover saved transfer command" className="space-y-3 rounded-2xl border border-amber-500/50 bg-card p-5">
      <div><h3 className="text-lg font-semibold">A transfer command needs review</h3><p className="mt-2 text-sm text-muted-foreground">{recoveryRedacted || !activeSummary
        ? 'The saved command remains blocked while current authorization and outcome are checked. Request details stay hidden.'
        : `${activeSummary.operation} ? request key ${activeSummary.requestId}. The exact command is stored on the server for this account and organization, so recovery can continue after this tab closes.`}</p></div>
      {recoveryNotice && <p role="status" className="text-sm">{recoveryNotice}</p>}{recoveryError && <p role="alert" className="text-sm text-destructive">{recoveryError}</p>}
      <div className="flex flex-wrap gap-3"><Button variant="outline" disabled={commandBusy} onClick={() => { void inspectSavedOutcome() }}>{commandBusy ? 'Checking saved outcome?' : 'Inspect saved outcome'}</Button>
        {canResolveRedactedCommand && <Button disabled={commandBusy} onClick={() => { void resolveRedactedCommand() }}>{commandBusy ? 'Resolving saved command?' : 'Resolve without transfer details'}</Button>}
        {canRetrySavedCommand && <Button disabled={commandBusy} onClick={() => { void retrySavedCommand() }}>{commandBusy ? 'Retrying saved command?' : 'Retry exact saved command'}</Button>}</div>
    </section>}
    {!historicalOnly && !activeIntentId && commandMessage?.complete && commandMessage.notice && <p role="status" className="rounded-xl border border-border p-4 text-sm">{commandMessage.notice}</p>}
    {!historicalOnly && !activeIntentId && commandMessage?.error && <div role="alert" className="rounded-xl border border-border p-4 text-sm"><p>{commandMessage.error}</p>{!pendingReadReady && <Button className="mt-3" variant="outline" disabled={commandBusy || pendingIntents.isFetching} onClick={() => { void retryPendingListSafely() }}>Check saved commands again</Button>}</div>}
    {transferId ? detail.isPending ? <StateMessage state="loading" title="Loading transfer record" description="Confirming the current transfer and your participating-store access." />
      : detail.isError ? <div className="space-y-4"><StateMessage state={detail.error instanceof ApiError && detail.error.status === 403 ? 'forbidden' : 'unavailable'} title="Transfer unavailable" description={errorMessage(detail.error)} /><Button variant="outline" onClick={() => { void detail.refetch() }}>Retry transfer read</Button><AppLink href={transfersHref} className="inline-flex text-primary underline">Back to transfers</AppLink></div>
        : <TransferDetailView organizationId={organizationId} transfer={detail.data} stores={[...storeMap.values()]} onCommand={runIntent} busy={commandBlocked} onRefresh={refreshTransferData} />
      : historicalOnly ? organization.role !== 'ORG_ADMIN' ? <StateMessage state="forbidden" title="Historical transfers are restricted" description="Only organization administrators can find transfers involving archived stores." />
        : historical.isPending ? <StateMessage state="loading" title="Loading historical transfers" description="Finding existing transfers that involve archived stores." />
          : historical.isError ? <div className="space-y-4"><StateMessage state="unavailable" title="Historical transfers unavailable" description={errorMessage(historical.error)} /><Button variant="outline" onClick={() => { void historical.refetch() }}>Retry historical transfer search</Button></div>
            : <div className="space-y-5"><AppLink href={transfersHref} className="inline-flex text-sm text-primary underline">Back to current transfer workspace</AppLink><Field label="Historical transfer status">{id => <select id={id} value={status} onChange={event => { setStatus(event.target.value as TransferStatus | '') }} className={inputClass}>{transferStatusOptions.map(option => <option key={option.value || 'all'} value={option.value}>{option.label}</option>)}</select>}</Field><HistoricalTransferQueue items={historicalItems} organizationId={organizationId} loading={historical.isFetching} onReload={() => { void historical.refetch() }} canLoadMore={Boolean(historical.hasNextPage)} loadingMore={historical.isFetchingNextPage} onLoadMore={() => { void historical.fetchNextPage() }} /></div>
      : <>
              <div className="grid gap-4 rounded-2xl border border-border bg-card p-5 sm:grid-cols-2">
                <Field label="Find a store">{id => <div className="flex gap-2"><input id={id} type="search" value={storeSearch} onChange={event => { setStoreSearch(event.target.value); setStoreFilter('') }} className={inputClass} placeholder="Search accessible stores" />{storeSearch && <Button type="button" variant="outline" onClick={() => { setStoreSearch(''); setStoreFilter('') }}>Clear search</Button>}</div>}</Field>
                <Field label="Store queue">{id => <select id={id} value={storeFilter} onChange={event => { setStoreFilter(event.target.value) }} className={inputClass}><option value="">All matching stores loaded below</option>{!transferAccessHidden && readableStores.map(store => <option key={store.id} value={store.id}>{store.code} · {store.name}</option>)}</select>}</Field>
                <Field label="Transfer status">{id => <select id={id} value={status} onChange={event => { setStatus(event.target.value as TransferStatus | '') }} className={inputClass}>{transferStatusOptions.map(option => <option key={option.value || 'all'} value={option.value}>{option.label}</option>)}</select>}</Field>
                {!stores.isPending && !stores.isError && stores.hasNextPage && <Button type="button" variant="outline" disabled={stores.isFetchingNextPage} onClick={() => { void stores.fetchNextPage() }}>{stores.isFetchingNextPage ? 'Loading stores…' : 'Load more accessible stores'}</Button>}
              </div>
              {transferAccessHidden ? <div className="space-y-4"><StateMessage state="unavailable" title="Transfer access is being rechecked" description="Previously loaded transfer, custody and evidence records are hidden until current store permissions and fresh reads are confirmed." /><Button variant="outline" disabled={transferAccessFetching} onClick={retryTransferAccess}>Retry transfer access</Button></div>
                : stores.isPending ? <StateMessage state="loading" title="Checking transfer access" description="Resolving your current store permissions." />
                : stores.isError ? <div className="space-y-4"><StateMessage state={stores.error instanceof ApiError && stores.error.status === 403 ? 'forbidden' : 'unavailable'} title="Transfer workspace unavailable" description={errorMessage(stores.error)} /><Button variant="outline" onClick={() => { void stores.refetch() }}>Retry store access</Button></div>
                : readableStores.length === 0 ? transferReadAccess.isPending ? <StateMessage state="loading" title="Checking transfer access" description="Checking your active store assignments before reporting that no transfers are available." />
                : transferReadAccess.isError ? <div className="space-y-4"><StateMessage state="unavailable" title="Transfer access unavailable" description={errorMessage(transferReadAccess.error)} /><Button variant="outline" onClick={() => { void transferReadAccess.refetch() }}>Retry transfer access check</Button></div>
                  : transferReadAccess.data === false ? <div className="space-y-3"><StateMessage state="forbidden" title="No transfer access for any active store" description="Ask an organization administrator to grant transfer reading for an active store." />{storeSearch && <Button type="button" variant="outline" onClick={() => { setStoreSearch(''); setStoreFilter('') }}>Clear search</Button>}</div>
                    : transferReadAccess.data === null ? <StateMessage state="unavailable" title="Transfer access could not be confirmed" description="The store list exceeded the supported search range. Refine the store search or ask an organization administrator to review your access." />
                      : storeSearch.trim() ? <div className="space-y-3"><StateMessage state="empty" title="No accessible stores match this search" description="Your account can read transfers in another active store. Clear the search or try a different store code or name." />{stores.hasNextPage && <p className="text-sm text-muted-foreground">More matching store results are available on the next page.</p>}</div>
                        : stores.hasNextPage ? <StateMessage state="empty" title="No transfer access on this store page" description="Load more active stores to check whether your transfer access is assigned on a later page." />
                          : <StateMessage state="forbidden" title="No transfer access for any active store" description="Ask an organization administrator to grant transfer reading for an active store." />
                : queueStoreIds.length === 0 ? <div className="space-y-3"><StateMessage state="empty" title="No accessible stores match this filter" description="Choose another store or clear the store filter to review transfers where you currently have access." />{storeFilter && <Button type="button" variant="outline" onClick={() => { setStoreFilter('') }}>Clear store filter</Button>}</div>
                  : <TransferQueue items={queueItems} stores={storeMap} transit={transitItems} discrepancies={evidenceItems} organizationId={organizationId}
                      onReload={() => { void queue.refetch() }} loading={queue.isFetching}
                      queuePending={queue.isPending} queueFetching={queue.isFetching} queueError={queue.isError} queueErrorMessage={queue.error ? errorMessage(queue.error) : ''}
                      canLoadTransfers={Boolean(queue.hasNextPage)} loadingTransfers={queue.isFetchingNextPage} onLoadTransfers={() => { void queue.fetchNextPage() }}
                      canLoadTransit={Boolean(transit.hasNextPage)} loadingTransit={transit.isFetchingNextPage} onLoadTransit={() => { void transit.fetchNextPage() }}
                      transitPending={transit.isPending} transitFetching={transit.isFetching} transitError={transit.isError} retryTransit={() => { void transit.refetch() }}
                      canLoadEvidence={Boolean(evidence.hasNextPage)} loadingEvidence={evidence.isFetchingNextPage} onLoadEvidence={() => { void evidence.fetchNextPage() }}
                      evidencePending={evidence.isPending} evidenceFetching={evidence.isFetching} evidenceError={evidence.isError} retryEvidence={() => { void evidence.refetch() }} />}
              {!transferAccessHidden && readableStores.some(store => store.active && store.allowedActions.includes('REQUEST_TRANSFER')) && <CreateTransferPanel key={JSON.stringify(scope)} organizationId={organizationId} administrator={organization.role === 'ORG_ADMIN'} onCommand={runIntent} busy={commandBusy} blockedReason={activeIntentId ? 'Resolve saved command first' : !pendingReadReady ? 'Checking saved command status' : undefined} />}
            </>}
  </section>
}

function HistoricalTransferQueue({ items, organizationId, loading, onReload, canLoadMore, loadingMore, onLoadMore }: {
  items: HistoricalTransferQueueItem[], organizationId: string, loading: boolean, onReload: () => void,
  canLoadMore: boolean, loadingMore: boolean, onLoadMore: () => void,
}) {
  const participantLabel = (participant: HistoricalTransferQueueItem['source']) => `${participant.code} · ${participant.name}`
  return <section aria-label="Historical transfer queue" className="space-y-4">
    <div className="flex flex-wrap items-center justify-between gap-3"><h3 className="text-xl font-semibold">Historical settlement queue</h3><Button variant="outline" disabled={loading} onClick={onReload}>{loading ? 'Refreshing…' : 'Refresh historical transfers'}</Button></div>
    {items.length === 0 && <p className="text-sm text-muted-foreground">No historical transfers match this status.</p>}
    <ul className="grid gap-4">{items.map(({ transfer, source, destination }) => <li key={transfer.id} className="min-w-0 rounded-2xl border border-border bg-card p-5">
      <div className="flex flex-wrap items-start justify-between gap-3"><div className="min-w-0"><p className="font-semibold">{participantLabel(source)}{!source.active && <span className="ml-2 rounded-full border border-border px-2 py-0.5 text-xs">Archived</span>} <span aria-hidden="true">→</span> {participantLabel(destination)}{!destination.active && <span className="ml-2 rounded-full border border-border px-2 py-0.5 text-xs">Archived</span>}</p><p className="mt-2 break-all text-xs text-muted-foreground">Transfer {transfer.id} · updated {new Date(transfer.updatedAt).toLocaleString()}</p></div><span className="rounded-full border border-border px-3 py-1 text-sm">{transferStatusLabel(transfer.status)}</span></div>
      <div className="mt-4 flex flex-wrap gap-x-5 gap-y-2 text-sm"><span>{transfer.lineCount} {transfer.lineCount === 1 ? 'line' : 'lines'}</span>{transfer.outstandingQuantity > 0 && <span>{transfer.outstandingQuantity} units outstanding</span>}</div>
      <AppLink href={`/organizations/${organizationId}/transfers/${transfer.id}`} className="mt-4 inline-flex min-h-11 items-center rounded-lg text-primary underline underline-offset-4">Open historical transfer</AppLink>
    </li>)}</ul>
    {canLoadMore && <Button variant="outline" disabled={loadingMore} onClick={onLoadMore}>{loadingMore ? 'Loading historical transfers…' : 'Load more historical transfers'}</Button>}
  </section>
}

function TransferQueue({ items, stores, transit, discrepancies, organizationId, onReload, loading, queuePending, queueFetching, queueError, queueErrorMessage,
  canLoadTransfers, loadingTransfers, onLoadTransfers, canLoadTransit, loadingTransit, onLoadTransit,
  transitPending, transitFetching, transitError, retryTransit, canLoadEvidence, loadingEvidence, onLoadEvidence,
  evidencePending, evidenceFetching, evidenceError, retryEvidence }: {
  items: TransferQueueItem[], stores: Map<string, Store>, transit: TransferInTransitLine[], discrepancies: TransferDiscrepancyRecord[],
  organizationId: string, onReload: () => void, loading: boolean, queuePending: boolean, queueFetching: boolean, queueError: boolean, queueErrorMessage: string,
  canLoadTransfers: boolean, loadingTransfers: boolean, onLoadTransfers: () => void,
  canLoadTransit: boolean, loadingTransit: boolean, onLoadTransit: () => void,
  transitPending: boolean, transitFetching: boolean, transitError: boolean, retryTransit: () => void,
  canLoadEvidence: boolean, loadingEvidence: boolean, onLoadEvidence: () => void,
  evidencePending: boolean, evidenceFetching: boolean, evidenceError: boolean, retryEvidence: () => void,
}) {
  const transitByTransfer = new Map<string, TransferInTransitLine[]>()
  for (const line of transit) transitByTransfer.set(line.transferId, [...(transitByTransfer.get(line.transferId) ?? []), line])
  const discrepancyTransfers = new Set(discrepancies.map(item => item.transferId))
  return <section aria-label="Transfer queue" className="space-y-4">
    <div className="flex flex-wrap items-center justify-between gap-3"><h3 className="text-xl font-semibold">Transfer queue</h3><Button variant="outline" disabled={loading} onClick={onReload}>{loading ? 'Refreshing…' : 'Refresh queue'}</Button></div>
    {queuePending && items.length === 0 && <p role="status" className="text-sm">Loading transfer queue…</p>}
    {queueFetching && !queuePending && items.length === 0 && <p role="status" className="text-sm">Refreshing transfer queue…</p>}
    {queueError && <p role="alert" className="text-sm text-destructive">Transfer queue could not be loaded. {queueErrorMessage} <Button variant="outline" onClick={onReload}>Retry queue</Button></p>}
    {queueError && items.length > 0 && <p role="status" className="text-sm">Showing previously loaded transfers; the latest refresh failed.</p>}
    {queueFetching && !queuePending && items.length > 0 && <p role="status" className="text-sm">Refreshing the transfer queue. Previously loaded transfers remain visible.</p>}
    {!queuePending && !queueFetching && !queueError && items.length === 0 && !canLoadTransfers && <p className="text-sm text-muted-foreground">No transfers match the selected queue filters.</p>}
    {!queuePending && !queueFetching && !queueError && items.length === 0 && canLoadTransfers && <p role="status" className="text-sm">No transfers are visible on this page. More results are available.</p>}
    <ul className="grid gap-4">{items.map(item => {
      const age = Math.max(0, ...(transitByTransfer.get(item.id) ?? []).map(line => line.ageSeconds))
      const source = stores.get(item.sourceStoreId); const destination = stores.get(item.destinationStoreId)
      return <li key={item.id} className="min-w-0 rounded-2xl border border-border bg-card p-5">
        <div className="flex flex-wrap items-start justify-between gap-3"><div className="min-w-0"><p className="font-semibold">{source ? `${source.code} · ${source.name}` : `Source store ${item.sourceStoreId.slice(0, 8)}`} <span aria-hidden="true">→</span> {destination ? `${destination.code} · ${destination.name}` : `Destination store ${item.destinationStoreId.slice(0, 8)}`}</p><p className="mt-2 break-all text-xs text-muted-foreground">Transfer {item.id} · updated {new Date(item.updatedAt).toLocaleString()}</p></div><span className="rounded-full border border-border px-3 py-1 text-sm">{transferStatusLabel(item.status)}</span></div>
        <div className="mt-4 flex flex-wrap gap-x-5 gap-y-2 text-sm"><span>{item.lineCount} {item.lineCount === 1 ? 'line' : 'lines'}</span>{item.outstandingQuantity > 0 && <span>{item.outstandingQuantity} units outstanding</span>}{age > 0 && <span className={age > 86400 ? 'font-semibold text-amber-700 dark:text-amber-300' : ''}>In transit for {formatTransferAge(age)}</span>}{discrepancyTransfers.has(item.id) && <span className="font-semibold text-amber-700 dark:text-amber-300">Documented evidence to review</span>}{['IN_TRANSIT', 'PARTIALLY_RECEIVED', 'RECONCILIATION_REQUIRED'].includes(item.status) && <span>Custody history available</span>}</div>
        <AppLink href={`/organizations/${organizationId}/transfers/${item.id}`} className="mt-4 inline-flex min-h-11 items-center rounded-lg text-primary underline underline-offset-4">Open transfer and evidence</AppLink>
      </li>
    })}</ul>
    {canLoadTransfers && <Button variant="outline" disabled={loadingTransfers} onClick={onLoadTransfers}>{loadingTransfers ? 'Loading transfers…' : 'Load more transfers'}</Button>}
    <section aria-label="In-transit lines" className="space-y-3 rounded-2xl border border-border p-5">
      <h4 className="text-lg font-semibold">In-transit lines</h4>
      {transitPending && <p role="status" className="text-sm">Loading outstanding custody lines…</p>}
      {transitFetching && !transitPending && transit.length === 0 && <p role="status" className="text-sm">Refreshing outstanding custody lines…</p>}
      {transitError && <p role="alert" className="text-sm text-destructive">In-transit records could not be loaded. <Button variant="outline" onClick={retryTransit}>Retry in-transit records</Button></p>}
      {transitError && transit.length > 0 && <p role="status" className="text-sm">Showing previously loaded custody lines; the latest refresh failed.</p>}
      {transitFetching && !transitPending && transit.length > 0 && <p role="status" className="text-sm">Refreshing custody lines. Previously loaded lines remain visible.</p>}
      {!transitPending && !transitFetching && !transitError && transit.length === 0 && !canLoadTransit && <p className="text-sm text-muted-foreground">No outstanding in-transit lines are visible for these stores.</p>}
      {!transitPending && !transitFetching && !transitError && transit.length === 0 && canLoadTransit && <p role="status" className="text-sm">No in-transit lines are visible on this page. More results are available.</p>}
      {transit.length > 0 && <ul className="space-y-2">{transit.map(line => <li key={`${line.transferId}:${line.lineId}`} className="flex flex-wrap items-center justify-between gap-3 text-sm"><span>Line {line.lineId.slice(0, 8)} · {line.outstanding} units · {formatTransferAge(line.ageSeconds)}</span><AppLink href={`/organizations/${organizationId}/transfers/${line.transferId}`} className="text-primary underline">Open transfer</AppLink></li>)}</ul>}
      {canLoadTransit && <Button variant="outline" disabled={loadingTransit} onClick={onLoadTransit}>{loadingTransit ? 'Loading in-transit lines…' : 'Load more in-transit lines'}</Button>}
    </section>
    <section aria-label="Transfer evidence history" className="space-y-3 rounded-2xl border border-border p-5">
      <h4 className="text-lg font-semibold">Transfer evidence history</h4>
      {evidencePending && <p role="status" className="text-sm">Loading reconciliation evidence…</p>}
      {evidenceFetching && !evidencePending && discrepancies.length === 0 && <p role="status" className="text-sm">Refreshing reconciliation evidence…</p>}
      {evidenceError && <p role="alert" className="text-sm text-destructive">Transfer evidence could not be loaded. <Button variant="outline" onClick={retryEvidence}>Retry evidence history</Button></p>}
      {evidenceError && discrepancies.length > 0 && <p role="status" className="text-sm">Showing previously loaded evidence; the latest refresh failed.</p>}
      {evidenceFetching && !evidencePending && discrepancies.length > 0 && <p role="status" className="text-sm">Refreshing reconciliation evidence. Previously loaded records remain visible.</p>}
      {!evidencePending && !evidenceFetching && !evidenceError && discrepancies.length === 0 && !canLoadEvidence && <p className="text-sm text-muted-foreground">No transfer reconciliation evidence is visible for these stores.</p>}
      {!evidencePending && !evidenceFetching && !evidenceError && discrepancies.length === 0 && canLoadEvidence && <p role="status" className="text-sm">No evidence is visible on this page. More results are available.</p>}
      {discrepancies.length > 0 && <ul className="space-y-2">{discrepancies.map(item => <li key={item.id} className="flex flex-wrap items-center justify-between gap-3 text-sm"><span>{item.type} · {new Date(item.recordedAt).toLocaleString()} · {discrepancyStatus(item)}</span><AppLink href={`/organizations/${organizationId}/transfers/${item.transferId}`} className="text-primary underline">Open evidence</AppLink></li>)}</ul>}
      {canLoadEvidence && <Button variant="outline" disabled={loadingEvidence} onClick={onLoadEvidence}>{loadingEvidence ? 'Loading evidence…' : 'Load more evidence'}</Button>}
    </section>
  </section>
}

export function CreateTransferPanel({ organizationId, administrator, onCommand, busy, blockedReason }: {
  organizationId: string, administrator: boolean,
  onCommand: (intent: TransferCommandIntent) => Promise<void>, busy: boolean,
  blockedReason?: string,
}) {
  const client = useQueryClient()
  const scope = useTenantKey()
  const [sourceStoreSearch, setSourceStoreSearch] = useState('')
  const [destinationStoreSearch, setDestinationStoreSearch] = useState('')
  const [sourceStoreId, setSourceStoreId] = useState('')
  const [destinationStoreId, setDestinationStoreId] = useState('')
  const [sourceStoreSelection, setSourceStoreSelection] = useState<Store | null>(null)
  const [destinationStoreSelection, setDestinationStoreSelection] = useState<Store | null>(null)
  const [destinationLocationSearch, setDestinationLocationSearch] = useState('')
  const [lines, setLines] = useState<DraftTransferLine[]>([newDraftTransferLine()])
  const [reason, setReason] = useState('')
  const [evidenceReference, setEvidenceReference] = useState('')
  const [showForm, setShowForm] = useState(false)
  const sourceStoresQuery = useInfiniteQuery({
    queryKey: [...scope, 'transfer-create', 'source-stores', sourceStoreSearch], initialPageParam: 0,
    queryFn: ({ signal, pageParam }) => listStores(organizationId, 'ACTIVE', sourceStoreSearch, pageParam, signal),
    getNextPageParam: (page, pages) => page.hasMore ? pages.length * 25 : undefined, staleTime: 0,
  })
  const destinationStoresQuery = useInfiniteQuery({
    queryKey: [...scope, 'transfer-create', 'destination-stores', destinationStoreSearch], initialPageParam: 0,
    queryFn: ({ signal, pageParam }) => listStores(organizationId, 'ACTIVE', destinationStoreSearch, pageParam, signal),
    getNextPageParam: (page, pages) => page.hasMore ? pages.length * 25 : undefined, staleTime: 0,
  })
  const sourceStores = uniqueBy((sourceStoresQuery.data?.pages ?? []).flatMap(page => page.items), store => store.id)
    .filter(store => store.active && store.allowedActions.includes('REQUEST_TRANSFER') && store.allowedActions.includes('READ_INVENTORY'))
  const destinationStores = uniqueBy((destinationStoresQuery.data?.pages ?? []).flatMap(page => page.items), store => store.id)
    .filter(store => store.active && store.id !== sourceStoreId && store.allowedActions.includes('READ_STORE'))
  const sourceStoreCandidates = uniqueBy([...sourceStores, ...(sourceStoreSelection?.id === sourceStoreId ? [sourceStoreSelection] : [])], store => store.id)
  const destinationStoreCandidates = uniqueBy([...destinationStores, ...(destinationStoreSelection?.id === destinationStoreId ? [destinationStoreSelection] : [])], store => store.id)
  const sourceSelection = useQuery({
    queryKey: [...scope, 'transfer-create', 'source-store-selection', sourceStoreId], enabled: showForm && Boolean(sourceStoreId), retry: false, staleTime: 0,
    queryFn: ({ signal }) => getStore(organizationId, sourceStoreId, signal),
  })
  const destinationSelection = useQuery({
    queryKey: [...scope, 'transfer-create', 'destination-store-selection', destinationStoreId], enabled: showForm && Boolean(destinationStoreId), retry: false, staleTime: 0,
    queryFn: ({ signal }) => getStore(organizationId, destinationStoreId, signal),
  })
  const sourceSelectionCompleted = sourceSelection.isSuccess && !sourceSelection.isError && !sourceSelection.isPending
    && !sourceSelection.isFetching && !sourceSelection.isPaused && sourceSelection.isFetchedAfterMount
  const destinationSelectionCompleted = destinationSelection.isSuccess && !destinationSelection.isError && !destinationSelection.isPending
    && !destinationSelection.isFetching && !destinationSelection.isPaused && destinationSelection.isFetchedAfterMount
  const sourceSelectionReady = sourceSelectionCompleted && sourceSelection.data?.id === sourceStoreId
    && sourceSelection.data.active && sourceSelection.data.allowedActions.includes('REQUEST_TRANSFER')
    && sourceSelection.data.allowedActions.includes('READ_INVENTORY')
  const destinationSelectionReady = destinationSelectionCompleted && destinationSelection.data?.id === destinationStoreId
    && destinationSelection.data.active && destinationSelection.data.id !== sourceStoreId
    && destinationSelection.data.allowedActions.includes('READ_STORE')
  const sourceStore = sourceSelectionReady ? sourceSelection.data
      : undefined
  const destinationStore = destinationSelectionReady ? destinationSelection.data : undefined
  const sourceSelectionDenied = (sourceSelection.isError && sourceSelection.error instanceof ApiError
    && (sourceSelection.error.status === 403 || sourceSelection.error.status === 404))
    || (sourceSelectionCompleted && !sourceSelectionReady)
  const destinationSelectionDenied = (destinationSelection.isError && destinationSelection.error instanceof ApiError
    && (destinationSelection.error.status === 403 || destinationSelection.error.status === 404))
    || (destinationSelectionCompleted && !destinationSelectionReady)
  const sourcePositionKey = useMemo(() => [...scope, 'transfer-create', 'positions', sourceStoreId] as const, [scope, sourceStoreId])
  const sourceLocationKey = useMemo(() => [...scope, 'transfer-create', 'source-location', sourceStoreId] as const, [scope, sourceStoreId])
  const destinationLocationKey = useMemo(() => [...scope, 'transfer-create', 'destination-locations', destinationStoreId] as const, [scope, destinationStoreId])
  const laneKey = useMemo(() => [...scope, 'transfer-create', 'lane', sourceStoreId, destinationStoreId] as const, [scope, sourceStoreId, destinationStoreId])
  useEffect(() => {
    if (!sourceStoreId || sourceStore || sourceSelectionDenied) return
    void client.cancelQueries({ queryKey: sourcePositionKey })
    void client.cancelQueries({ queryKey: sourceLocationKey })
    if (destinationStoreId) void client.cancelQueries({ queryKey: laneKey })
  }, [client, sourceStoreId, sourceStore, sourceSelectionDenied, sourcePositionKey, sourceLocationKey, destinationStoreId, laneKey])
  useEffect(() => {
    if (!destinationStoreId || destinationStore || destinationSelectionDenied) return
    void client.cancelQueries({ queryKey: destinationLocationKey })
    void client.cancelQueries({ queryKey: laneKey })
  }, [client, destinationStoreId, destinationStore, destinationSelectionDenied, destinationLocationKey, laneKey])
  useEffect(() => {
    if (!sourceStoreId || !sourceSelectionDenied) return
    const cancellations = [
      client.cancelQueries({ queryKey: sourcePositionKey }),
      client.cancelQueries({ queryKey: sourceLocationKey }),
    ]
    if (destinationStoreId) cancellations.push(client.cancelQueries({ queryKey: laneKey }))
    void Promise.all(cancellations).then(() => {
      client.removeQueries({ queryKey: sourcePositionKey })
      client.removeQueries({ queryKey: sourceLocationKey })
      if (destinationStoreId) client.removeQueries({ queryKey: laneKey })
    })
  }, [client, sourceStoreId, sourceSelectionDenied, destinationStoreId, scope, sourcePositionKey, sourceLocationKey, laneKey])
  useEffect(() => {
    if (!destinationStoreId || !destinationSelectionDenied) return
    void Promise.all([
      client.cancelQueries({ queryKey: destinationLocationKey }),
      client.cancelQueries({ queryKey: laneKey }),
    ]).then(() => {
      client.removeQueries({ queryKey: destinationLocationKey })
      client.removeQueries({ queryKey: laneKey })
    })
  }, [client, destinationStoreId, destinationSelectionDenied, scope, destinationLocationKey, laneKey])
  const selectedSourceStoreId = sourceStoreId
  const selectedDestinationStoreId = destinationStoreId
  const positions = useInfiniteQuery({ queryKey: sourcePositionKey, enabled: Boolean(sourceStore), initialPageParam: 0,
    queryFn: ({ signal, pageParam }) => listInventoryAvailableByLot(organizationId, selectedSourceStoreId, { offset: pageParam, limit: 100 }, signal),
    getNextPageParam: (page, pages) => page.hasMore ? pages.length * 100 : undefined, staleTime: 0 })
  const sourcePositions = sourceStore ? (positions.data?.pages ?? []).flatMap(page => page.items) : []
  const destinationLocations = useInfiniteQuery({
    queryKey: [...destinationLocationKey, destinationLocationSearch],
    enabled: Boolean(destinationStore), initialPageParam: 0,
    queryFn: ({ signal, pageParam }) => listStorageLocations(organizationId, selectedDestinationStoreId, false, destinationLocationSearch, pageParam, signal),
    getNextPageParam: (page, pages) => page.hasMore ? pages.length * 25 : undefined, staleTime: 0,
  })
  const destinationLocationItems = destinationStore
    ? uniqueBy((destinationLocations.data?.pages ?? []).flatMap(page => page.items), location => location.id) : []
  const selectedPositions = uniqueBy(lines.flatMap(line => sourcePositions.filter(item => item.positionId === line.sourcePositionId)), item => item.positionId)
  const selectedProducts = [...new Set(selectedPositions.map(position => position.productId))]
  const selectedLots = uniqueBy(selectedPositions, position => `${position.productId}:${position.lotId}`)
  const selectedSourceLocations = [...new Set(selectedPositions.map(position => position.locationId))]
  const productQueries = useQueries({ queries: selectedProducts.map(productId => ({
    queryKey: [...scope, 'transfer-create', 'product', productId], queryFn: ({ signal }: { signal: AbortSignal }) => getProduct(organizationId, productId, signal), staleTime: 60000,
  })) })
  const lotQueries = useQueries({ queries: selectedLots.map(position => ({
    queryKey: [...scope, 'transfer-create', 'lot', position.lotId], queryFn: ({ signal }: { signal: AbortSignal }) => getLot(organizationId, position.lotId, signal), staleTime: 60000,
  })) })
  const sourceLocationQueries = useQueries({ queries: selectedSourceLocations.map(locationId => ({
    queryKey: [...sourceLocationKey, locationId],
    queryFn: ({ signal }: { signal: AbortSignal }) => getStorageLocation(organizationId, selectedSourceStoreId, locationId, signal), staleTime: 0,
  })) })
  const route = useQuery({ queryKey: laneKey, enabled: administrator && Boolean(sourceStore && destinationStore), queryFn: ({ signal }) => lookupLane(organizationId, selectedSourceStoreId, selectedDestinationStoreId, signal), staleTime: 0 })
  const positionById = new Map(sourcePositions.map(position => [position.positionId, position]))
  const sourceLocationById = new Map(selectedSourceLocations.flatMap((id, index) => sourceLocationQueries[index]?.data ? [[id, sourceLocationQueries[index]!.data!]] : []))
  const destinationLocationById = new Map(destinationLocationItems.map(location => [location.id, location]))
  const productById = new Map(selectedProducts.flatMap((id, index) => productQueries[index]?.data ? [[id, productQueries[index]!.data!]] : []))
  const lotById = new Map(selectedLots.flatMap((position, index) => lotQueries[index]?.data ? [[`${position.productId}:${position.lotId}`, lotQueries[index]!.data!]] : []))
  const selectedPositionIds = lines.map(line => line.sourcePositionId).filter(Boolean)
  const duplicatePosition = new Set(selectedPositionIds).size !== selectedPositionIds.length
  const validLines = lines.length >= 1 && lines.length <= 50 && lines.every(line => {
    const position = positionById.get(line.sourcePositionId)
    const destination = destinationLocationById.get(line.destinationLocationId)
    const quantity = Number(line.quantity)
    return Boolean(position && position.initialization === 'INITIALIZED' && position.unit === 'EA'
      && position.available !== null && Number.isSafeInteger(quantity) && quantity > 0 && quantity <= position.available
      && destination?.usable)
  }) && !duplicatePosition
  const aggregateAvailability = (() => {
    const quantities = new Map<string, number>()
    for (const line of lines) {
      const position = positionById.get(line.sourcePositionId)
      const quantity = Number(line.quantity)
      if (!position || !Number.isSafeInteger(quantity) || quantity <= 0) continue
      const key = `${position.productId}:${position.lotId}`
      quantities.set(key, (quantities.get(key) ?? 0) + quantity)
    }
    return [...quantities].every(([key, quantity]) => {
      const [productId, lotId] = key.split(':')
      const available = sourcePositions.filter(position => position.productId === productId && position.lotId === lotId)
        .reduce((sum, position) => sum + (position.available ?? 0), 0)
      return Number.isSafeInteger(quantity) && quantity <= available
    })
  })()
  const allRoutesMatch = lines.every(line => {
    const position = positionById.get(line.sourcePositionId)
    const sourceLocation = position && sourceLocationById.get(position.locationId)
    const destinationLocation = destinationLocationById.get(line.destinationLocationId)
    const lane = route.data?.lane
    return !administrator || Boolean(lane && sourceLocation?.zoneId === lane.originZoneId && destinationLocation?.zoneId === lane.destinationZoneId)
  })
  const routeAllowsRequest = !administrator || route.data?.status === 'CONFIGURED'
  const sourceAvailabilityReady = Boolean(sourceStore && positions.data && !positions.isPending && !positions.isFetching && !positions.isPaused && !positions.isError)
  const destinationLocationsReady = Boolean(destinationStore && destinationLocations.data && !destinationLocations.isPending
    && !destinationLocations.isFetching && !destinationLocations.isPaused && !destinationLocations.isError)
  const routeReadReady = !administrator || Boolean(route.data && !route.isPending && !route.isFetching && !route.isPaused && !route.isError)
  const sourceLocationsReady = !administrator || sourceLocationQueries.every(query => query.isSuccess && !query.isFetching && !query.isPaused && !query.isError)
  const canSubmit = Boolean(sourceStore && destinationStore && positions.data && destinationLocations.data
    && sourceAvailabilityReady && destinationLocationsReady && routeReadReady && sourceLocationsReady
    && validLines && aggregateAvailability && allRoutesMatch && reason.trim() && evidenceReference.trim() && routeAllowsRequest && !busy && !blockedReason)

  function updateLine(id: string, patch: Partial<DraftTransferLine>) {
    setLines(current => current.map(line => line.id === id ? { ...line, ...patch } : line))
  }

  return <section className="space-y-4 rounded-2xl border border-border bg-card p-5">
    <div className="flex flex-wrap items-start justify-between gap-3"><div><h3 className="text-xl font-semibold">Create transfer request</h3><p className="mt-2 text-sm text-muted-foreground">A request records intent only. Stock is reserved after an authorized approver accepts the current source position.</p></div><Button variant="outline" onClick={() => { setShowForm(value => !value) }}>{showForm ? 'Close request form' : 'New transfer request'}</Button></div>
    {showForm && <form className="grid gap-4 sm:grid-cols-2" onSubmit={event => {
      event.preventDefault()
      if (!canSubmit) return
      const body = { requestId: crypto.randomUUID(), sourceStoreId: selectedSourceStoreId, destinationStoreId: selectedDestinationStoreId,
        lines: lines.map(line => {
          const position = positionById.get(line.sourcePositionId)!
          return { sourcePositionId: position.positionId, destinationLocationId: line.destinationLocationId,
            quantity: Number(line.quantity), expectedSourceVersion: position.positionVersion }
        }),
        reason: reason.trim(), evidenceReference: evidenceReference.trim() }
      void onCommand({ operation: 'CREATE', transferId: null, body })
    }}>
      <Field label="Find a source store">{id => <div className="flex gap-2"><input id={id} type="search" value={sourceStoreSearch} onChange={event => { setSourceStoreSearch(event.target.value) }} className={inputClass} placeholder="Search source stores by code or name" />{sourceStoreSearch && <Button type="button" variant="outline" onClick={() => { setSourceStoreSearch('') }}>Clear source search</Button>}</div>}</Field>
      <Field label="Find a destination store">{id => <div className="flex gap-2"><input id={id} type="search" value={destinationStoreSearch} onChange={event => { setDestinationStoreSearch(event.target.value) }} className={inputClass} placeholder="Search destination stores by code or name" />{destinationStoreSearch && <Button type="button" variant="outline" onClick={() => { setDestinationStoreSearch('') }}>Clear destination search</Button>}</div>}</Field>
      <div className="flex flex-wrap items-end gap-3"><Field label="Source store">{id => <select id={id} required value={sourceStoreId} onChange={event => {
        const nextStoreId = event.target.value
        const selected = sourceStoreCandidates.find(store => store.id === nextStoreId) ?? null
        setSourceStoreId(nextStoreId); setSourceStoreSelection(selected); setLines([newDraftTransferLine()])
        if (nextStoreId === destinationStoreId) { setDestinationStoreId(''); setDestinationStoreSelection(null); setLines([newDraftTransferLine()]) }
      }} className={inputClass}><option value="">Choose a source</option>{sourceStoreCandidates.map(store => <option key={store.id} value={store.id}>{store.code} · {store.name}</option>)}</select>}</Field><Field label="Destination store">{id => <select id={id} required value={destinationStoreId} onChange={event => {
        const nextStoreId = event.target.value
        setDestinationStoreId(nextStoreId); setDestinationStoreSelection(destinationStoreCandidates.find(store => store.id === nextStoreId) ?? null)
        setLines(current => current.map(line => ({ ...line, destinationLocationId: '' })))
      }} className={inputClass}><option value="">Choose a destination</option>{destinationStoreCandidates.filter(store => store.active && store.id !== sourceStoreId && store.allowedActions.includes('READ_STORE')).map(store => <option key={store.id} value={store.id}>{store.code} · {store.name}</option>)}</select>}</Field></div>
      {sourceStoresQuery.hasNextPage && <Button type="button" variant="outline" disabled={sourceStoresQuery.isFetchingNextPage} onClick={() => { void sourceStoresQuery.fetchNextPage() }}>{sourceStoresQuery.isFetchingNextPage ? 'Loading source stores…' : 'Load more source stores'}</Button>}
      {destinationStoresQuery.hasNextPage && <Button type="button" variant="outline" disabled={destinationStoresQuery.isFetchingNextPage} onClick={() => { void destinationStoresQuery.fetchNextPage() }}>{destinationStoresQuery.isFetchingNextPage ? 'Loading destination stores…' : 'Load more destination stores'}</Button>}
      {sourceStores.length === 0 && !sourceStoresQuery.isPending && !sourceStoresQuery.isError && <div role="status" className="sm:col-span-2 text-sm">{sourceStoreSearch ? 'No eligible source stores match this search.' : 'No active store currently has both transfer request and inventory read access.'} {sourceStoreSearch && <Button type="button" variant="outline" onClick={() => { setSourceStoreSearch('') }}>Clear source search</Button>}</div>}
      {destinationStores.length === 0 && !destinationStoresQuery.isPending && !destinationStoresQuery.isError && <div role="status" className="sm:col-span-2 text-sm">{destinationStoreSearch ? 'No eligible destination stores match this search.' : 'No active destination stores with store read access are available.'} {destinationStoreSearch && <Button type="button" variant="outline" onClick={() => { setDestinationStoreSearch('') }}>Clear destination search</Button>}</div>}
      {sourceStoresQuery.isError && <p role="alert" className="sm:col-span-2 text-sm text-destructive">Source store access could not be checked. {errorMessage(sourceStoresQuery.error)} <Button type="button" variant="outline" onClick={() => { void sourceStoresQuery.refetch() }}>Retry source stores</Button></p>}
      {destinationStoresQuery.isError && <p role="alert" className="sm:col-span-2 text-sm text-destructive">Destination store access could not be checked. {errorMessage(destinationStoresQuery.error)} <Button type="button" variant="outline" onClick={() => { void destinationStoresQuery.refetch() }}>Retry destination stores</Button></p>}
      {sourceStoreId && sourceSelection.isError && <p role="alert" className="sm:col-span-2 text-sm text-destructive">The selected source store could not be revalidated. {errorMessage(sourceSelection.error)} <Button type="button" variant="outline" onClick={() => { void sourceSelection.refetch() }}>Retry source access</Button></p>}
      {sourceStoreId && (sourceSelection.isPending || sourceSelection.isFetching || sourceSelection.isPaused) && <p role="status" className="sm:col-span-2 text-sm">Rechecking source store access…</p>}
      {sourceStoreId && sourceSelectionCompleted && !sourceStore && <p role="alert" className="sm:col-span-2 text-sm text-destructive">The selected source store is no longer active or you no longer have the required transfer and inventory access. Choose an eligible source again.</p>}
      {destinationStoreId && destinationSelection.isError && <p role="alert" className="sm:col-span-2 text-sm text-destructive">The selected destination store could not be revalidated. {errorMessage(destinationSelection.error)} <Button type="button" variant="outline" onClick={() => { void destinationSelection.refetch() }}>Retry destination access</Button></p>}
      {destinationStoreId && (destinationSelection.isPending || destinationSelection.isFetching || destinationSelection.isPaused) && <p role="status" className="sm:col-span-2 text-sm">Rechecking destination store access…</p>}
      {destinationStoreId && destinationSelectionCompleted && !destinationStore && <p role="alert" className="sm:col-span-2 text-sm text-destructive">The selected destination store is no longer active, readable or distinct from the source. Choose an eligible destination again.</p>}
      <div className="space-y-4 sm:col-span-2" aria-label="Transfer lines">
        {lines.map((line, index) => {
          const position = positionById.get(line.sourcePositionId)
          const sourceLocation = position && sourceLocationById.get(position.locationId)
          const destinationLocation = destinationLocationById.get(line.destinationLocationId)
          const product = position && productById.get(position.productId)
          const lot = position && lotById.get(`${position.productId}:${position.lotId}`)
          const positionLabel = index === 0 ? 'Source product and lot' : `Source product and lot for line ${index + 1}`
          const destinationLabel = index === 0 ? 'Destination receiving location' : `Destination receiving location for line ${index + 1}`
          const quantityLabel = index === 0 ? `Quantity (${position?.unit ?? 'EA'})` : `Quantity for line ${index + 1} (${position?.unit ?? 'EA'})`
          return <fieldset key={line.id} className="grid min-w-0 gap-4 rounded-xl border border-border p-4 sm:grid-cols-2">
            <legend className="px-2 font-semibold">Transfer line {index + 1} of {lines.length}</legend>
            <Field label={positionLabel}>{id => <select id={id} required value={line.sourcePositionId} onChange={event => { updateLine(line.id, { sourcePositionId: event.target.value, quantity: '' }) }} disabled={positions.isPending || positions.isFetching || positions.isError || !sourceStore} className={inputClass}><option value="">Choose an available stock position</option>{sourcePositions.map(item => {
        const itemProduct = productById.get(item.productId)
        const itemLot = lotById.get(`${item.productId}:${item.lotId}`)
        const free = item.available === null ? 'count unknown' : `${item.available} ${item.unit} available`
        const duplicate = selectedPositionIds.includes(item.positionId) && item.positionId !== line.sourcePositionId
        return <option key={item.positionId} value={item.positionId} disabled={duplicate || item.initialization !== 'INITIALIZED' || item.unit !== 'EA' || item.available === null || item.available <= 0}>{itemProduct ? `${itemProduct.sku} · ${itemProduct.name}` : `Product ${item.productId.slice(0, 8)}`} · {itemLot?.lotCode ?? `Lot ${item.lotId.slice(0, 8)}`} · location ${item.locationId.slice(0, 8)} · {free}{duplicate ? ' · already selected' : ''}</option>
      })}</select>}</Field>
            <Field label="Search destination locations">{id => <input id={id} type="search" value={destinationLocationSearch} onChange={event => { setDestinationLocationSearch(event.target.value) }} className={inputClass} placeholder="Search location code or name" />}</Field>
            <Field label={destinationLabel}>{id => <select id={id} required value={line.destinationLocationId} onChange={event => { updateLine(line.id, { destinationLocationId: event.target.value }) }} disabled={!destinationStore || destinationLocations.isPending || destinationLocations.isFetching || destinationLocations.isError} className={inputClass}><option value="">Choose an active location</option>{destinationLocationItems.map(location => <option key={location.id} value={location.id} disabled={!location.usable}>{location.code} · {location.name} · {location.zoneId}{location.usable ? '' : ' · unavailable'}</option>)}</select>}</Field>
            {position && <Field label={quantityLabel}>{id => <input id={id} type="number" min="1" max={position.available ?? undefined} step="1" required value={line.quantity} onChange={event => { updateLine(line.id, { quantity: event.target.value }) }} className={inputClass} />}</Field>}
            <div className="min-w-0 text-sm sm:col-span-2"><p>Source zone: {sourceLocation?.zoneId ?? (position ? 'Loading source location' : 'Choose source stock')} · destination zone: {destinationLocation?.zoneId ?? 'Choose receiving location'}</p>
              {position && <p className="mt-1">Observed source position version: {position.positionVersion}. Product {product?.sku ?? position.productId.slice(0, 8)} · lot {lot?.lotCode ?? position.lotId.slice(0, 8)}.</p>}
              {position?.initialization !== 'INITIALIZED' && position && <p role="alert" className="mt-1 text-amber-700 dark:text-amber-300">This position has no confirmed physical count and cannot be requested.</p>}
              {position?.trust === 'UNVERIFIED' && <p className="mt-1 text-amber-700 dark:text-amber-300">This stock position is unverified. Approval will recheck its lot safety and reservation eligibility.</p>}
              {administrator && position && destinationLocation && route.data?.lane && (sourceLocation?.zoneId !== route.data.lane.originZoneId || destinationLocation.zoneId !== route.data.lane.destinationZoneId) && <p role="alert" className="mt-1 text-destructive">This line uses zones that do not match the configured transport lane.</p>}
            </div>
            {lines.length > 1 && <Button type="button" variant="outline" onClick={() => { setLines(current => current.length > 1 ? current.filter(item => item.id !== line.id) : current) }} aria-label={`Remove transfer line ${index + 1}`}>Remove line</Button>}
          </fieldset>
        })}
        {sourceStore && positions.hasNextPage && <Button type="button" variant="outline" disabled={positions.isFetching || positions.isError} onClick={() => { void positions.fetchNextPage() }}>{positions.isFetching ? 'Loading source positions…' : 'Load more available source positions'}</Button>}
        {destinationStore && destinationLocations.hasNextPage && <Button type="button" variant="outline" disabled={destinationLocations.isFetching || destinationLocations.isError} onClick={() => { void destinationLocations.fetchNextPage() }}>{destinationLocations.isFetching ? 'Loading locations…' : 'Load more destination locations'}</Button>}
        <Button type="button" variant="outline" disabled={lines.length >= 50} onClick={() => { setLines(current => current.length < 50 ? [...current, newDraftTransferLine()] : current) }}>Add transfer line</Button>
        {duplicatePosition && <p role="alert" className="text-sm text-destructive">Choose a different source position for each line.</p>}
        {!aggregateAvailability && <p role="alert" className="text-sm text-destructive">Combined line quantities exceed the observed source availability for a product and lot.</p>}
        {administrator && sourceStore && destinationStore && lines.some(line => line.sourcePositionId && line.destinationLocationId) && !allRoutesMatch && route.data?.lane && <p role="alert" className="text-sm text-destructive">One or more lines use zones outside the configured lane.</p>}
      </div>
      <div className="sm:col-span-2 rounded-xl border border-border p-4 text-sm"><h4 className="font-semibold">Route and storage context</h4>
        {administrator && sourceStore && destinationStore && (route.isPending ? <p role="status" className="mt-2">Checking configured transport route…</p> : route.isError ? <p role="alert" className="mt-2 text-destructive">{errorMessage(route.error)} <Button type="button" variant="outline" onClick={() => { void route.refetch() }}>Retry route check</Button></p> : <p className="mt-2">Transport route: {route.data?.status === 'CONFIGURED' ? 'Configured and available' : `Unavailable (${route.data?.status ?? 'not checked'})`}. The server rechecks lane, zones and stock when each command is saved.</p>)}
        {!administrator && <p className="mt-2">The server checks the current transport route, compatible zones and stock state when you submit.</p>}</div>
      {sourceStore && positions.isError && <p role="alert" className="sm:col-span-2 text-sm text-destructive">Source availability could not be checked: {errorMessage(positions.error)} <Button type="button" variant="outline" onClick={() => { void positions.refetch() }}>Retry availability</Button></p>}
      {((sourceStore && sourceLocationQueries.some(query => query.isError)) || (destinationStore && destinationLocations.isError)) && <p role="alert" className="sm:col-span-2 text-sm text-destructive">Storage locations could not be checked. Confirm store access and retry the failed read. <Button type="button" variant="outline" onClick={() => { if (sourceStore) for (const query of sourceLocationQueries) if (query.isError) void query.refetch(); if (destinationStore && destinationLocations.isError) void destinationLocations.refetch() }}>Retry locations</Button></p>}
      {positions.isPending && sourceStore && <p role="status" className="sm:col-span-2">Loading current source availability…</p>}
      <Field label="Reason for transfer">{id => <textarea id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
      <Field label="Evidence reference">{id => <input id={id} required maxLength={256} value={evidenceReference} onChange={event => { setEvidenceReference(event.target.value) }} className={inputClass} placeholder="Document or review reference" />}</Field>
      <Button type="submit" className="sm:col-span-2" disabled={!canSubmit}>{busy ? 'Saving request…' : blockedReason ?? 'Create transfer request'}</Button>
    </form>}
  </section>
}

export function TransferDetailView({ organizationId, transfer, stores, onCommand, busy, onRefresh }: {
  organizationId: string, transfer: Transfer, stores: Store[],
  onCommand: (intent: TransferCommandIntent) => Promise<void>, busy: boolean,
  onRefresh: () => Promise<void>,
}) {
  const scope = useTenantKey()
  const storeMap = new Map(stores.map(store => [store.id, store]))
  const historicalParticipants = transfer.historicalParticipants
  const capabilities = useQuery({ queryKey: [...scope, 'transfers', 'capabilities', transfer.id],
    queryFn: ({ signal }) => getTransferCapabilities(organizationId, transfer.id, signal), refetchOnWindowFocus: true, staleTime: 0 })
  const sourceActions = capabilities.data?.sourceActions ?? []
  const destinationActions = capabilities.data?.destinationActions ?? []
  const sourceMetadata = useQuery({ queryKey: [...scope, 'transfers', 'participant-store', transfer.sourceStoreId],
    enabled: !storeMap.has(transfer.sourceStoreId) && sourceActions.includes('READ_STORE'),
    queryFn: ({ signal }) => getStore(organizationId, transfer.sourceStoreId, signal), staleTime: 0 })
  const destinationMetadata = useQuery({ queryKey: [...scope, 'transfers', 'participant-store', transfer.destinationStoreId],
    enabled: !storeMap.has(transfer.destinationStoreId) && destinationActions.includes('READ_STORE'),
    queryFn: ({ signal }) => getStore(organizationId, transfer.destinationStoreId, signal), staleTime: 0 })
  const source = historicalParticipants?.source ?? storeMap.get(transfer.sourceStoreId) ?? sourceMetadata.data
  const destination = historicalParticipants?.destination ?? storeMap.get(transfer.destinationStoreId) ?? destinationMetadata.data
  const custody = useInfiniteQuery({ queryKey: [...scope, 'transfers', 'custody', transfer.id], initialPageParam: null as string | null,
    queryFn: ({ signal, pageParam }) => listTransferCustody(organizationId, transfer.id, signal, pageParam, 50),
    getNextPageParam: page => page.hasMore ? page.nextCursor ?? undefined : undefined, refetchOnWindowFocus: true, staleTime: 0 })
  const transitQuery = useInfiniteQuery({ queryKey: [...scope, 'transfers', 'detail-transit', transfer.id], initialPageParam: null as string | null,
    queryFn: ({ signal, pageParam }) => listTransferInTransit(organizationId, transfer.id, signal, pageParam, 50),
    getNextPageParam: page => page.hasMore ? page.nextCursor ?? undefined : undefined, staleTime: 0 })
  const discrepancyQuery = useInfiniteQuery({ queryKey: [...scope, 'transfers', 'detail-discrepancies', transfer.id], initialPageParam: null as string | null,
    queryFn: ({ signal, pageParam }) => listTransferScopedDiscrepancies(organizationId, transfer.id, signal, pageParam, 50),
    getNextPageParam: page => page.hasMore ? page.nextCursor ?? undefined : undefined, staleTime: 0 })
  const activeReturnQuery = useQuery({ queryKey: [...scope, 'transfers', 'active-returns', transfer.id],
    queryFn: ({ signal }) => listActiveTransferReturns(organizationId, transfer.id, signal), refetchOnWindowFocus: true, staleTime: 0 })
  const custodyItems = (custody.data?.pages ?? []).flatMap(page => page.items)
  const transitItems = uniqueBy((transitQuery.data?.pages ?? []).flatMap(page => page.items), line => `${line.transferId}:${line.lineId}`)
  const detailDiscrepancies = uniqueBy((discrepancyQuery.data?.pages ?? []).flatMap(page => page.items), item => item.id)
  const pendingReturns: PendingReturnLine[] = (activeReturnQuery.data?.items ?? []).map(item => ({
    lineId: item.lineId, requestId: item.requestId, requestReference: item.requestReference,
    requestedQuantity: item.requestedQuantity, settledQuantity: item.settledQuantity, unsettledQuantity: item.unsettledQuantity,
  }))
  const canReceivePhysicalSettlement = capabilities.data?.canReceivePhysicalSettlement ?? false
  const canConfirmPhysicalReturn = capabilities.data?.canConfirmPhysicalReturn ?? false
  const historicalSettlementActions = capabilities.data?.historicalSettlementActions ?? []
  const canRequestHistoricalReturn = historicalSettlementActions.includes('REQUEST_RETURN')
  const canConfirmHistoricalLoss = historicalSettlementActions.includes('CONFIRM_LOSS')
  const canOpenHistoricalDiscrepancy = historicalSettlementActions.includes('OPEN_DISCREPANCY')
  const canCloseHistoricalDiscrepancy = historicalSettlementActions.includes('CLOSE')
  const sourceCanConfirmReturn = sourceActions.includes('RECEIVE_STOCK') && sourceActions.includes('READ_INVENTORY')
    || canConfirmPhysicalReturn
  const historicalPositions = useQuery({ queryKey: [...scope, 'transfers', 'settlement-positions', transfer.id, transfer.version],
    enabled: canReceivePhysicalSettlement || canConfirmPhysicalReturn,
    queryFn: ({ signal }) => getTransferSettlementPositions(organizationId, transfer.id, signal), staleTime: 0 })
  const regularReturnPositionQueries = useQueries({ queries: pendingReturns.map(item => {
    const line = transfer.lines.find(candidate => candidate.id === item.lineId)!
    return { queryKey: [...scope, 'transfers', 'return-source-position', transfer.id, item.lineId, transfer.version],
      enabled: sourceCanConfirmReturn && !canConfirmPhysicalReturn,
      queryFn: ({ signal }: { signal: AbortSignal }) => getInventorySnapshot(organizationId, transfer.sourceStoreId, line.sourcePositionId, signal), staleTime: 0 }
  }) })
  const returnPositionQueries: ReturnPositionQuery[] = canConfirmPhysicalReturn
    ? pendingReturns.map(item => {
      const saved = historicalPositions.data?.sourcePositions.find(position => position.lineId === item.lineId)
      return { data: saved ? { version: saved.version } : undefined, isPending: historicalPositions.isPending,
        isError: historicalPositions.isError || Boolean(historicalPositions.data && !saved), refetch: () => historicalPositions.refetch() }
    })
    : regularReturnPositionQueries
  const canReadDestinationStock = destinationActions.includes('READ_INVENTORY')
  const receivingPositions = useQuery({ queryKey: [...scope, 'transfers', 'destination-positions', transfer.id, transfer.version], enabled: canReadDestinationStock && !canReceivePhysicalSettlement && transfer.lines.some(line => outstanding(line) > 0),
    queryFn: async ({ signal }) => {
      const records = await Promise.all(transfer.lines.map(async line => {
        const page = await listInventoryPositions(organizationId, transfer.destinationStoreId,
          { productId: line.productId, lotId: line.lotId, locationId: line.destinationLocationId, offset: 0, limit: 100 }, signal)
        return [line.id, page.items.find(position => position.productId === line.productId && position.lotId === line.lotId && position.locationId === line.destinationLocationId) ?? null] as const
      }))
      return new Map(records)
    }, staleTime: 0 })
  const destinationPositions = canReceivePhysicalSettlement
    ? new Map(historicalPositions.data?.destinationPositions.map(position => [position.lineId, { version: position.version }]) ?? [])
    : receivingPositions.data
  const ageSeconds = Math.max(0, ...transitItems.map(line => line.ageSeconds))
  const firstDispatchAt = [...transitItems].sort((left, right) => left.dispatchedAt.localeCompare(right.dispatchedAt))[0]?.dispatchedAt
  const nowVersion = transfer.version

  function command(operation: Extract<TransferCommandIntent['operation'], 'APPROVE' | 'REJECT' | 'START_PICK' | 'CLOSE'>, reason: string, evidenceReference: string) {
    const body = { requestId: crypto.randomUUID(), expectedVersion: nowVersion, reason: reason.trim(), evidenceReference: evidenceReference.trim() }
    void onCommand({ operation, transferId: transfer.id, body })
  }

  return <section className="space-y-7">
    <div className="flex flex-wrap items-start justify-between gap-3"><div><AppLink href={`/organizations/${organizationId}/transfers`} className="text-sm text-primary underline">Back to transfer queue</AppLink><h3 className="mt-3 break-all text-2xl font-semibold">Transfer {transfer.id}</h3><p className="mt-2"><span>{source ? `${source.code} · ${source.name}` : `Source ${transfer.sourceStoreId}`}{source && !source.active && <span className="ml-2 rounded-full border border-border px-2 py-0.5 text-xs">Archived</span>}</span> <span aria-hidden="true">→</span> <span>{destination ? `${destination.code} · ${destination.name}` : `Destination ${transfer.destinationStoreId}`}{destination && !destination.active && <span className="ml-2 rounded-full border border-border px-2 py-0.5 text-xs">Archived</span>}</span></p><p className="mt-2 text-sm text-muted-foreground">{transferStatusLabel(transfer.status)} · version {transfer.version} · outstanding custody {transfer.outstandingQuantity} units</p></div><Button variant="outline" disabled={busy} onClick={() => { void onRefresh() }}>Refresh saved record</Button></div>
    {capabilities.isPending && <p role="status" className="rounded-xl border border-border p-4 text-sm">Checking current actions for both transfer participants…</p>}
    {capabilities.isError && <p role="alert" className="rounded-xl border border-destructive/30 p-4 text-sm text-destructive">Current transfer capabilities could not be checked, so participant actions remain unavailable. {errorMessage(capabilities.error)} <Button variant="outline" onClick={() => { void capabilities.refetch() }}>Retry capability check</Button></p>}
    {(historicalSettlementActions.length > 0 || canReceivePhysicalSettlement || canConfirmPhysicalReturn) && <p role="note" aria-label="Administrative historical settlement" className="rounded-xl border border-border p-4 text-sm">Administrative settlement of this existing historical transfer. This does not reactivate either store. Confirm the physical facts and record the required documents and evidence before proceeding.</p>}
    {transfer.status === 'RECONCILIATION_REQUIRED' && transfer.outstandingQuantity === 0 && <p role="status" className="rounded-xl border border-border p-4 text-sm">All units are accounted for. A manager must review the recorded evidence and confirm reconciliation before this transfer is finalized.</p>}
    {ageSeconds > 0 && firstDispatchAt && <p className="rounded-xl border border-border p-4 text-sm">Outstanding goods have been in custody for {formatTransferAge(ageSeconds)}. First dispatch: {new Date(firstDispatchAt).toLocaleString()}.</p>}
    <section aria-labelledby="transfer-lines-heading" className="space-y-3"><h4 id="transfer-lines-heading" className="text-lg font-semibold">Lines and stock custody</h4><ul className="grid gap-3">{transfer.lines.map(line => <li key={line.id} className="space-y-3 rounded-xl border border-border p-4"><div className="flex flex-wrap items-start justify-between gap-3"><div><p className="font-semibold">Line {line.lineNumber} · {line.sku} · lot {line.lotCode}</p><p className="mt-1 break-all text-sm text-muted-foreground">{line.requestedQuantity} {line.unit} requested · source position {line.sourcePositionId.slice(0, 8)} · destination location {line.destinationLocationId.slice(0, 8)}</p></div><span className="text-sm">{line.picked ? 'Physical pick recorded' : transfer.status === 'PICKING' ? 'Awaiting full-line pick' : 'Not picked'}</span></div><dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-5"><Quantity label="Dispatched" value={line.quantities.dispatched ?? 0} unit={line.unit} /><Quantity label="Received" value={line.quantities.physicallyReceived ?? 0} unit={line.unit} /><Quantity label="Confirmed loss" value={line.quantities.confirmedLoss ?? 0} unit={line.unit} /><Quantity label="Returned" value={line.quantities.physicallyReturned ?? 0} unit={line.unit} /><Quantity label="Outstanding" value={line.quantities.outstanding ?? 0} unit={line.unit} /></dl></li>)}</ul></section>

    {transfer.status === 'REQUESTED' && sourceActions.includes('APPROVE_TRANSFER') && <DecisionAction title="Review request" primaryLabel="Approve and reserve stock" onSubmit={(reason, evidence) => { command('APPROVE', reason, evidence) }} busy={busy} />}
    {transfer.status === 'REQUESTED' && sourceActions.includes('APPROVE_TRANSFER') && <DecisionAction title="Reject request" primaryLabel="Reject transfer" onSubmit={(reason, evidence) => { command('REJECT', reason, evidence) }} busy={busy} />}
    {transfer.status === 'APPROVED' && sourceActions.includes('PICK_TRANSFER') && <DecisionAction title="Begin physical picking" primaryLabel="Start picking" onSubmit={(reason, evidence) => { command('START_PICK', reason, evidence) }} busy={busy} />}
    {transfer.status === 'PICKING' && sourceActions.includes('PICK_TRANSFER') && transfer.lines.filter(line => !line.picked).map(line => <PickAction key={line.id} transfer={transfer} line={line} busy={busy} onCommand={onCommand} />)}
    {transfer.status === 'PICKED' && sourceActions.includes('DISPATCH_TRANSFER') && <DispatchAction transfer={transfer} busy={busy} onCommand={onCommand} />}
    {activeReturnQuery.isPending && <p role="status" className="rounded-xl border border-border p-4 text-sm">Checking current unsettled physical return requests before enabling stock actions…</p>}
    {activeReturnQuery.isError && <p role="alert" className="rounded-xl border border-destructive/30 p-4 text-sm text-destructive">Current physical return state could not be verified, so stock and return actions are unavailable. {errorMessage(activeReturnQuery.error)} <Button variant="outline" onClick={() => { void activeReturnQuery.refetch() }}>Retry return state</Button></p>}
    {pendingReturns.length > 0 && <p role="note" className="rounded-xl border border-border p-4 text-sm">A physical receipt or confirmed loss on a line with an active return request will supersede that request's remaining quantity. You can then create a new request for the current outstanding quantity.</p>}
    {activeReturnQuery.isSuccess && ['IN_TRANSIT', 'PARTIALLY_RECEIVED', 'RECONCILIATION_REQUIRED'].includes(transfer.status)
      && (destinationActions.includes('RECEIVE_STOCK') && canReadDestinationStock || canReceivePhysicalSettlement)
      && transfer.lines.filter(line => outstanding(line) > 0).map(line => <ReceiveAction key={line.id} transfer={transfer} line={line} position={destinationPositions?.get(line.id)} busy={busy} onCommand={onCommand} />)}
    {activeReturnQuery.isSuccess && ['IN_TRANSIT', 'PARTIALLY_RECEIVED', 'RECONCILIATION_REQUIRED'].includes(transfer.status)
      && destinationActions.includes('RECEIVE_STOCK') && !canReadDestinationStock && !canReceivePhysicalSettlement
      && <StateMessage state="forbidden" title="Destination inventory version unavailable" description="Receiving requires current inventory read access at the destination so stale stock cannot be overwritten." />}
    {receivingPositions.isError && canReadDestinationStock && !canReceivePhysicalSettlement && <p role="alert" className="text-sm text-destructive">Destination stock version could not be read. Refresh inventory access before receiving: {errorMessage(receivingPositions.error)} <Button variant="outline" onClick={() => { void receivingPositions.refetch() }}>Retry stock read</Button></p>}
    {historicalPositions.isError && (canReceivePhysicalSettlement || canConfirmPhysicalReturn) && <p role="alert" className="text-sm text-destructive">Transfer settlement position versions could not be read. Retry the transfer record before recording a physical receipt or return. {errorMessage(historicalPositions.error)} <Button variant="outline" onClick={() => { void historicalPositions.refetch() }}>Retry settlement version read</Button></p>}
    {activeReturnQuery.isSuccess && ['IN_TRANSIT', 'PARTIALLY_RECEIVED', 'RECONCILIATION_REQUIRED'].includes(transfer.status) && (destinationActions.includes('MANAGE_TRANSFER_DISCREPANCIES') || canConfirmHistoricalLoss) && transfer.lines.some(line => outstanding(line) > 0) && <DamageAction transfer={transfer} busy={busy} onCommand={onCommand} />}
    {activeReturnQuery.isSuccess && ['IN_TRANSIT', 'PARTIALLY_RECEIVED', 'RECONCILIATION_REQUIRED'].includes(transfer.status) && (destinationActions.includes('REQUEST_TRANSFER_RETURN') || canRequestHistoricalReturn) && transfer.lines.some(line => outstanding(line) > 0 && !pendingReturns.some(item => item.lineId === line.id)) && <RequestReturnAction key={`${transfer.id}:${transfer.version}`} transfer={transfer} pendingLineIds={new Set(pendingReturns.map(item => item.lineId))} busy={busy} onCommand={onCommand} />}
    {activeReturnQuery.isSuccess && pendingReturns.length > 0 && <section aria-label="Return requests" className="space-y-4 rounded-2xl border border-border p-5"><h4 className="text-lg font-semibold">Physical return requests</h4>{pendingReturns.map(item => {
      const line = transfer.lines.find(candidate => candidate.id === item.lineId)!
      return <p key={`${item.requestId}:${item.lineId}`} className="text-sm">Line {line.lineNumber}: {item.unsettledQuantity} {line.unit} remain unsettled under return request {item.requestReference}. This is a logical request; the goods remain in transit custody until the source confirms physical receipt.</p>
    })}</section>}
    {pendingReturns.length > 0 && sourceCanConfirmReturn && activeReturnQuery.isSuccess && <ConfirmReturnAction key={`${transfer.id}:${transfer.version}:${pendingReturns.map(item => item.requestId).join('|')}`} transfer={transfer} returns={pendingReturns} positionQueries={returnPositionQueries} busy={busy} onCommand={onCommand} />}
    {activeReturnQuery.isSuccess && transfer.status === 'RECONCILIATION_REQUIRED' && transfer.outstandingQuantity === 0 && (sourceActions.includes('MANAGE_TRANSFER_DISCREPANCIES') || destinationActions.includes('MANAGE_TRANSFER_DISCREPANCIES') || canCloseHistoricalDiscrepancy) && <DecisionAction title="Confirm final reconciliation" primaryLabel="Confirm reconciliation" onSubmit={(reason, evidence) => { command('CLOSE', reason, evidence) }} busy={busy} />}
    {activeReturnQuery.isSuccess && ['IN_TRANSIT', 'PARTIALLY_RECEIVED', 'RECONCILIATION_REQUIRED'].includes(transfer.status) && (destinationActions.includes('MANAGE_TRANSFER_DISCREPANCIES') || canOpenHistoricalDiscrepancy) && <OpenDiscrepancyAction transfer={transfer} busy={busy} onCommand={onCommand} />}
    {transfer.canCancel && ['REQUESTED', 'APPROVED', 'PICKING', 'PICKED'].includes(transfer.status) && <CancelAction transfer={transfer} onCommand={onCommand} busy={busy} />}

    <section aria-label="Outstanding in-transit lines" className="space-y-3 rounded-2xl border border-border p-5">
      <h4 className="text-lg font-semibold">Outstanding in-transit lines</h4>
      {transitQuery.isPending && <p role="status">Loading current custody lines…</p>}
      {transitQuery.isError && <p role="alert" className="text-sm text-destructive">Transit records are unavailable. <Button variant="outline" onClick={() => { void transitQuery.refetch() }}>Retry transit read</Button></p>}
      {!transitQuery.isPending && !transitQuery.isError && transitItems.length === 0 && <p className="text-sm text-muted-foreground">No goods remain in transit.</p>}
      {transitItems.length > 0 && <ul className="space-y-2">{transitItems.map(line => <li key={line.lineId} className="flex flex-wrap justify-between gap-2 text-sm"><span>Line {line.lineId.slice(0, 8)} · {line.outstanding} units · dispatched {new Date(line.dispatchedAt).toLocaleString()}</span><span>{formatTransferAge(line.ageSeconds)} in transit</span></li>)}</ul>}
      {transitQuery.hasNextPage && <Button variant="outline" disabled={transitQuery.isFetchingNextPage} onClick={() => { void transitQuery.fetchNextPage() }}>{transitQuery.isFetchingNextPage ? 'Loading in-transit lines…' : 'Load more in-transit lines'}</Button>}
    </section>
    <section aria-labelledby="custody-heading" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3"><div><h4 id="custody-heading" className="text-lg font-semibold">Custody and physical evidence</h4><p className="mt-1 text-sm text-muted-foreground">Every entry links the responsible actor, quantity, remaining custody and any stock ledger movement.</p></div><Button variant="outline" disabled={custody.isFetching} onClick={() => { void custody.refetch() }}>Refresh timeline</Button></div>
      {custody.isPending ? <p role="status">Loading immutable custody entries…</p>
        : custody.isError ? <p role="alert" className="text-sm text-destructive">Custody history unavailable: {errorMessage(custody.error)} <Button variant="outline" onClick={() => { void custody.refetch() }}>Retry timeline read</Button></p>
          : custodyItems.length === 0 ? <p className="rounded-xl border border-border p-4 text-sm">No physical custody movement is recorded. Approval and picking do not mean goods have been dispatched.</p>
            : <ol className="space-y-3">{custodyItems.map((movement: TransferCustodyMovement) => <li key={movement.id} className="rounded-xl border border-border p-4"><div className="flex flex-wrap justify-between gap-2"><p className="font-semibold">{movement.type} · {movement.quantity} units</p><div className="space-y-1 text-sm text-muted-foreground">{movement.type === 'RECEIPT' && <p><span className="font-medium">Physical receipt time:</span> {movement.physicalEventAt ? <time dateTime={movement.physicalEventAt} title={movement.physicalEventAt}>{new Date(movement.physicalEventAt).toLocaleString()}</time> : 'Not available'}</p>}<p><span className="font-medium">Recorded at:</span> <time dateTime={movement.recordedAt} title={movement.recordedAt}>{new Date(movement.recordedAt).toLocaleString()}</time></p></div></div><p className="mt-2 text-sm">Actor {movement.actorId} · outstanding {movement.outstandingBefore} → {movement.outstandingAfter} · line {movement.lineId.slice(0, 8)}</p><p className="mt-2 break-all text-sm">Reason: {movement.reason} · evidence: {movement.evidenceReference}</p>{movement.stockMovementId && <p className="mt-2 break-all text-xs text-muted-foreground">Stock movement {movement.stockMovementId} · position {movement.stockPositionId} · store {movement.stockStoreId}</p>}</li>)}</ol>}
      {custody.hasNextPage && <Button variant="outline" disabled={custody.isFetchingNextPage} onClick={() => { void custody.fetchNextPage() }}>{custody.isFetchingNextPage ? 'Loading custody entries…' : 'Load more custody entries'}</Button>}
    </section>
    <section aria-labelledby="discrepancy-heading" className="space-y-3">
      <h4 id="discrepancy-heading" className="text-lg font-semibold">Discrepancy and reconciliation evidence</h4>
      {discrepancyQuery.isPending ? <p role="status">Checking reconciliation records…</p>
        : discrepancyQuery.isError ? <p role="alert" className="text-sm text-destructive">Reconciliation history unavailable: {errorMessage(discrepancyQuery.error)} <Button variant="outline" onClick={() => { void discrepancyQuery.refetch() }}>Retry reconciliation read</Button></p>
          : detailDiscrepancies.length === 0 ? <p className="text-sm text-muted-foreground">No reconciliation evidence has been recorded.</p>
            : <ul className="space-y-3">{detailDiscrepancies.map(item => <li key={item.id} className="rounded-xl border border-border p-4"><div className="flex flex-wrap justify-between gap-2"><p className="font-semibold">{item.type} · {new Date(item.recordedAt).toLocaleString()}</p><span className="text-sm">{discrepancyStatus(item)}</span></div><p className="mt-2 text-sm">Actor {item.actorId} · expected: {item.expectedState} · observed: {item.observedState}</p><p className="mt-1 text-sm">{item.reason} · source reference: {item.evidenceReference}</p></li>)}</ul>}
      {discrepancyQuery.hasNextPage && <Button variant="outline" disabled={discrepancyQuery.isFetchingNextPage} onClick={() => { void discrepancyQuery.fetchNextPage() }}>{discrepancyQuery.isFetchingNextPage ? 'Loading reconciliation records…' : 'Load more reconciliation records'}</Button>}
    </section>
  </section>
}

function RequestReturnAction({ transfer, pendingLineIds, busy, onCommand }: {
  transfer: Transfer, pendingLineIds: Set<string>, busy: boolean, onCommand: (intent: TransferCommandIntent) => Promise<void>,
}) {
  const eligibleLines = transfer.lines.filter(line => outstanding(line) > 0 && !pendingLineIds.has(line.id))
  const [selectedLineIds, setSelectedLineIds] = useState<string[]>(() => eligibleLines.map(line => line.id))
  const [requestReference, setRequestReference] = useState('')
  const [reason, setReason] = useState('')
  const [evidenceReference, setEvidenceReference] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const selectedLines = eligibleLines.filter(line => selectedLineIds.includes(line.id))
  return <form aria-label="Request physical return" className="grid min-w-0 gap-4 rounded-2xl border border-border p-5 sm:grid-cols-2" onSubmit={event => {
    event.preventDefault()
    if (!selectedLines.length || !confirmed) return
    const body = { requestId: crypto.randomUUID(), expectedVersion: transfer.version, requestReference: requestReference.trim(),
      lines: selectedLines.map(line => ({ lineId: line.id })), reason: reason.trim(), evidenceReference: evidenceReference.trim() }
    void onCommand({ operation: 'REQUEST_RETURN', transferId: transfer.id, body })
  }}>
    <div className="min-w-0 sm:col-span-2"><h4 className="text-lg font-semibold">Request physical return</h4><p className="mt-1 text-sm text-muted-foreground">This records which outstanding goods should return and why. It does not move stock or reduce custody; the source store must confirm physical receipt.</p></div>
    <fieldset className="grid gap-3 sm:col-span-2"><legend className="font-medium">Select outstanding lines</legend>{eligibleLines.map(line => <label key={line.id} className="flex min-w-0 items-start gap-3 rounded-lg border border-border p-3 text-sm"><input type="checkbox" checked={selectedLineIds.includes(line.id)} onChange={event => { setSelectedLineIds(current => event.target.checked ? [...new Set([...current, line.id])] : current.filter(id => id !== line.id)) }} className="mt-1" /><span className="min-w-0 break-words">Line {line.lineNumber}: {outstanding(line)} {line.unit} outstanding · {line.sku} · lot {line.lotCode}</span></label>)}</fieldset>
    <Field label="Return request reference">{id => <input id={id} required maxLength={200} value={requestReference} onChange={event => { setRequestReference(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Reason for return request">{id => <textarea id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Evidence reference">{id => <input id={id} required maxLength={256} value={evidenceReference} onChange={event => { setEvidenceReference(event.target.value) }} className={inputClass} />}</Field>
    <label className="flex min-w-0 items-start gap-3 text-sm sm:col-span-2"><input type="checkbox" required checked={confirmed} onChange={event => { setConfirmed(event.target.checked) }} className="mt-1" /><span>I understand this request records intent only and the returned quantities stay in transit custody until physically received at the source.</span></label>
    <Button type="submit" disabled={busy || !selectedLines.length || !requestReference.trim() || !reason.trim() || !evidenceReference.trim() || !confirmed} className="sm:col-span-2">{busy ? 'Saving return request…' : 'Record return request'}</Button>
  </form>
}

type ReturnPositionQuery = { data?: Pick<StockPosition, 'version'>, isPending: boolean, isError: boolean, refetch: () => Promise<unknown> }

function ConfirmReturnAction({ transfer, returns, positionQueries, busy, onCommand }: {
  transfer: Transfer, returns: PendingReturnLine[], positionQueries: ReturnPositionQuery[],
  busy: boolean, onCommand: (intent: TransferCommandIntent) => Promise<void>,
}) {
  const positionByLineId = new Map(returns.map((item, index) => [item.lineId, positionQueries[index]!]))
  const groups = [...new Set(returns.map(item => item.requestId))].map(requestId => ({
    requestId, reference: returns.find(item => item.requestId === requestId)!.requestReference,
    lines: returns.filter(item => item.requestId === requestId),
  }))
  return <section aria-label="Confirm physical return" className="space-y-4">
    <h4 className="text-lg font-semibold">Confirm physical return at source</h4>
    {groups.map(group => <ConfirmReturnGroup key={group.requestId} transfer={transfer} requestId={group.requestId} requestReference={group.reference}
      returns={group.lines} positionByLineId={positionByLineId} busy={busy} onCommand={onCommand} />)}
  </section>
}

function ConfirmReturnGroup({ transfer, requestId, requestReference, returns, positionByLineId, busy, onCommand }: {
  transfer: Transfer, requestId: string, requestReference: string, returns: PendingReturnLine[], positionByLineId: Map<string, ReturnPositionQuery>,
  busy: boolean, onCommand: (intent: TransferCommandIntent) => Promise<void>,
}) {
  const [documentReference, setDocumentReference] = useState('')
  const [reason, setReason] = useState('')
  const [evidenceReference, setEvidenceReference] = useState('')
  const [lineReferences, setLineReferences] = useState<Record<string, string>>({})
  const [quantities, setQuantities] = useState<Record<string, string>>(() => Object.fromEntries(returns.map(item => [item.lineId, String(item.unsettledQuantity)])))
  const [selectedLineIds, setSelectedLineIds] = useState<string[]>(() => returns.map(item => item.lineId))
  const [confirmed, setConfirmed] = useState(false)
  const selectedReturns = returns.filter(item => selectedLineIds.includes(item.lineId))
  const validQuantity = (item: PendingReturnLine) => {
    const quantity = Number(quantities[item.lineId])
    return Number.isSafeInteger(quantity) && quantity > 0 && quantity <= item.unsettledQuantity
  }
  const positionsReady = selectedReturns.every(item => Boolean(positionByLineId.get(item.lineId)?.data && !positionByLineId.get(item.lineId)?.isError))
  const positionsFailed = selectedReturns.some(item => positionByLineId.get(item.lineId)?.isError)
  const canSubmit = Boolean(selectedReturns.length && positionsReady && confirmed && requestReference.trim()
    && documentReference.trim() && reason.trim() && evidenceReference.trim()
    && selectedReturns.every(item => Boolean(lineReferences[item.lineId]?.trim()) && validQuantity(item)) && !busy)
  return <form aria-label={`Confirm physical return request ${requestReference}`} className="grid min-w-0 gap-4 rounded-2xl border border-border p-5 sm:grid-cols-2" onSubmit={event => {
    event.preventDefault()
    if (!canSubmit) return
    const body = { requestId: crypto.randomUUID(), expectedVersion: transfer.version, requestReference: requestReference.trim(), documentReference: documentReference.trim(),
      lines: selectedReturns.map(item => {
        const line = transfer.lines.find(candidate => candidate.id === item.lineId)!
        const position = positionByLineId.get(item.lineId)!.data!
        return { lineId: line.id, quantity: Number(quantities[line.id]), expectedSourceVersion: position.version,
          documentLineReference: lineReferences[line.id]!.trim(), observedSku: line.sku, observedLotCode: line.lotCode }
      }), reason: reason.trim(), evidenceReference: evidenceReference.trim() }
    void onCommand({ operation: 'CONFIRM_RETURN', transferId: transfer.id, body })
  }}>
    <div className="min-w-0 sm:col-span-2"><p className="mt-1 break-words text-sm text-muted-foreground">Request reference: {requestReference} · request {requestId}. Choose only the lines physically received now; later receipts use a new physical reference.</p></div>
    <Field label="Physical receipt reference">{id => <input id={id} required maxLength={200} value={documentReference} onChange={event => { setDocumentReference(event.target.value) }} className={inputClass} />}</Field>
    {returns.map(item => {
      const line = transfer.lines.find(candidate => candidate.id === item.lineId)!
      const query = positionByLineId.get(item.lineId)
      return <fieldset key={line.id} className="grid min-w-0 gap-3 rounded-lg border border-border p-3 sm:grid-cols-2"><legend className="px-2 font-medium">Line {line.lineNumber} · {item.requestedQuantity} {line.unit}</legend>
        <label className="flex items-start gap-3 text-sm sm:col-span-2"><input type="checkbox" checked={selectedLineIds.includes(item.lineId)} onChange={event => { setSelectedLineIds(current => event.target.checked ? [...new Set([...current, item.lineId])] : current.filter(id => id !== item.lineId)) }} className="mt-1" />Include line {line.lineNumber} in this physical receipt</label>
        {selectedLineIds.includes(item.lineId) && <Field label={`Quantity physically returned for line ${line.lineNumber}`}>{id => <input id={id} required type="number" min={1} max={item.unsettledQuantity} step={1} value={quantities[item.lineId] ?? ''} onChange={event => { setQuantities(current => ({ ...current, [item.lineId]: event.target.value })) }} className={inputClass} />}</Field>}
        {selectedLineIds.includes(item.lineId) && !validQuantity(item) && <p role="alert" className="text-sm text-destructive sm:col-span-2">Enter a whole quantity from 1 to {item.unsettledQuantity} {line.unit}.</p>}
        <p className="break-words text-sm">Expected SKU {line.sku} · lot {line.lotCode}. Current source position version: {query?.data?.version ?? (query?.isPending ? 'checking…' : 'unavailable')}.</p>
        {selectedLineIds.includes(item.lineId) && <Field label={`Return document line for line ${line.lineNumber}`}>{id => <input id={id} required maxLength={64} value={lineReferences[line.id] ?? ''} onChange={event => { setLineReferences(current => ({ ...current, [line.id]: event.target.value })) }} className={inputClass} />}</Field>}
        {selectedLineIds.includes(item.lineId) && query?.isError && <p role="alert" className="text-sm text-destructive sm:col-span-2">Source position version failed to load. <Button type="button" variant="outline" onClick={() => { void query.refetch() }}>Retry source version</Button></p>}
      </fieldset>
    })}
    {positionsFailed && <p role="alert" className="text-sm text-destructive sm:col-span-2">Current source inventory versions could not be read. Refresh the source inventory access and retry.</p>}
    <Field label="Reason for physical return">{id => <textarea id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Evidence reference">{id => <input id={id} required maxLength={256} value={evidenceReference} onChange={event => { setEvidenceReference(event.target.value) }} className={inputClass} />}</Field>
    <label className="flex min-w-0 items-start gap-3 text-sm sm:col-span-2"><input type="checkbox" required checked={confirmed} onChange={event => { setConfirmed(event.target.checked) }} className="mt-1" /><span>I physically received and verified every selected quantity, SKU and lot at the source store.</span></label>
    <Button type="submit" disabled={!canSubmit} className="sm:col-span-2">{busy ? 'Saving physical return…' : 'Confirm physical return received'}</Button>
  </form>
}

function Quantity({ label, value, unit }: { label: string, value: number, unit: string }) {
  return <div><dt className="text-xs text-muted-foreground">{label}</dt><dd className="mt-1 font-medium">{value} {unit}</dd></div>
}

function DecisionAction({ title, primaryLabel, onSubmit, busy }: { title: string, primaryLabel: string, onSubmit: (reason: string, evidence: string) => void, busy: boolean }) {
  const [reason, setReason] = useState('')
  const [evidence, setEvidence] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  return <form aria-label={title} className="grid gap-4 rounded-2xl border border-border p-5 sm:grid-cols-2" onSubmit={event => { event.preventDefault(); onSubmit(reason, evidence) }}>
    <h4 className="text-lg font-semibold sm:col-span-2">{title}</h4>
    <Field label="Reason">{id => <textarea id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Evidence reference">{id => <input id={id} required maxLength={256} value={evidence} onChange={event => { setEvidence(event.target.value) }} className={inputClass} />}</Field>
    <label className="flex items-start gap-3 text-sm sm:col-span-2"><input type="checkbox" required checked={confirmed} onChange={event => { setConfirmed(event.target.checked) }} className="mt-1" />I reviewed the saved transfer version and confirm this action is physically and operationally accurate.</label>
    <Button type="submit" disabled={busy || !reason.trim() || !evidence.trim() || !confirmed} className="sm:col-span-2">{busy ? 'Saving command…' : primaryLabel}</Button>
  </form>
}

function CancelAction({ transfer, onCommand, busy }: { transfer: Transfer, onCommand: (intent: TransferCommandIntent) => Promise<void>, busy: boolean }) {
  const pickedLines = transfer.lines.filter(line => line.picked)
  const [reason, setReason] = useState('')
  const [evidence, setEvidence] = useState('')
  const [putBackReference, setPutBackReference] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  return <form aria-label="Cancel transfer before dispatch" className="grid gap-4 rounded-2xl border border-border p-5 sm:grid-cols-2" onSubmit={event => {
    event.preventDefault()
    if (!confirmed || pickedLines.length > 0 && !putBackReference.trim()) return
    const body = { requestId: crypto.randomUUID(), expectedVersion: transfer.version, reason: reason.trim(), evidenceReference: evidence.trim(),
      ...(pickedLines.length > 0 ? { putBackConfirmed: true, putBackDocumentReference: putBackReference.trim() } : {}) }
    void onCommand({ operation: 'CANCEL', transferId: transfer.id, body })
  }}>
    <div className="sm:col-span-2"><h4 className="text-lg font-semibold">Cancel transfer before dispatch</h4>{pickedLines.length > 0 && <div className="mt-2 text-sm text-muted-foreground"><p>Physically return each picked line to its source shelf. The transfer reservation will be released only after you confirm this put-back.</p><ul className="mt-2 list-inside list-disc">{pickedLines.map(line => <li key={line.id}>Line {line.lineNumber}: {line.requestedQuantity} {line.unit} · {line.sku} · lot {line.lotCode}</li>)}</ul></div>}</div>
    <Field label="Reason">{id => <textarea id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Evidence reference">{id => <input id={id} required maxLength={256} value={evidence} onChange={event => { setEvidence(event.target.value) }} className={inputClass} />}</Field>
    {pickedLines.length > 0 && <Field label="Physical put-back document reference">{id => <input id={id} required maxLength={200} value={putBackReference} onChange={event => { setPutBackReference(event.target.value) }} className={inputClass} />}</Field>}
    <label className="flex items-start gap-3 text-sm sm:col-span-2"><input type="checkbox" required checked={confirmed} onChange={event => { setConfirmed(event.target.checked) }} className="mt-1" />{pickedLines.length > 0 ? 'I physically returned every listed picked line to its source shelf and verified its SKU, lot and full quantity.' : 'I reviewed the saved transfer version and confirm cancellation before physical dispatch.'}</label>
    <Button type="submit" disabled={busy || !reason.trim() || !evidence.trim() || pickedLines.length > 0 && !putBackReference.trim() || !confirmed} className="sm:col-span-2">{busy ? 'Saving cancellation…' : 'Cancel transfer'}</Button>
  </form>
}

function PickAction({ transfer, line, busy, onCommand }: { transfer: Transfer, line: Transfer['lines'][number], busy: boolean, onCommand: (intent: TransferCommandIntent) => Promise<void> }) {
  const [documentReference, setDocumentReference] = useState('')
  const [reason, setReason] = useState('')
  const [evidenceReference, setEvidenceReference] = useState('')
  const [checked, setChecked] = useState(false)
  return <form aria-label={`Pick line ${line.lineNumber}`} className="grid gap-4 rounded-2xl border border-border p-5 sm:grid-cols-2" onSubmit={event => {
    event.preventDefault()
    if (!checked) return
    const body = { requestId: crypto.randomUUID(), expectedVersion: transfer.version, lineId: line.id, documentReference: documentReference.trim(), quantity: line.requestedQuantity, observedSku: line.sku, observedLotCode: line.lotCode, reason: reason.trim(), evidenceReference: evidenceReference.trim() }
    void onCommand({ operation: 'PICK', transferId: transfer.id, body })
  }}>
    <div className="sm:col-span-2"><h4 className="text-lg font-semibold">Physically pick line {line.lineNumber}</h4><p className="mt-1 text-sm text-muted-foreground">Confirm the complete requested line: {line.requestedQuantity} {line.unit} · {line.sku} · lot {line.lotCode}. Partial picks are not accepted by this workflow.</p></div>
    <Field label="Pick document reference">{id => <input id={id} required maxLength={200} value={documentReference} onChange={event => { setDocumentReference(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Reason for pick">{id => <input id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Evidence reference">{id => <input id={id} required maxLength={256} value={evidenceReference} onChange={event => { setEvidenceReference(event.target.value) }} className={inputClass} />}</Field>
    <label className="flex items-start gap-3 text-sm"><input type="checkbox" required checked={checked} onChange={event => { setChecked(event.target.checked) }} className="mt-1" />I scanned and physically verified this exact SKU, lot and full quantity.</label>
    <Button type="submit" disabled={busy || !reason.trim() || !evidenceReference.trim() || !documentReference.trim() || !checked} className="sm:col-span-2">{busy ? 'Saving pick evidence…' : 'Confirm full-line pick'}</Button>
    {transfer.status === 'PICKING' && <p className="sm:col-span-2 text-sm text-muted-foreground">PICKING means source verification is in progress. It does not mean stock was dispatched.</p>}
  </form>
}

function DispatchAction({ transfer, busy, onCommand }: { transfer: Transfer, busy: boolean, onCommand: (intent: TransferCommandIntent) => Promise<void> }) {
  const [documentReference, setDocumentReference] = useState('')
  const [reason, setReason] = useState('')
  const [evidenceReference, setEvidenceReference] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  return <form aria-label="Confirm physical dispatch" className="grid gap-4 rounded-2xl border border-border p-5 sm:grid-cols-2" onSubmit={event => {
    event.preventDefault()
    if (!confirmed) return
    const body = { requestId: crypto.randomUUID(), expectedVersion: transfer.version, documentReference: documentReference.trim(), lines: transfer.lines.map(line => ({ lineId: line.id, quantity: line.requestedQuantity, observedSku: line.sku, observedLotCode: line.lotCode })), reason: reason.trim(), evidenceReference: evidenceReference.trim() }
    void onCommand({ operation: 'DISPATCH', transferId: transfer.id, body })
  }}>
    <div className="sm:col-span-2"><h4 className="text-lg font-semibold">Confirm physical dispatch</h4><p className="mt-1 text-sm text-muted-foreground">Picked goods remain in the source store until a carrier physically takes custody. This action posts the source ledger and opens transit custody.</p></div>
    <Field label="Dispatch document reference">{id => <input id={id} required maxLength={200} value={documentReference} onChange={event => { setDocumentReference(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Reason for dispatch">{id => <input id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Evidence reference">{id => <input id={id} required maxLength={256} value={evidenceReference} onChange={event => { setEvidenceReference(event.target.value) }} className={inputClass} />}</Field>
    <label className="flex items-start gap-3 text-sm"><input type="checkbox" required checked={confirmed} onChange={event => { setConfirmed(event.target.checked) }} className="mt-1" />I confirm the carrier has physically received every listed line.</label>
    <Button type="submit" disabled={busy || !reason.trim() || !evidenceReference.trim() || !documentReference.trim() || !confirmed} className="sm:col-span-2">{busy ? 'Saving dispatch…' : 'Confirm goods left the source store'}</Button>
  </form>
}

function ReceiveAction({ transfer, line, position, busy, onCommand }: { transfer: Transfer, line: Transfer['lines'][number], position?: Pick<StockPosition, 'version'> | null, busy: boolean, onCommand: (intent: TransferCommandIntent) => Promise<void> }) {
  const [quantity, setQuantity] = useState(String(outstanding(line)))
  const [documentReference, setDocumentReference] = useState('')
  const [lineReference, setLineReference] = useState('')
  const [reason, setReason] = useState('')
  const [evidenceReference, setEvidenceReference] = useState('')
  const [physicallyChecked, setPhysicallyChecked] = useState(false)
  const parsedQuantity = Number(quantity)
  const canSubmit = Number.isSafeInteger(parsedQuantity) && parsedQuantity > 0 && parsedQuantity <= outstanding(line)
    && Boolean(documentReference.trim() && lineReference.trim() && reason.trim() && evidenceReference.trim() && physicallyChecked && position !== undefined && !busy)
  return <form aria-label={`Receive line ${line.lineNumber}`} className="grid gap-4 rounded-2xl border border-border p-5 sm:grid-cols-2" onSubmit={event => {
    event.preventDefault()
    if (!canSubmit) return
    const body = { requestId: crypto.randomUUID(), expectedVersion: transfer.version, documentReference: documentReference.trim(),
      lines: [{ lineId: line.id, quantity: parsedQuantity, expectedDestinationVersion: position?.version ?? 0, documentLineReference: lineReference.trim(), observedSku: line.sku, observedLotCode: line.lotCode }],
      reason: reason.trim(), evidenceReference: evidenceReference.trim() }
    void onCommand({ operation: 'RECEIVE', transferId: transfer.id, body })
  }}>
    <div className="sm:col-span-2"><h4 className="text-lg font-semibold">Receive line {line.lineNumber}</h4><p className="mt-1 text-sm text-muted-foreground">{outstanding(line)} units remain in transit. Destination position version: {position?.version ?? (position === undefined ? 'checking current stock…' : 0)}. Current lot safety controls whether received stock is available or quarantined.</p></div>
    <Field label={`Physical quantity received (${line.unit})`}>{id => <input id={id} type="number" min="1" max={outstanding(line)} step="1" required value={quantity} onChange={event => { setQuantity(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Receiving document reference">{id => <input id={id} required maxLength={200} value={documentReference} onChange={event => { setDocumentReference(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Document line reference">{id => <input id={id} required maxLength={64} value={lineReference} onChange={event => { setLineReference(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Reason for receipt">{id => <input id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Evidence reference">{id => <input id={id} required maxLength={256} value={evidenceReference} onChange={event => { setEvidenceReference(event.target.value) }} className={inputClass} />}</Field>
    <label className="flex items-start gap-3 text-sm"><input type="checkbox" required checked={physicallyChecked} onChange={event => { setPhysicallyChecked(event.target.checked) }} className="mt-1" />I counted these units and verified SKU {line.sku} and lot {line.lotCode} against the delivery.</label>
    <Button type="submit" disabled={!canSubmit} className="sm:col-span-2">{busy ? 'Saving receipt…' : `Record ${parsedQuantity > 0 ? parsedQuantity : ''} physical units received`}</Button>
  </form>
}

function DamageAction({ transfer, busy, onCommand }: { transfer: Transfer, busy: boolean, onCommand: (intent: TransferCommandIntent) => Promise<void> }) {
  const linesForResolution = transfer.lines.filter(item => outstanding(item) > 0)
  const [lineId, setLineId] = useState(linesForResolution[0]?.id ?? '')
  const line = transfer.lines.find(item => item.id === lineId)
  const [quantity, setQuantity] = useState(String(line?.quantities.outstanding ?? 0))
  const [reasonCode, setReasonCode] = useState<'DAMAGED_IN_TRANSIT' | 'DESTROYED_IN_TRANSIT' | 'LOST_WITH_EVIDENCE'>('DAMAGED_IN_TRANSIT')
  const [documentReference, setDocumentReference] = useState('')
  const [lineReference, setLineReference] = useState('')
  const [reason, setReason] = useState('')
  const [evidenceReference, setEvidenceReference] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const count = Number(quantity)
  return <form aria-label="Resolve loss or damage" className="grid gap-4 rounded-2xl border border-amber-500/40 p-5 sm:grid-cols-2" onSubmit={event => {
    event.preventDefault()
    if (!line || !confirmed || !Number.isSafeInteger(count) || count < 1 || count > outstanding(line)) return
    const body = { requestId: crypto.randomUUID(), expectedVersion: transfer.version, documentReference: documentReference.trim(), reasonCode,
      lines: [{ lineId: line.id, quantity: count, documentLineReference: lineReference.trim() }], reason: reason.trim(), evidenceReference: evidenceReference.trim() }
    void onCommand({ operation: 'CONFIRM_LOSS', transferId: transfer.id, body })
  }}>
    <div className="sm:col-span-2"><h4 className="text-lg font-semibold">Resolve damaged or missing units</h4><p className="mt-1 text-sm text-muted-foreground">Record only a documented physical loss. This reduces outstanding custody; it does not create destination stock. A manager must close the resulting discrepancy after reconciliation.</p></div>
    <Field label="Transfer line">{id => <select id={id} value={lineId} onChange={event => { setLineId(event.target.value); setQuantity(String(outstanding(transfer.lines.find(item => item.id === event.target.value) ?? linesForResolution[0]!))) }} className={inputClass}>{linesForResolution.map(item => <option key={item.id} value={item.id}>Line {item.lineNumber} · {item.sku} · lot {item.lotCode} · {outstanding(item)} outstanding</option>)}</select>}</Field>
    <Field label="Loss or damage classification">{id => <select id={id} value={reasonCode} onChange={event => { setReasonCode(event.target.value as typeof reasonCode) }} className={inputClass}><option value="DAMAGED_IN_TRANSIT">Damaged in transit</option><option value="DESTROYED_IN_TRANSIT">Destroyed in transit</option><option value="LOST_WITH_EVIDENCE">Lost with evidence</option></select>}</Field>
    <Field label="Quantity affected">{id => <input id={id} type="number" required min="1" max={line?.quantities.outstanding ?? 0} step="1" value={quantity} onChange={event => { setQuantity(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Damage document reference">{id => <input id={id} required maxLength={200} value={documentReference} onChange={event => { setDocumentReference(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Document line reference">{id => <input id={id} required maxLength={64} value={lineReference} onChange={event => { setLineReference(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Reason and evidence description">{id => <input id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Evidence reference">{id => <input id={id} required maxLength={256} value={evidenceReference} onChange={event => { setEvidenceReference(event.target.value) }} className={inputClass} />}</Field>
    <label className="flex items-start gap-3 text-sm"><input type="checkbox" required checked={confirmed} onChange={event => { setConfirmed(event.target.checked) }} className="mt-1" />I reviewed the physical evidence and confirm these units were not received or returned.</label>
    <Button type="submit" disabled={busy || !line || !count || count > (line?.quantities.outstanding ?? 0) || !reason.trim() || !evidenceReference.trim() || !documentReference.trim() || !lineReference.trim() || !confirmed} className="sm:col-span-2">{busy ? 'Saving discrepancy evidence…' : 'Record documented loss or damage'}</Button>
  </form>
}

function OpenDiscrepancyAction({ transfer, busy, onCommand }: { transfer: Transfer, busy: boolean, onCommand: (intent: TransferCommandIntent) => Promise<void> }) {
  const [lineId, setLineId] = useState('')
  const [expectedState, setExpectedState] = useState('')
  const [observedState, setObservedState] = useState('')
  const [reason, setReason] = useState('')
  const [evidenceReference, setEvidenceReference] = useState('')
  return <form aria-label="Open a custody discrepancy" className="grid gap-4 rounded-2xl border border-border p-5 sm:grid-cols-2" onSubmit={event => {
    event.preventDefault()
    const body = { requestId: crypto.randomUUID(), expectedVersion: transfer.version, ...(lineId ? { lineId } : {}), expectedState: expectedState.trim(), observedState: observedState.trim(), reason: reason.trim(), evidenceReference: evidenceReference.trim() }
    void onCommand({ operation: 'OPEN_DISCREPANCY', transferId: transfer.id, body })
  }}>
    <h4 className="text-lg font-semibold sm:col-span-2">Document an unresolved custody discrepancy</h4>
    <Field label="Affected line (optional)">{id => <select id={id} value={lineId} onChange={event => { setLineId(event.target.value) }} className={inputClass}><option value="">Whole transfer</option>{transfer.lines.map(line => <option key={line.id} value={line.id}>Line {line.lineNumber} · {line.sku} · {line.lotCode}</option>)}</select>}</Field>
    <Field label="Expected physical state">{id => <input id={id} required maxLength={500} value={expectedState} onChange={event => { setExpectedState(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Observed physical state">{id => <input id={id} required maxLength={500} value={observedState} onChange={event => { setObservedState(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Reason">{id => <input id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Evidence reference">{id => <input id={id} required maxLength={256} value={evidenceReference} onChange={event => { setEvidenceReference(event.target.value) }} className={inputClass} />}</Field>
    <Button type="submit" disabled={busy || !expectedState.trim() || !observedState.trim() || !reason.trim() || !evidenceReference.trim()} className="sm:col-span-2">Record discrepancy for review</Button>
  </form>
}
