import { useMemo, useState } from 'react'
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
  getTransferSettlementPositions, listTransferInTransit, listTransferScopedDiscrepancies, listTransfers, listTransfersInTransit, markTransferCommandIntentSubmitted, prepareTransferCommandIntent,
  resolveFailedTransferCommandIntent,
} from '@/api/transfers'
import type {
  Transfer, TransferCommandIntent, TransferCommandIntentInspection, TransferCustodyMovement, TransferDiscrepancyRecord,
  TransferInTransitLine, TransferQueueItem, TransferStatus,
} from '@/api/transfers'
import { OrganizationPage } from '../organizations/organization-page'
import { useOrganization } from '../organizations/organization-context'
import { useTenantKey } from '../organizations/tenant-requests'
import { formatTransferAge, transferStatusLabel, transferStatusOptions } from './transfer-format'
import { loadStoreCursorPages, TransferRequestScheduler } from './transfer-pagination'
import type { StoreCursorStates } from './transfer-pagination'

type DraftTransferLine = { id: string, sourcePositionId: string, destinationLocationId: string, quantity: string }
type PendingReturnLine = { lineId: string, requestId: string, requestReference: string, requestedQuantity: number, settledQuantity: number, unsettledQuantity: number }
const newDraftTransferLine = (): DraftTransferLine => ({ id: crypto.randomUUID(), sourcePositionId: '', destinationLocationId: '', quantity: '' })

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

function uncertainOutcome(error: unknown) {
  return !(error instanceof ApiError) || error.status >= 500
}

function outstanding(line: Transfer['lines'][number]) { return line.quantities.outstanding ?? 0 }

export function TransfersPage() {
  const { organizationId, transferId } = useParams({ strict: false })
  return <OrganizationPage organizationId={organizationId}>{organizationId && <TransferWorkspace key={organizationId} organizationId={organizationId} transferId={transferId} />}</OrganizationPage>
}

function TransferWorkspace({ organizationId, transferId }: { organizationId: string, transferId?: string }) {
  const client = useQueryClient()
  const navigate = useNavigate()
  const scope = useTenantKey()
  const transferRequestScheduler = useMemo(() => new TransferRequestScheduler(8), [])
  const organization = useOrganization()
  const [status, setStatus] = useState<TransferStatus | ''>('')
  const [storeFilter, setStoreFilter] = useState('')
  const [storeSearch, setStoreSearch] = useState('')
  const [pendingInspection, setPendingInspection] = useState<TransferCommandIntentInspection | null>(null)
  const [commandBusy, setCommandBusy] = useState(false)
  const [commandError, setCommandError] = useState('')
  const [commandNotice, setCommandNotice] = useState('')
  const [recoveryMissing, setRecoveryMissing] = useState(false)
  const pendingIntentKey = [...scope, 'transfer-command-intents', 'pending'] as const
  const pendingIntents = useQuery({
    queryKey: pendingIntentKey,
    queryFn: ({ signal }) => listPendingTransferCommandIntents(organizationId, signal),
    retry: false, refetchOnWindowFocus: true, staleTime: 0, gcTime: 0,
  })
  const pendingCommand = pendingIntents.data?.items[0]
  const commandBlocked = commandBusy || Boolean(pendingCommand) || pendingIntents.isPending || pendingIntents.isError
  const stores = useInfiniteQuery({
    queryKey: [...scope, 'stores', 'transfer-workspace', storeSearch], enabled: !transferId, initialPageParam: 0,
    queryFn: ({ signal, pageParam }) => listStores(organizationId, 'ACTIVE', storeSearch, pageParam, signal),
    getNextPageParam: (page, pages) => page.hasMore ? pages.length * 25 : undefined, refetchOnWindowFocus: true, staleTime: 0,
  })
  const storeItems = (stores.data?.pages ?? []).flatMap(page => page.items)
  const readableStores = uniqueBy(storeItems.filter(store => store.active && store.allowedActions.includes('READ_TRANSFERS')), store => store.id)
  const queueStoreIds = (storeFilter ? readableStores.filter(store => store.id === storeFilter) : readableStores).map(store => store.id)
  const queue = useInfiniteQuery({
    queryKey: [...scope, 'transfers', 'queue', storeSearch, status, queueStoreIds], enabled: !transferId && queueStoreIds.length > 0,
    initialPageParam: {} as StoreCursorStates,
    queryFn: ({ signal, pageParam }) => loadStoreCursorPages(queueStoreIds, pageParam,
      (storeId, cursor, requestSignal) => listTransfers(organizationId, storeId, status, requestSignal ?? signal, cursor, 50),
      transferRequestScheduler, signal),
    getNextPageParam: page => page.hasMore ? page.nextCursors : undefined, refetchOnWindowFocus: true, staleTime: 0,
  })
  const transit = useInfiniteQuery({
    queryKey: [...scope, 'transfers', 'in-transit', storeSearch, queueStoreIds], enabled: !transferId && queueStoreIds.length > 0,
    initialPageParam: {} as StoreCursorStates,
    queryFn: ({ signal, pageParam }) => loadStoreCursorPages(queueStoreIds, pageParam,
      (storeId, cursor, requestSignal) => listTransfersInTransit(organizationId, storeId, requestSignal ?? signal, cursor, 50),
      transferRequestScheduler, signal),
    getNextPageParam: page => page.hasMore ? page.nextCursors : undefined, refetchOnWindowFocus: true, staleTime: 0,
  })
  const evidence = useInfiniteQuery({
    queryKey: [...scope, 'transfers', 'evidence', storeSearch, queueStoreIds], enabled: !transferId && queueStoreIds.length > 0,
    initialPageParam: {} as StoreCursorStates,
    queryFn: ({ signal, pageParam }) => loadStoreCursorPages(queueStoreIds, pageParam,
      (storeId, cursor, requestSignal) => listTransferDiscrepancies(organizationId, storeId, requestSignal ?? signal, cursor, 50),
      transferRequestScheduler, signal),
    getNextPageParam: page => page.hasMore ? page.nextCursors : undefined, refetchOnWindowFocus: true, staleTime: 0,
  })
  const queueItems = uniqueBy((queue.data?.pages ?? []).flatMap(page => page.items), item => item.id)
    .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt) || right.id.localeCompare(left.id))
  const transitItems = uniqueBy((transit.data?.pages ?? []).flatMap(page => page.items), item => `${item.transferId}:${item.lineId}`)
  const evidenceItems = uniqueBy((evidence.data?.pages ?? []).flatMap(page => page.items), item => item.id)
  const detail = useQuery({ queryKey: [...scope, 'transfers', 'detail', transferId], enabled: Boolean(transferId), queryFn: ({ signal }) => getTransfer(organizationId, transferId!, signal), refetchOnWindowFocus: true, staleTime: 0 })

  async function refreshTransferData() {
    await client.invalidateQueries({ queryKey: scope })
  }

  async function refreshPendingIntents() {
    await client.invalidateQueries({ queryKey: [...scope, 'transfer-command-intents'] })
  }

  async function submitPreparedCommand(intent: TransferCommandIntent, intentId: string) {
    await markTransferCommandIntentSubmitted(organizationId, intentId, new AbortController().signal)
    const outcome = await executeTransferCommand(organizationId, intent, new AbortController().signal)
    let acknowledged = true
    try { await acknowledgeTransferCommandIntent(organizationId, intentId, new AbortController().signal) }
    catch { acknowledged = false }
    setPendingInspection(null)
    setRecoveryMissing(false)
    setCommandError('')
    setCommandNotice(acknowledged
      ? `Saved as ${transferStatusLabel(outcome.status)}. Server record ${outcome.transferId.slice(0, 8)} · version ${outcome.version}.`
      : `Saved as ${transferStatusLabel(outcome.status)}. The result is confirmed; its recovery record still needs acknowledgement.`)
    await Promise.all([refreshTransferData(), refreshPendingIntents()])
    if (transferId !== outcome.transferId) await navigate({ to: '/organizations/$organizationId/transfers/$transferId', params: { organizationId, transferId: outcome.transferId } })
  }

  async function runIntent(intent: TransferCommandIntent) {
    if (commandBlocked) return
    setCommandBusy(true)
    setCommandError('')
    setCommandNotice('')
    setRecoveryMissing(false)
    setPendingInspection(null)
    let intentId: string | undefined
    try {
      const prepared = await prepareTransferCommandIntent(organizationId, intent, new AbortController().signal)
      intentId = prepared.id
      await submitPreparedCommand(intent, intentId)
    } catch (error) {
      setCommandError(errorMessage(error))
      if (intentId && !uncertainOutcome(error)) {
        try {
          await resolveFailedTransferCommandIntent(organizationId, intentId,
            error instanceof ApiError && error.status === 409 ? 'CONFLICTED' : 'REJECTED', new AbortController().signal)
          setCommandNotice('The server rejected the command before committing it. Review the current transfer and permissions before starting another request.')
        } catch {
          setCommandNotice('The command failed, but its durable recovery record could not be resolved. Inspect it before submitting another command.')
        }
      } else if (intentId) {
        setCommandNotice('The server saved the exact command, but its result is unknown. Inspect the saved outcome or retry the original request after checking current authorization.')
      } else {
        setCommandNotice('The command may have been prepared on the server. Reload the transfer workspace to check for a saved recovery record; no stock command is retried automatically.')
      }
      setPendingInspection(null)
      await refreshPendingIntents()
    } finally { setCommandBusy(false) }
  }

  async function inspectSavedOutcome() {
    if (!pendingCommand || commandBusy) return
    setCommandBusy(true); setCommandError(''); setCommandNotice('Checking the saved server outcome without repeating the stock operation.')
    try {
      const result = await inspectTransferCommandIntent(organizationId, pendingCommand.id, new AbortController().signal)
      if (result.redacted) {
        if (result.executionStatus === 'COMMITTED_AWAITING_ACKNOWLEDGEMENT') {
          await acknowledgeTransferCommandIntent(organizationId, pendingCommand.id, new AbortController().signal)
          setPendingInspection(null); setRecoveryMissing(false)
          setCommandNotice('The saved command committed and was safely acknowledged. Transfer details are hidden because access changed.')
          await refreshPendingIntents()
        } else {
          setPendingInspection(result)
          setRecoveryMissing(false)
          setCommandNotice('Access to this transfer changed. The server can safely resolve the saved command without showing its payload or transfer details.')
        }
        return
      }
      if (result.committedOutcome) {
        await acknowledgeTransferCommandIntent(organizationId, pendingCommand.id, new AbortController().signal)
        setPendingInspection(null); setRecoveryMissing(false)
        setCommandNotice(`Recovered ${transferStatusLabel(result.committedOutcome.status)} from the saved command record.`)
        await Promise.all([refreshTransferData(), refreshPendingIntents()])
        if (transferId !== result.committedOutcome.transferId) await navigate({ to: '/organizations/$organizationId/transfers/$transferId', params: { organizationId, transferId: result.committedOutcome.transferId } })
      } else {
        setPendingInspection(result)
        setRecoveryMissing(result.executionStatus === 'SUBMITTED_AWAITING_OUTCOME' || result.executionStatus === 'PREPARED')
        setCommandNotice(result.executionStatus === 'SUBMITTED_AWAITING_OUTCOME'
          ? 'No committed outcome is visible yet. The original request may still be in flight; retrying submits the exact saved payload with the same request key, so the server can return its original result without repeating stock effects.'
          : 'This command was saved but not submitted. Inspecting found no committed outcome; submission will use the saved request key and payload under your current permissions.')
      }
    } catch (error) {
      setCommandError(errorMessage(error))
      setCommandNotice('Outcome inspection failed. Retry this read when the service is available; no command was submitted.')
    } finally { setCommandBusy(false) }
  }

  async function retrySavedCommand() {
    if (!pendingCommand || !pendingInspection || pendingInspection.redacted || commandBusy) return
    const intent = { operation: pendingInspection.operation, transferId: pendingInspection.transferId, body: pendingInspection.payload }
    if (!isTransferCommandIntent(intent)) {
      setCommandError('The saved transfer command has an unsupported shape. Contact support before making another stock change.')
      return
    }
    setCommandBusy(true); setCommandError(''); setCommandNotice('Submitting the original command with its saved request key and exact payload.')
    try {
      await submitPreparedCommand(intent, pendingCommand.id)
    } catch (error) {
      setCommandError(errorMessage(error))
      if (!uncertainOutcome(error)) {
        try {
          await resolveFailedTransferCommandIntent(organizationId, pendingCommand.id,
            error instanceof ApiError && error.status === 409 ? 'CONFLICTED' : 'REJECTED', new AbortController().signal)
          setPendingInspection(null); setRecoveryMissing(false)
          setCommandNotice('The saved command was rejected without a committed receipt. Review current transfer state before a new request.')
        } catch {
          setCommandNotice('The server could not confirm whether this command completed. Keep the recovery record and inspect it again before taking further action.')
        }
      } else {
        setCommandNotice('The original request still has no confirmed response. Inspect or retry this exact saved command after checking current authorization.')
      }
      setPendingInspection(null)
      await refreshPendingIntents()
    } finally { setCommandBusy(false) }
  }

  async function resolveRedactedCommand() {
    if (!pendingCommand || !pendingInspection?.redacted || commandBusy) return
    setCommandBusy(true); setCommandError(''); setCommandNotice('Checking and resolving the saved command under the server transaction lock.')
    const resolution = pendingInspection.executionStatus === 'PREPARED' ? 'REJECTED' : 'CONFLICTED'
    try {
      await resolveFailedTransferCommandIntent(organizationId, pendingCommand.id, resolution, new AbortController().signal)
      setPendingInspection(null); setRecoveryMissing(false)
      setCommandNotice('The server confirmed that no command committed and released the blocked request. Transfer details remain hidden.')
      await refreshPendingIntents()
    } catch (error) {
      setCommandError(errorMessage(error))
      setCommandNotice('The server could not safely resolve this request yet. Keep it for recovery and inspect its status again.')
      await refreshPendingIntents()
    } finally { setCommandBusy(false) }
  }

  const storeMap = useMemo(() => new Map(storeItems.map(store => [store.id, store])), [storeItems])
  const transfersHref = `/organizations/${organizationId}/transfers`
  return <section className="space-y-7">
    <header className="space-y-2"><p className="text-xs font-semibold uppercase tracking-[0.16em] text-primary">Stock movement between stores</p><h2 className="text-3xl font-semibold">Stock transfers</h2><p className="max-w-3xl text-sm text-muted-foreground">Review each request, physical handoff and receiving event against its saved stock and custody records.</p></header>
    {pendingIntents.isError && <div role="alert" className="rounded-xl border border-destructive/30 p-4 text-sm text-destructive">The server could not confirm whether a transfer command is awaiting recovery. New transfer commands are paused until this check succeeds. <Button variant="outline" onClick={() => { void pendingIntents.refetch() }}>Check saved commands again</Button></div>}
    {pendingCommand && <section aria-label="Recover saved transfer command" className="space-y-3 rounded-2xl border border-amber-500/50 bg-card p-5">
      <div><h3 className="text-lg font-semibold">A transfer command needs review</h3><p className="mt-2 text-sm text-muted-foreground">{pendingCommand.redacted
        ? 'Your access changed. The command is still recoverable, with transfer details and its saved request hidden.'
        : `${pendingCommand.operation} · request key ${pendingCommand.requestId}. The exact command is stored on the server for this account and organization, so recovery can continue after this tab closes.`}</p></div>
      {commandNotice && <p role="status" className="text-sm">{commandNotice}</p>}{commandError && <p role="alert" className="text-sm text-destructive">{commandError}</p>}
      <div className="flex flex-wrap gap-3"><Button variant="outline" disabled={commandBusy} onClick={() => { void inspectSavedOutcome() }}>{commandBusy ? 'Checking saved outcome…' : 'Inspect saved outcome'}</Button>
        {pendingInspection?.redacted && <Button disabled={commandBusy} onClick={() => { void resolveRedactedCommand() }}>{commandBusy ? 'Resolving saved command…' : 'Resolve without transfer details'}</Button>}
        {recoveryMissing && pendingInspection && !pendingInspection.redacted && <Button disabled={commandBusy} onClick={() => { void retrySavedCommand() }}>{commandBusy ? 'Retrying saved command…' : 'Retry exact saved command'}</Button>}</div>
    </section>}
    {!pendingCommand && commandNotice && <p role="status" className="rounded-xl border border-border p-4 text-sm">{commandNotice}</p>}
    {!pendingCommand && commandError && <p role="alert" className="rounded-xl border border-destructive/30 p-4 text-sm text-destructive">{commandError}</p>}
    {transferId ? detail.isPending ? <StateMessage state="loading" title="Loading transfer record" description="Confirming the current transfer and your participating-store access." />
      : detail.isError ? <div className="space-y-4"><StateMessage state={detail.error instanceof ApiError && detail.error.status === 403 ? 'forbidden' : 'unavailable'} title="Transfer unavailable" description={errorMessage(detail.error)} /><Button variant="outline" onClick={() => { void detail.refetch() }}>Retry transfer read</Button><AppLink href={transfersHref} className="inline-flex text-primary underline">Back to transfers</AppLink></div>
        : <TransferDetailView organizationId={organizationId} transfer={detail.data} stores={[...storeMap.values()]} onCommand={runIntent} busy={commandBlocked} onRefresh={refreshTransferData} />
      : stores.isPending ? <StateMessage state="loading" title="Checking transfer access" description="Resolving your current store permissions." />
        : stores.isError ? <div className="space-y-4"><StateMessage state={stores.error instanceof ApiError && stores.error.status === 403 ? 'forbidden' : 'unavailable'} title="Transfer workspace unavailable" description={errorMessage(stores.error)} /><Button variant="outline" onClick={() => { void stores.refetch() }}>Retry store access</Button></div>
          : readableStores.length === 0 ? <StateMessage state="forbidden" title="No transfer access for these stores" description="Ask an organization administrator to grant transfer reading and the actions needed for your role." />
            : <>
              <div className="grid gap-4 rounded-2xl border border-border bg-card p-5 sm:grid-cols-2">
                <Field label="Find a store">{id => <input id={id} type="search" value={storeSearch} onChange={event => { setStoreSearch(event.target.value); setStoreFilter('') }} className={inputClass} placeholder="Search accessible stores" />}</Field>
                <Field label="Store queue">{id => <select id={id} value={storeFilter} onChange={event => { setStoreFilter(event.target.value) }} className={inputClass}><option value="">All matching stores loaded below</option>{readableStores.map(store => <option key={store.id} value={store.id}>{store.code} · {store.name}</option>)}</select>}</Field>
                <Field label="Transfer status">{id => <select id={id} value={status} onChange={event => { setStatus(event.target.value as TransferStatus | '') }} className={inputClass}>{transferStatusOptions.map(option => <option key={option.value || 'all'} value={option.value}>{option.label}</option>)}</select>}</Field>
                {stores.hasNextPage && <Button variant="outline" disabled={stores.isFetchingNextPage} onClick={() => { void stores.fetchNextPage() }}>{stores.isFetchingNextPage ? 'Loading stores…' : 'Load more accessible stores'}</Button>}
              </div>
              {queue.isPending ? <StateMessage state="loading" title="Loading transfer queue" description="Reading only stores where you currently have transfer access." />
                : queue.isError ? <div className="space-y-4"><StateMessage state={queue.error instanceof ApiError && queue.error.status === 403 ? 'forbidden' : 'unavailable'} title="Transfer queue unavailable" description={errorMessage(queue.error)} /><Button variant="outline" onClick={() => { void queue.refetch() }}>Retry queue</Button></div>
                  : queueItems.length === 0 && transitItems.length === 0 && evidenceItems.length === 0 ? <StateMessage state="empty" title="No transfers in this view" description="Change the store or status filter, or create a request from verified available source stock." />
                    : <TransferQueue items={queueItems} stores={storeMap} transit={transitItems} discrepancies={evidenceItems} organizationId={organizationId}
                      onReload={() => { void queue.refetch() }} loading={queue.isFetching}
                      canLoadTransfers={Boolean(queue.hasNextPage)} loadingTransfers={queue.isFetchingNextPage} onLoadTransfers={() => { void queue.fetchNextPage() }}
                      canLoadTransit={Boolean(transit.hasNextPage)} loadingTransit={transit.isFetchingNextPage} onLoadTransit={() => { void transit.fetchNextPage() }}
                      transitPending={transit.isPending} transitError={transit.isError} retryTransit={() => { void transit.refetch() }}
                      canLoadEvidence={Boolean(evidence.hasNextPage)} loadingEvidence={evidence.isFetchingNextPage} onLoadEvidence={() => { void evidence.fetchNextPage() }}
                      evidencePending={evidence.isPending} evidenceError={evidence.isError} retryEvidence={() => { void evidence.refetch() }} />}
              {readableStores.some(store => store.active && store.allowedActions.includes('REQUEST_TRANSFER')) && <CreateTransferPanel organizationId={organizationId} administrator={organization.role === 'ORG_ADMIN'} onCommand={runIntent} busy={commandBlocked} />}
            </>}
  </section>
}

function TransferQueue({ items, stores, transit, discrepancies, organizationId, onReload, loading,
  canLoadTransfers, loadingTransfers, onLoadTransfers, canLoadTransit, loadingTransit, onLoadTransit,
  transitPending, transitError, retryTransit, canLoadEvidence, loadingEvidence, onLoadEvidence,
  evidencePending, evidenceError, retryEvidence }: {
  items: TransferQueueItem[], stores: Map<string, Store>, transit: TransferInTransitLine[], discrepancies: TransferDiscrepancyRecord[],
  organizationId: string, onReload: () => void, loading: boolean,
  canLoadTransfers: boolean, loadingTransfers: boolean, onLoadTransfers: () => void,
  canLoadTransit: boolean, loadingTransit: boolean, onLoadTransit: () => void,
  transitPending: boolean, transitError: boolean, retryTransit: () => void,
  canLoadEvidence: boolean, loadingEvidence: boolean, onLoadEvidence: () => void,
  evidencePending: boolean, evidenceError: boolean, retryEvidence: () => void,
}) {
  const transitByTransfer = new Map<string, TransferInTransitLine[]>()
  for (const line of transit) transitByTransfer.set(line.transferId, [...(transitByTransfer.get(line.transferId) ?? []), line])
  const discrepancyTransfers = new Set(discrepancies.map(item => item.transferId))
  return <section aria-label="Transfer queue" className="space-y-4">
    <div className="flex flex-wrap items-center justify-between gap-3"><h3 className="text-xl font-semibold">Transfer queue</h3><Button variant="outline" disabled={loading} onClick={onReload}>{loading ? 'Refreshing…' : 'Refresh queue'}</Button></div>
    {items.length === 0 && <p className="text-sm text-muted-foreground">No transfers match the selected queue filters.</p>}
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
      {transitError && <p role="alert" className="text-sm text-destructive">In-transit records could not be loaded. <Button variant="outline" onClick={retryTransit}>Retry in-transit records</Button></p>}
      {!transitPending && !transitError && transit.length === 0 && <p className="text-sm text-muted-foreground">No outstanding in-transit lines are visible for these stores.</p>}
      {transit.length > 0 && <ul className="space-y-2">{transit.map(line => <li key={`${line.transferId}:${line.lineId}`} className="flex flex-wrap items-center justify-between gap-3 text-sm"><span>Line {line.lineId.slice(0, 8)} · {line.outstanding} units · {formatTransferAge(line.ageSeconds)}</span><AppLink href={`/organizations/${organizationId}/transfers/${line.transferId}`} className="text-primary underline">Open transfer</AppLink></li>)}</ul>}
      {canLoadTransit && <Button variant="outline" disabled={loadingTransit} onClick={onLoadTransit}>{loadingTransit ? 'Loading in-transit lines…' : 'Load more in-transit lines'}</Button>}
    </section>
    <section aria-label="Transfer evidence history" className="space-y-3 rounded-2xl border border-border p-5">
      <h4 className="text-lg font-semibold">Transfer evidence history</h4>
      {evidencePending && <p role="status" className="text-sm">Loading reconciliation evidence…</p>}
      {evidenceError && <p role="alert" className="text-sm text-destructive">Transfer evidence could not be loaded. <Button variant="outline" onClick={retryEvidence}>Retry evidence history</Button></p>}
      {!evidencePending && !evidenceError && discrepancies.length === 0 && <p className="text-sm text-muted-foreground">No transfer reconciliation evidence is visible for these stores.</p>}
      {discrepancies.length > 0 && <ul className="space-y-2">{discrepancies.map(item => <li key={item.id} className="flex flex-wrap items-center justify-between gap-3 text-sm"><span>{item.type} · {new Date(item.recordedAt).toLocaleString()} · {discrepancyStatus(item)}</span><AppLink href={`/organizations/${organizationId}/transfers/${item.transferId}`} className="text-primary underline">Open evidence</AppLink></li>)}</ul>}
      {canLoadEvidence && <Button variant="outline" disabled={loadingEvidence} onClick={onLoadEvidence}>{loadingEvidence ? 'Loading evidence…' : 'Load more evidence'}</Button>}
    </section>
  </section>
}

export function CreateTransferPanel({ organizationId, administrator, onCommand, busy }: {
  organizationId: string, administrator: boolean,
  onCommand: (intent: TransferCommandIntent) => Promise<void>, busy: boolean,
}) {
  const scope = useTenantKey()
  const [storeSearch, setStoreSearch] = useState('')
  const [sourceStoreId, setSourceStoreId] = useState('')
  const [destinationStoreId, setDestinationStoreId] = useState('')
  const [destinationLocationSearch, setDestinationLocationSearch] = useState('')
  const [lines, setLines] = useState<DraftTransferLine[]>([newDraftTransferLine()])
  const [reason, setReason] = useState('')
  const [evidenceReference, setEvidenceReference] = useState('')
  const [showForm, setShowForm] = useState(false)
  const storesQuery = useInfiniteQuery({
    queryKey: [...scope, 'transfer-create', 'stores', storeSearch], initialPageParam: 0,
    queryFn: ({ signal, pageParam }) => listStores(organizationId, 'ACTIVE', storeSearch, pageParam, signal),
    getNextPageParam: (page, pages) => page.hasMore ? pages.length * 25 : undefined, staleTime: 0,
  })
  const stores = uniqueBy((storesQuery.data?.pages ?? []).flatMap(page => page.items), store => store.id)
  const sourceStores = stores.filter(store => store.active && store.allowedActions.includes('REQUEST_TRANSFER') && store.allowedActions.includes('READ_INVENTORY'))
  const selectedSourceStoreId = sourceStores.some(store => store.id === sourceStoreId) ? sourceStoreId : sourceStores[0]?.id ?? ''
  const sourceStore = sourceStores.find(store => store.id === selectedSourceStoreId)
  const destinations = stores.filter(store => store.active && store.id !== selectedSourceStoreId && store.allowedActions.includes('READ_STORE'))
  const selectedDestinationStoreId = destinations.some(store => store.id === destinationStoreId) ? destinationStoreId : ''
  const destinationStore = destinations.find(store => store.id === selectedDestinationStoreId)
  const positions = useInfiniteQuery({ queryKey: [...scope, 'transfer-create', 'positions', selectedSourceStoreId], enabled: Boolean(selectedSourceStoreId), initialPageParam: 0,
    queryFn: ({ signal, pageParam }) => listInventoryAvailableByLot(organizationId, selectedSourceStoreId, { offset: pageParam, limit: 100 }, signal),
    getNextPageParam: (page, pages) => page.hasMore ? pages.length * 100 : undefined, staleTime: 0 })
  const sourcePositions = (positions.data?.pages ?? []).flatMap(page => page.items)
  const destinationLocations = useInfiniteQuery({
    queryKey: [...scope, 'transfer-create', 'destination-locations', selectedDestinationStoreId, destinationLocationSearch],
    enabled: Boolean(selectedDestinationStoreId), initialPageParam: 0,
    queryFn: ({ signal, pageParam }) => listStorageLocations(organizationId, selectedDestinationStoreId, false, destinationLocationSearch, pageParam, signal),
    getNextPageParam: (page, pages) => page.hasMore ? pages.length * 25 : undefined, staleTime: 0,
  })
  const destinationLocationItems = uniqueBy((destinationLocations.data?.pages ?? []).flatMap(page => page.items), location => location.id)
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
    queryKey: [...scope, 'transfer-create', 'source-location', selectedSourceStoreId, locationId],
    queryFn: ({ signal }: { signal: AbortSignal }) => getStorageLocation(organizationId, selectedSourceStoreId, locationId, signal), staleTime: 0,
  })) })
  const route = useQuery({ queryKey: [...scope, 'transfer-create', 'lane', selectedSourceStoreId, selectedDestinationStoreId], enabled: administrator && Boolean(selectedSourceStoreId && selectedDestinationStoreId), queryFn: ({ signal }) => lookupLane(organizationId, selectedSourceStoreId, selectedDestinationStoreId, signal), staleTime: 0 })
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
  const canSubmit = Boolean(sourceStore && destinationStore && positions.data && destinationLocations.data
    && validLines && aggregateAvailability && allRoutesMatch && reason.trim() && evidenceReference.trim() && routeAllowsRequest && !busy)

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
      <Field label="Find a source or destination store">{id => <input id={id} type="search" value={storeSearch} onChange={event => { setStoreSearch(event.target.value); setSourceStoreId(''); setDestinationStoreId(''); setLines([newDraftTransferLine()]) }} className={inputClass} placeholder="Search stores you can access" />}</Field>
      <div className="flex flex-wrap items-end gap-3"><Field label="Source store">{id => <select id={id} required value={selectedSourceStoreId} onChange={event => { const nextStoreId = event.target.value; setSourceStoreId(nextStoreId); setLines([newDraftTransferLine()]); if (nextStoreId === destinationStoreId) setDestinationStoreId('') }} className={inputClass}><option value="">Choose a source</option>{sourceStores.map(store => <option key={store.id} value={store.id}>{store.code} · {store.name}</option>)}</select>}</Field><Field label="Destination store">{id => <select id={id} required value={selectedDestinationStoreId} onChange={event => { setDestinationStoreId(event.target.value); setLines(current => current.map(line => ({ ...line, destinationLocationId: '' }))) }} className={inputClass}><option value="">Choose a destination</option>{destinations.map(store => <option key={store.id} value={store.id}>{store.code} · {store.name}</option>)}</select>}</Field></div>
      {storesQuery.hasNextPage && <Button type="button" variant="outline" disabled={storesQuery.isFetchingNextPage} onClick={() => { void storesQuery.fetchNextPage() }}>{storesQuery.isFetchingNextPage ? 'Loading stores…' : 'Load more stores'}</Button>}
      {storesQuery.isError && <p role="alert" className="sm:col-span-2 text-sm text-destructive">Store access could not be checked. {errorMessage(storesQuery.error)} <Button type="button" variant="outline" onClick={() => { void storesQuery.refetch() }}>Retry stores</Button></p>}
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
            <Field label={positionLabel}>{id => <select id={id} required value={line.sourcePositionId} onChange={event => { updateLine(line.id, { sourcePositionId: event.target.value, quantity: '' }) }} disabled={positions.isPending || !selectedSourceStoreId} className={inputClass}><option value="">Choose an available stock position</option>{sourcePositions.map(item => {
        const itemProduct = productById.get(item.productId)
        const itemLot = lotById.get(`${item.productId}:${item.lotId}`)
        const free = item.available === null ? 'count unknown' : `${item.available} ${item.unit} available`
        const duplicate = selectedPositionIds.includes(item.positionId) && item.positionId !== line.sourcePositionId
        return <option key={item.positionId} value={item.positionId} disabled={duplicate || item.initialization !== 'INITIALIZED' || item.unit !== 'EA' || item.available === null || item.available <= 0}>{itemProduct ? `${itemProduct.sku} · ${itemProduct.name}` : `Product ${item.productId.slice(0, 8)}`} · {itemLot?.lotCode ?? `Lot ${item.lotId.slice(0, 8)}`} · location ${item.locationId.slice(0, 8)} · {free}{duplicate ? ' · already selected' : ''}</option>
      })}</select>}</Field>
            <Field label="Search destination locations">{id => <input id={id} type="search" value={destinationLocationSearch} onChange={event => { setDestinationLocationSearch(event.target.value) }} className={inputClass} placeholder="Search location code or name" />}</Field>
            <Field label={destinationLabel}>{id => <select id={id} required value={line.destinationLocationId} onChange={event => { updateLine(line.id, { destinationLocationId: event.target.value }) }} disabled={!destinationStore || destinationLocations.isPending} className={inputClass}><option value="">Choose an active location</option>{destinationLocationItems.map(location => <option key={location.id} value={location.id} disabled={!location.usable}>{location.code} · {location.name} · {location.zoneId}{location.usable ? '' : ' · unavailable'}</option>)}</select>}</Field>
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
        {positions.hasNextPage && <Button type="button" variant="outline" disabled={positions.isFetchingNextPage} onClick={() => { void positions.fetchNextPage() }}>{positions.isFetchingNextPage ? 'Loading source positions…' : 'Load more available source positions'}</Button>}
        {destinationLocations.hasNextPage && <Button type="button" variant="outline" disabled={destinationLocations.isFetchingNextPage} onClick={() => { void destinationLocations.fetchNextPage() }}>{destinationLocations.isFetchingNextPage ? 'Loading locations…' : 'Load more destination locations'}</Button>}
        <Button type="button" variant="outline" disabled={lines.length >= 50} onClick={() => { setLines(current => current.length < 50 ? [...current, newDraftTransferLine()] : current) }}>Add transfer line</Button>
        {duplicatePosition && <p role="alert" className="text-sm text-destructive">Choose a different source position for each line.</p>}
        {!aggregateAvailability && <p role="alert" className="text-sm text-destructive">Combined line quantities exceed the observed source availability for a product and lot.</p>}
        {administrator && lines.some(line => line.sourcePositionId && line.destinationLocationId) && !allRoutesMatch && route.data?.lane && <p role="alert" className="text-sm text-destructive">One or more lines use zones outside the configured lane.</p>}
      </div>
      <div className="sm:col-span-2 rounded-xl border border-border p-4 text-sm"><h4 className="font-semibold">Route and storage context</h4>
        {administrator && selectedSourceStoreId && selectedDestinationStoreId && (route.isPending ? <p role="status" className="mt-2">Checking configured transport route…</p> : route.isError ? <p role="alert" className="mt-2 text-destructive">{errorMessage(route.error)} <Button type="button" variant="outline" onClick={() => { void route.refetch() }}>Retry route check</Button></p> : <p className="mt-2">Transport route: {route.data?.status === 'CONFIGURED' ? 'Configured and available' : `Unavailable (${route.data?.status ?? 'not checked'})`}. The server rechecks lane, zones and stock when each command is saved.</p>)}
        {!administrator && <p className="mt-2">The server checks the current transport route, compatible zones and stock state when you submit.</p>}</div>
      {positions.isError && <p role="alert" className="sm:col-span-2 text-sm text-destructive">Source availability could not be checked: {errorMessage(positions.error)} <Button type="button" variant="outline" onClick={() => { void positions.refetch() }}>Retry availability</Button></p>}
      {(sourceLocationQueries.some(query => query.isError) || destinationLocations.isError) && <p role="alert" className="sm:col-span-2 text-sm text-destructive">Storage locations could not be checked. Confirm store access and retry the failed read. <Button type="button" variant="outline" onClick={() => { for (const query of sourceLocationQueries) if (query.isError) void query.refetch(); if (destinationLocations.isError) void destinationLocations.refetch() }}>Retry locations</Button></p>}
      {positions.isPending && selectedSourceStoreId && <p role="status" className="sm:col-span-2">Loading current source availability…</p>}
      <Field label="Reason for transfer">{id => <textarea id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
      <Field label="Evidence reference">{id => <input id={id} required maxLength={256} value={evidenceReference} onChange={event => { setEvidenceReference(event.target.value) }} className={inputClass} placeholder="Document or review reference" />}</Field>
      <Button type="submit" className="sm:col-span-2" disabled={!canSubmit}>{busy ? 'Saving request…' : 'Create transfer request'}</Button>
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
  const source = storeMap.get(transfer.sourceStoreId) ?? sourceMetadata.data
  const destination = storeMap.get(transfer.destinationStoreId) ?? destinationMetadata.data
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
    <div className="flex flex-wrap items-start justify-between gap-3"><div><AppLink href={`/organizations/${organizationId}/transfers`} className="text-sm text-primary underline">Back to transfer queue</AppLink><h3 className="mt-3 break-all text-2xl font-semibold">Transfer {transfer.id}</h3><p className="mt-2">{source ? `${source.code} · ${source.name}` : `Source ${transfer.sourceStoreId}`} <span aria-hidden="true">→</span> {destination ? `${destination.code} · ${destination.name}` : `Destination ${transfer.destinationStoreId}`}</p><p className="mt-2 text-sm text-muted-foreground">{transferStatusLabel(transfer.status)} · version {transfer.version} · outstanding custody {transfer.outstandingQuantity} units</p></div><Button variant="outline" disabled={busy} onClick={() => { void onRefresh() }}>Refresh saved record</Button></div>
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
            : <ol className="space-y-3">{custodyItems.map((movement: TransferCustodyMovement) => <li key={movement.id} className="rounded-xl border border-border p-4"><div className="flex flex-wrap justify-between gap-2"><p className="font-semibold">{movement.type} · {movement.quantity} units</p><time className="text-sm text-muted-foreground" dateTime={movement.recordedAt}>{new Date(movement.recordedAt).toLocaleString()}</time></div><p className="mt-2 text-sm">Actor {movement.actorId} · outstanding {movement.outstandingBefore} → {movement.outstandingAfter} · line {movement.lineId.slice(0, 8)}</p><p className="mt-2 break-all text-sm">Reason: {movement.reason} · evidence: {movement.evidenceReference}</p>{movement.stockMovementId && <p className="mt-2 break-all text-xs text-muted-foreground">Stock movement {movement.stockMovementId} · position {movement.stockPositionId} · store {movement.stockStoreId}</p>}</li>)}</ol>}
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
    const body = { requestId: crypto.randomUUID(), expectedVersion: transfer.version, documentReference: documentReference.trim(), physicallyReceivedAt: new Date().toISOString(),
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
