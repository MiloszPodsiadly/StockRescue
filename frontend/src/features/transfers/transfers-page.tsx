import { useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from '@tanstack/react-router'
import { AppLink } from '@/components/app-link'
import { Button } from '@/components/ui/button'
import { Field, inputClass } from '@/components/form-field'
import { StateMessage } from '@/components/state-message'
import { ApiError, errorMessage } from '@/api/client'
import { listStores } from '@/api/stores'
import type { Store } from '@/api/stores'
import { listInventoryAvailableByLot, listInventoryPositions } from '@/api/inventory'
import type { StockPosition } from '@/api/inventory'
import { listStorageLocations } from '@/api/storage-locations'
import { listLots } from '@/api/lots'
import { listProducts } from '@/api/products'
import { lookupLane } from '@/api/configuration'
import {
  executeTransferCommand, getTransfer, listTransferCustody, listTransferDiscrepancies,
  listTransfers, listTransfersInTransit, recoverTransferCommand,
} from '@/api/transfers'
import type {
  Transfer, TransferCommandIntent, TransferCustodyMovement, TransferDiscrepancyRecord,
  TransferInTransitLine, TransferQueueItem, TransferStatus,
} from '@/api/transfers'
import { OrganizationPage } from '../organizations/organization-page'
import { useOrganization } from '../organizations/organization-context'
import { useTenantKey } from '../organizations/tenant-requests'
import { formatTransferAge, transferStatusLabel, transferStatusOptions } from './transfer-format'

type SavedCommand = { intent: TransferCommandIntent, savedAt: string }
type QueueSnapshot = { items: TransferQueueItem[], transit: TransferInTransitLine[], discrepancies: TransferDiscrepancyRecord[] }

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isTransferIntent(value: unknown): value is TransferCommandIntent {
  if (!isRecord(value) || !isRecord(value.body) || typeof value.body.requestId !== 'string'
    || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value.body.requestId)) return false
  const allowed = ['CREATE', 'APPROVE', 'REJECT', 'CANCEL', 'START_PICK', 'PICK', 'DISPATCH', 'RECEIVE', 'CONFIRM_LOSS', 'OPEN_DISCREPANCY', 'REQUEST_RETURN', 'CONFIRM_RETURN']
  if (typeof value.operation !== 'string' || !allowed.includes(value.operation)) return false
  return value.operation === 'CREATE' ? value.transferId === null : typeof value.transferId === 'string'
}

function savedCommandKey(organizationId: string) { return `stockrescue.transfer-command.${organizationId}` }

function readSavedCommand(organizationId: string): TransferCommandIntent | null {
  try {
    const saved = sessionStorage.getItem(savedCommandKey(organizationId))
    if (!saved) return null
    const candidate: unknown = JSON.parse(saved)
    if (isRecord(candidate) && isTransferIntent(candidate.intent)) return candidate.intent
    sessionStorage.removeItem(savedCommandKey(organizationId))
  } catch { /* Browser storage is an optional recovery aid. */ }
  return null
}

function storeSavedCommand(organizationId: string, intent: TransferCommandIntent) {
  try { sessionStorage.setItem(savedCommandKey(organizationId), JSON.stringify({ intent, savedAt: new Date().toISOString() } satisfies SavedCommand)) }
  catch { /* Recovery remains available in this view even when browser storage is disabled. */ }
}

function clearSavedCommand(organizationId: string) {
  try { sessionStorage.removeItem(savedCommandKey(organizationId)) }
  catch { /* Nothing else is required when browser storage is disabled. */ }
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
  const organization = useOrganization()
  const [status, setStatus] = useState<TransferStatus | ''>('')
  const [storeFilter, setStoreFilter] = useState('')
  const [pendingCommand, setPendingCommand] = useState(() => readSavedCommand(organizationId))
  const [commandBusy, setCommandBusy] = useState(false)
  const [commandError, setCommandError] = useState('')
  const [commandNotice, setCommandNotice] = useState('')
  const [recoveryMissing, setRecoveryMissing] = useState(false)
  const stores = useQuery({ queryKey: [...scope, 'stores', 'transfer-workspace'], queryFn: ({ signal }) => listStores(organizationId, 'ACTIVE', '', 0, signal), refetchOnWindowFocus: true, staleTime: 0 })
  const readableStores = (stores.data?.items ?? []).filter(store => store.active && store.allowedActions.includes('READ_TRANSFERS'))
  const queueStoreIds = (storeFilter ? readableStores.filter(store => store.id === storeFilter) : readableStores).map(store => store.id)
  const queue = useQuery({
    queryKey: [...scope, 'transfers', 'queue', status, queueStoreIds], enabled: !transferId && queueStoreIds.length > 0,
    queryFn: async ({ signal }): Promise<QueueSnapshot> => {
      const pages = await Promise.all(queueStoreIds.map(storeId => listTransfers(organizationId, storeId, status, signal)))
      const items = [...new Map(pages.flatMap(page => page.items).map(item => [item.id, item])).values()]
      const activeTransfers = items.filter(item => ['IN_TRANSIT', 'PARTIALLY_RECEIVED', 'RECONCILIATION_REQUIRED'].includes(item.status))
      const involvedStores = [...new Set(activeTransfers.flatMap(item => [item.sourceStoreId, item.destinationStoreId]).filter(id => queueStoreIds.includes(id)))]
      const [transitPages, discrepancyPages] = await Promise.all([
        Promise.all(involvedStores.map(storeId => listTransfersInTransit(organizationId, storeId, signal))),
        Promise.all(involvedStores.map(storeId => listTransferDiscrepancies(organizationId, storeId, signal))),
      ])
      return { items: items.sort((left, right) => right.updatedAt.localeCompare(left.updatedAt)),
        transit: transitPages.flatMap(page => page.items), discrepancies: discrepancyPages.flatMap(page => page.items) }
    }, refetchOnWindowFocus: true, staleTime: 0,
  })
  const detail = useQuery({ queryKey: [...scope, 'transfers', 'detail', transferId], enabled: Boolean(transferId), queryFn: ({ signal }) => getTransfer(organizationId, transferId!, signal), refetchOnWindowFocus: true, staleTime: 0 })

  async function refreshTransferData() {
    await client.invalidateQueries({ queryKey: scope })
  }

  async function runIntent(intent: TransferCommandIntent, retrySaved = false) {
    if (commandBusy || pendingCommand && !retrySaved) return
    setCommandBusy(true)
    setCommandError('')
    setCommandNotice('')
    setRecoveryMissing(false)
    if (!retrySaved) {
      storeSavedCommand(organizationId, intent)
      setPendingCommand(intent)
    }
    try {
      const outcome = await executeTransferCommand(organizationId, intent, new AbortController().signal)
      clearSavedCommand(organizationId)
      setPendingCommand(null)
      setRecoveryMissing(false)
      setCommandError('')
      setCommandNotice(`Saved as ${transferStatusLabel(outcome.status)}. Server record ${outcome.transferId.slice(0, 8)} · version ${outcome.version}.`)
      await refreshTransferData()
      await navigate({ to: '/organizations/$organizationId/transfers/$transferId', params: { organizationId, transferId: outcome.transferId } })
    } catch (error) {
      setCommandError(errorMessage(error))
      if (uncertainOutcome(error)) {
        setCommandNotice('The response was not confirmed. Inspect the saved command outcome before deciding what to do next.')
      } else if (!retrySaved) {
        clearSavedCommand(organizationId)
        setPendingCommand(null)
      }
    } finally { setCommandBusy(false) }
  }

  async function inspectSavedOutcome() {
    if (!pendingCommand || commandBusy) return
    setCommandBusy(true); setCommandError(''); setCommandNotice('Checking the saved server outcome without repeating the stock operation.')
    try {
      const result = await recoverTransferCommand(organizationId, pendingCommand.operation, pendingCommand.body.requestId, new AbortController().signal)
      clearSavedCommand(organizationId); setPendingCommand(null); setRecoveryMissing(false)
      setCommandNotice(`Recovered ${transferStatusLabel(result.outcome.status)} from the saved command record.`)
      await refreshTransferData()
      await navigate({ to: '/organizations/$organizationId/transfers/$transferId', params: { organizationId, transferId: result.outcome.transferId } })
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        setRecoveryMissing(true)
        setCommandNotice('No saved outcome is visible yet. The original request is still held here; retrying it uses the same request key and exact saved details.')
        setCommandError('The server has no matching command record yet.')
      } else {
        setCommandError(errorMessage(error))
        setCommandNotice('Outcome inspection failed. Retry this read when the service is available; no command was submitted.')
      }
    } finally { setCommandBusy(false) }
  }

  const storeMap = useMemo(() => new Map((stores.data?.items ?? []).map(store => [store.id, store])), [stores.data])
  const transfersHref = `/organizations/${organizationId}/transfers`
  return <section className="space-y-7">
    <header className="space-y-2"><p className="text-xs font-semibold uppercase tracking-[0.16em] text-primary">Stock movement between stores</p><h2 className="text-3xl font-semibold">Stock transfers</h2><p className="max-w-3xl text-sm text-muted-foreground">Review each request, physical handoff and receiving event against its saved stock and custody records.</p></header>
    {pendingCommand && <section aria-label="Recover saved transfer command" className="space-y-3 rounded-2xl border border-amber-500/50 bg-card p-5">
      <div><h3 className="text-lg font-semibold">A transfer command needs review</h3><p className="mt-2 text-sm text-muted-foreground">{pendingCommand.operation} · request key {pendingCommand.body.requestId}. This tab stores the exact command details so the outcome can be checked safely.</p></div>
      {commandNotice && <p role="status" className="text-sm">{commandNotice}</p>}{commandError && <p role="alert" className="text-sm text-destructive">{commandError}</p>}
      <div className="flex flex-wrap gap-3"><Button variant="outline" disabled={commandBusy} onClick={() => { void inspectSavedOutcome() }}>{commandBusy ? 'Checking saved outcome…' : 'Inspect saved outcome'}</Button>
        {recoveryMissing && <Button disabled={commandBusy} onClick={() => { void runIntent(pendingCommand, true) }}>{commandBusy ? 'Retrying saved command…' : 'Retry exact saved command'}</Button>}</div>
    </section>}
    {!pendingCommand && commandNotice && <p role="status" className="rounded-xl border border-border p-4 text-sm">{commandNotice}</p>}
    {!pendingCommand && commandError && <p role="alert" className="rounded-xl border border-destructive/30 p-4 text-sm text-destructive">{commandError}</p>}
    {stores.isPending ? <StateMessage state="loading" title="Checking transfer access" description="Resolving your current store permissions." />
      : stores.isError ? <div className="space-y-4"><StateMessage state={stores.error instanceof ApiError && stores.error.status === 403 ? 'forbidden' : 'unavailable'} title="Transfer workspace unavailable" description={errorMessage(stores.error)} /><Button variant="outline" onClick={() => { void stores.refetch() }}>Retry store access</Button></div>
        : transferId ? detail.isPending ? <StateMessage state="loading" title="Loading transfer record" description="Confirming the current transfer and your participating-store access." />
          : detail.isError ? <div className="space-y-4"><StateMessage state={detail.error instanceof ApiError && detail.error.status === 403 ? 'forbidden' : 'unavailable'} title="Transfer unavailable" description={errorMessage(detail.error)} /><Button variant="outline" onClick={() => { void detail.refetch() }}>Retry transfer read</Button><AppLink href={transfersHref} className="inline-flex text-primary underline">Back to transfers</AppLink></div>
            : <TransferDetailView organizationId={organizationId} transfer={detail.data} stores={[...storeMap.values()]} queueStores={readableStores} discrepancies={queue.data?.discrepancies ?? []} onCommand={runIntent} busy={commandBusy || Boolean(pendingCommand)} onRefresh={refreshTransferData} />
          : readableStores.length === 0 ? <StateMessage state="forbidden" title="No transfer access for these stores" description="Ask an organization administrator to grant transfer reading and the actions needed for your role." />
            : <>
              <div className="grid gap-4 rounded-2xl border border-border bg-card p-5 sm:grid-cols-2">
                <Field label="Store queue">{id => <select id={id} value={storeFilter} onChange={event => { setStoreFilter(event.target.value) }} className={inputClass}><option value="">All stores I can read</option>{readableStores.map(store => <option key={store.id} value={store.id}>{store.code} · {store.name}</option>)}</select>}</Field>
                <Field label="Transfer status">{id => <select id={id} value={status} onChange={event => { setStatus(event.target.value as TransferStatus | '') }} className={inputClass}>{transferStatusOptions.map(option => <option key={option.value || 'all'} value={option.value}>{option.label}</option>)}</select>}</Field>
              </div>
              {queue.isPending ? <StateMessage state="loading" title="Loading transfer queue" description="Reading only stores where you currently have transfer access." />
                : queue.isError ? <div className="space-y-4"><StateMessage state={queue.error instanceof ApiError && queue.error.status === 403 ? 'forbidden' : 'unavailable'} title="Transfer queue unavailable" description={errorMessage(queue.error)} /><Button variant="outline" onClick={() => { void queue.refetch() }}>Retry queue</Button></div>
                  : queue.data.items.length === 0 ? <StateMessage state="empty" title="No transfers in this view" description="Change the store or status filter, or create a request from verified available source stock." />
                    : <TransferQueue items={queue.data.items} stores={storeMap} transit={queue.data.transit} discrepancies={queue.data.discrepancies} organizationId={organizationId} onReload={() => { void queue.refetch() }} loading={queue.isFetching} />}
              {readableStores.some(store => store.active && store.allowedActions.includes('REQUEST_TRANSFER')) && <CreateTransferPanel organizationId={organizationId} stores={stores.data.items} administrator={organization.role === 'ORG_ADMIN'} onCommand={runIntent} busy={commandBusy || Boolean(pendingCommand)} />}
            </>}
  </section>
}

function TransferQueue({ items, stores, transit, discrepancies, organizationId, onReload, loading }: {
  items: TransferQueueItem[], stores: Map<string, Store>, transit: TransferInTransitLine[], discrepancies: TransferDiscrepancyRecord[],
  organizationId: string, onReload: () => void, loading: boolean,
}) {
  const transitByTransfer = new Map<string, TransferInTransitLine[]>()
  for (const line of transit) transitByTransfer.set(line.transferId, [...(transitByTransfer.get(line.transferId) ?? []), line])
  const discrepancyTransfers = new Set(discrepancies.map(item => item.transferId))
  return <section aria-label="Transfer queue" className="space-y-4">
    <div className="flex flex-wrap items-center justify-between gap-3"><h3 className="text-xl font-semibold">Transfer queue</h3><Button variant="outline" disabled={loading} onClick={onReload}>{loading ? 'Refreshing…' : 'Refresh queue'}</Button></div>
    <ul className="grid gap-4">{items.map(item => {
      const age = Math.max(0, ...(transitByTransfer.get(item.id) ?? []).map(line => line.ageSeconds))
      const source = stores.get(item.sourceStoreId); const destination = stores.get(item.destinationStoreId)
      return <li key={item.id} className="min-w-0 rounded-2xl border border-border bg-card p-5">
        <div className="flex flex-wrap items-start justify-between gap-3"><div className="min-w-0"><p className="font-semibold">{source ? `${source.code} · ${source.name}` : `Source store ${item.sourceStoreId.slice(0, 8)}`} <span aria-hidden="true">→</span> {destination ? `${destination.code} · ${destination.name}` : `Destination store ${item.destinationStoreId.slice(0, 8)}`}</p><p className="mt-2 break-all text-xs text-muted-foreground">Transfer {item.id} · updated {new Date(item.updatedAt).toLocaleString()}</p></div><span className="rounded-full border border-border px-3 py-1 text-sm">{transferStatusLabel(item.status)}</span></div>
        <div className="mt-4 flex flex-wrap gap-x-5 gap-y-2 text-sm"><span>{item.lineCount} {item.lineCount === 1 ? 'line' : 'lines'}</span>{item.outstandingQuantity > 0 && <span>{item.outstandingQuantity} units outstanding</span>}{age > 0 && <span className={age > 86400 ? 'font-semibold text-amber-700 dark:text-amber-300' : ''}>In transit for {formatTransferAge(age)}</span>}{discrepancyTransfers.has(item.id) && <span className="font-semibold text-amber-700 dark:text-amber-300">Documented evidence to review</span>}{['IN_TRANSIT', 'PARTIALLY_RECEIVED', 'RECONCILIATION_REQUIRED'].includes(item.status) && <span>Custody history available</span>}</div>
        <AppLink href={`/organizations/${organizationId}/transfers/${item.id}`} className="mt-4 inline-flex min-h-11 items-center rounded-lg text-primary underline underline-offset-4">Open transfer and evidence</AppLink>
      </li>
    })}</ul>
  </section>
}

function CreateTransferPanel({ organizationId, stores, administrator, onCommand, busy }: {
  organizationId: string, stores: Store[], administrator: boolean,
  onCommand: (intent: TransferCommandIntent) => Promise<void>, busy: boolean,
}) {
  const scope = useTenantKey()
  const [sourceStoreId, setSourceStoreId] = useState('')
  const [destinationStoreId, setDestinationStoreId] = useState('')
  const [sourcePositionId, setSourcePositionId] = useState('')
  const [destinationLocationId, setDestinationLocationId] = useState('')
  const [quantity, setQuantity] = useState('')
  const [reason, setReason] = useState('')
  const [evidenceReference, setEvidenceReference] = useState('')
  const [showForm, setShowForm] = useState(false)
  const sourceStores = stores.filter(store => store.active && store.allowedActions.includes('REQUEST_TRANSFER') && store.allowedActions.includes('READ_INVENTORY'))
  const selectedSourceStoreId = sourceStores.some(store => store.id === sourceStoreId) ? sourceStoreId : sourceStores[0]?.id ?? ''
  const sourceStore = sourceStores.find(store => store.id === selectedSourceStoreId)
  const destinations = stores.filter(store => store.active && store.id !== selectedSourceStoreId && store.allowedActions.includes('READ_STORE'))
  const selectedDestinationStoreId = destinations.some(store => store.id === destinationStoreId) ? destinationStoreId : ''
  const destinationStore = destinations.find(store => store.id === selectedDestinationStoreId)
  const positions = useQuery({ queryKey: [...scope, 'transfer-create', 'positions', selectedSourceStoreId], enabled: Boolean(selectedSourceStoreId), queryFn: ({ signal }) => listInventoryAvailableByLot(organizationId, selectedSourceStoreId, { offset: 0, limit: 100 }, signal), staleTime: 0 })
  const sourceLocations = useQuery({ queryKey: [...scope, 'transfer-create', 'locations', selectedSourceStoreId], enabled: Boolean(selectedSourceStoreId), queryFn: ({ signal }) => listStorageLocations(organizationId, selectedSourceStoreId, false, '', 0, signal), staleTime: 0 })
  const destinationLocations = useQuery({ queryKey: [...scope, 'transfer-create', 'locations', selectedDestinationStoreId], enabled: Boolean(selectedDestinationStoreId), queryFn: ({ signal }) => listStorageLocations(organizationId, selectedDestinationStoreId, false, '', 0, signal), staleTime: 0 })
  const sourcePosition = positions.data?.items.find(position => position.positionId === sourcePositionId)
  const sourceLocation = sourceLocations.data?.items.find(location => location.id === sourcePosition?.locationId)
  const destinationLocation = destinationLocations.data?.items.find(location => location.id === destinationLocationId)
  const products = useQuery({ queryKey: [...scope, 'transfer-create', 'products'], enabled: Boolean(sourcePosition), queryFn: ({ signal }) => listProducts(organizationId, 'ACTIVE', '', 0, signal), staleTime: 60000 })
  const lots = useQuery({ queryKey: [...scope, 'transfer-create', 'lots', sourcePosition?.productId], enabled: Boolean(sourcePosition?.productId), queryFn: ({ signal }) => listLots(organizationId, sourcePosition!.productId, false, 0, signal), staleTime: 60000 })
  const route = useQuery({ queryKey: [...scope, 'transfer-create', 'lane', selectedSourceStoreId, selectedDestinationStoreId], enabled: administrator && Boolean(selectedSourceStoreId && selectedDestinationStoreId), queryFn: ({ signal }) => lookupLane(organizationId, selectedSourceStoreId, selectedDestinationStoreId, signal), staleTime: 0 })
  const available = sourcePosition?.available
  const count = Number(quantity)
  const routeAllowsRequest = !administrator || route.data?.status === 'CONFIGURED'
  const canSubmit = Boolean(sourceStore && destinationStore && sourcePosition && sourcePosition.initialization === 'INITIALIZED'
    && available !== null && available !== undefined && Number.isSafeInteger(count) && count > 0 && count <= available
    && destinationLocation?.usable && reason.trim() && evidenceReference.trim() && routeAllowsRequest && !busy)

  return <section className="space-y-4 rounded-2xl border border-border bg-card p-5">
    <div className="flex flex-wrap items-start justify-between gap-3"><div><h3 className="text-xl font-semibold">Create transfer request</h3><p className="mt-2 text-sm text-muted-foreground">A request records intent only. Stock is reserved after an authorized approver accepts the current source position.</p></div><Button variant="outline" onClick={() => { setShowForm(value => !value) }}>{showForm ? 'Close request form' : 'New transfer request'}</Button></div>
    {showForm && <form className="grid gap-4 sm:grid-cols-2" onSubmit={event => {
      event.preventDefault()
      if (!canSubmit || !sourcePosition || !destinationLocation) return
      const body = { requestId: crypto.randomUUID(), sourceStoreId: selectedSourceStoreId, destinationStoreId: selectedDestinationStoreId,
        lines: [{ sourcePositionId: sourcePosition.positionId, destinationLocationId, quantity: count, expectedSourceVersion: sourcePosition.positionVersion }],
        reason: reason.trim(), evidenceReference: evidenceReference.trim() }
      void onCommand({ operation: 'CREATE', transferId: null, body })
    }}>
      <Field label="Source store">{id => <select id={id} required value={selectedSourceStoreId} onChange={event => { const nextStoreId = event.target.value; setSourceStoreId(nextStoreId); setSourcePositionId(''); if (nextStoreId === destinationStoreId) { setDestinationStoreId(''); setDestinationLocationId('') } }} className={inputClass}><option value="">Choose a source</option>{sourceStores.map(store => <option key={store.id} value={store.id}>{store.code} · {store.name}</option>)}</select>}</Field>
      <Field label="Destination store">{id => <select id={id} required value={selectedDestinationStoreId} onChange={event => { setDestinationStoreId(event.target.value); setDestinationLocationId('') }} className={inputClass}><option value="">Choose a destination</option>{destinations.map(store => <option key={store.id} value={store.id}>{store.code} · {store.name}</option>)}</select>}</Field>
      <Field label="Source product and lot">{id => <select id={id} required value={sourcePositionId} onChange={event => { setSourcePositionId(event.target.value) }} disabled={positions.isPending || !selectedSourceStoreId} className={inputClass}><option value="">Choose an available stock position</option>{positions.data?.items.map(position => {
        const product = products.data?.items.find(item => item.id === position.productId)
        const lot = lots.data?.items.find(item => item.id === position.lotId)
        const location = sourceLocations.data?.items.find(item => item.id === position.locationId)
        const free = position.available === null ? 'count unknown' : `${position.available} ${position.unit} available`
        return <option key={position.positionId} value={position.positionId}>{product ? `${product.sku} · ${product.name}` : `Product ${position.productId.slice(0, 8)}`} · {lot?.lotCode ?? `Lot ${position.lotId.slice(0, 8)}`} · {location?.code ?? `Location ${position.locationId.slice(0, 8)}`} · {free}</option>
      })}</select>}</Field>
      <Field label="Destination receiving location">{id => <select id={id} required value={destinationLocationId} onChange={event => { setDestinationLocationId(event.target.value) }} disabled={!destinationStore || destinationLocations.isPending} className={inputClass}><option value="">Choose an active location</option>{destinationLocations.data?.items.map(location => <option key={location.id} value={location.id} disabled={!location.usable}>{location.code} · {location.name} · {location.zoneId}{location.usable ? '' : ' · unavailable'}</option>)}</select>}</Field>
      {sourcePosition && <Field label={`Quantity (${sourcePosition.unit})`}>{id => <input id={id} type="number" min="1" max={available ?? undefined} step="1" required value={quantity} onChange={event => { setQuantity(event.target.value) }} className={inputClass} />}</Field>}
      <div className="sm:col-span-2 rounded-xl border border-border p-4 text-sm"><h4 className="font-semibold">Route and storage context</h4><p className="mt-2">Source zone: {sourceLocation?.zoneId ?? (sourcePosition ? 'Loading source location' : 'Choose source stock')} · destination zone: {destinationLocation?.zoneId ?? 'Choose receiving location'}</p>
        {administrator && selectedSourceStoreId && selectedDestinationStoreId && (route.isPending ? <p role="status" className="mt-2">Checking configured transport route…</p> : route.isError ? <p role="alert" className="mt-2 text-destructive">{errorMessage(route.error)} <Button type="button" variant="outline" onClick={() => { void route.refetch() }}>Retry route check</Button></p> : <p className="mt-2">Transport route: {route.data?.status === 'CONFIGURED' ? 'Configured and available' : `Unavailable (${route.data?.status ?? 'not checked'})`}. The server rechecks lane, zones and stock when each command is saved.</p>)}
        {!administrator && <p className="mt-2">The server checks the current transport route, compatible zones and stock state when you submit.</p>}</div>
      {positions.isError && <p role="alert" className="sm:col-span-2 text-sm text-destructive">Source availability could not be checked: {errorMessage(positions.error)} <Button type="button" variant="outline" onClick={() => { void positions.refetch() }}>Retry availability</Button></p>}
      {(sourceLocations.isError || destinationLocations.isError) && <p role="alert" className="sm:col-span-2 text-sm text-destructive">Storage locations could not be checked. Confirm store access and retry the failed read.</p>}
      {sourcePosition?.initialization !== 'INITIALIZED' && sourcePosition && <p className="sm:col-span-2 text-sm text-amber-700 dark:text-amber-300">This position has no confirmed physical count and cannot be requested.</p>}
      {sourcePosition?.trust === 'UNVERIFIED' && sourcePosition && <p className="sm:col-span-2 text-sm text-amber-700 dark:text-amber-300">This stock position is unverified. Approval will recheck its lot safety and reservation eligibility.</p>}
      <Field label="Reason for transfer">{id => <textarea id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
      <Field label="Evidence reference">{id => <input id={id} required maxLength={256} value={evidenceReference} onChange={event => { setEvidenceReference(event.target.value) }} className={inputClass} placeholder="Document or review reference" />}</Field>
      <Button type="submit" className="sm:col-span-2" disabled={!canSubmit}>{busy ? 'Saving request…' : 'Create transfer request'}</Button>
    </form>}
  </section>
}

function TransferDetailView({ organizationId, transfer, stores, queueStores, discrepancies, onCommand, busy, onRefresh }: {
  organizationId: string, transfer: Transfer, stores: Store[], queueStores: Store[],
  discrepancies: TransferDiscrepancyRecord[], onCommand: (intent: TransferCommandIntent) => Promise<void>, busy: boolean,
  onRefresh: () => Promise<void>,
}) {
  const scope = useTenantKey()
  const storeMap = new Map(stores.map(store => [store.id, store]))
  const source = storeMap.get(transfer.sourceStoreId)
  const destination = storeMap.get(transfer.destinationStoreId)
  const sourceActions = source?.allowedActions ?? []
  const destinationActions = destination?.allowedActions ?? []
  const custody = useQuery({ queryKey: [...scope, 'transfers', 'custody', transfer.id], queryFn: ({ signal }) => listTransferCustody(organizationId, transfer.id, signal), refetchOnWindowFocus: true, staleTime: 0 })
  const partyStores = queueStores.filter(store => store.id === transfer.sourceStoreId || store.id === transfer.destinationStoreId)
  const transitQuery = useQuery({ queryKey: [...scope, 'transfers', 'detail-transit', transfer.id], enabled: partyStores.length > 0,
    queryFn: async ({ signal }) => (await Promise.all(partyStores.map(store => listTransfersInTransit(organizationId, store.id, signal)))).flatMap(page => page.items).filter(line => line.transferId === transfer.id), staleTime: 0 })
  const discrepancyQuery = useQuery({ queryKey: [...scope, 'transfers', 'detail-discrepancies', transfer.id], enabled: partyStores.length > 0,
    queryFn: async ({ signal }) => (await Promise.all(partyStores.map(store => listTransferDiscrepancies(organizationId, store.id, signal)))).flatMap(page => page.items).filter(item => item.transferId === transfer.id), staleTime: 0 })
  const canReadDestinationStock = destinationActions.includes('READ_INVENTORY')
  const receivingPositions = useQuery({ queryKey: [...scope, 'transfers', 'destination-positions', transfer.id, transfer.version], enabled: canReadDestinationStock && transfer.lines.some(line => outstanding(line) > 0),
    queryFn: async ({ signal }) => {
      const records = await Promise.all(transfer.lines.map(async line => {
        const page = await listInventoryPositions(organizationId, transfer.destinationStoreId,
          { productId: line.productId, lotId: line.lotId, locationId: line.destinationLocationId, offset: 0, limit: 100 }, signal)
        return [line.id, page.items.find(position => position.productId === line.productId && position.lotId === line.lotId && position.locationId === line.destinationLocationId) ?? null] as const
      }))
      return new Map(records)
    }, staleTime: 0 })
  const ageSeconds = Math.max(0, ...(transitQuery.data ?? []).map(line => line.ageSeconds))
  const firstDispatchAt = [...(transitQuery.data ?? [])].sort((left, right) => left.dispatchedAt.localeCompare(right.dispatchedAt))[0]?.dispatchedAt
  const nowVersion = transfer.version

  function command(operation: Extract<TransferCommandIntent['operation'], 'APPROVE' | 'REJECT' | 'CANCEL' | 'START_PICK' | 'CLOSE'>, reason: string, evidenceReference: string) {
    const body = { requestId: crypto.randomUUID(), expectedVersion: nowVersion, reason: reason.trim(), evidenceReference: evidenceReference.trim() }
    void onCommand({ operation, transferId: transfer.id, body })
  }

  return <section className="space-y-7">
    <div className="flex flex-wrap items-start justify-between gap-3"><div><AppLink href={`/organizations/${organizationId}/transfers`} className="text-sm text-primary underline">Back to transfer queue</AppLink><h3 className="mt-3 break-all text-2xl font-semibold">Transfer {transfer.id}</h3><p className="mt-2">{source ? `${source.code} · ${source.name}` : `Source ${transfer.sourceStoreId}`} <span aria-hidden="true">→</span> {destination ? `${destination.code} · ${destination.name}` : `Destination ${transfer.destinationStoreId}`}</p><p className="mt-2 text-sm text-muted-foreground">{transferStatusLabel(transfer.status)} · version {transfer.version} · outstanding custody {transfer.outstandingQuantity} units</p></div><Button variant="outline" disabled={busy} onClick={() => { void onRefresh() }}>Refresh saved record</Button></div>
    {transitQuery.isError && <p role="status" className="rounded-xl border border-border p-4 text-sm">Transit age is temporarily unavailable. <Button variant="outline" onClick={() => { void transitQuery.refetch() }}>Retry transit read</Button></p>}
    {ageSeconds > 0 && firstDispatchAt && <p className="rounded-xl border border-border p-4 text-sm">Outstanding goods have been in custody for {formatTransferAge(ageSeconds)}. First dispatch: {new Date(firstDispatchAt).toLocaleString()}.</p>}
    <section aria-labelledby="transfer-lines-heading" className="space-y-3"><h4 id="transfer-lines-heading" className="text-lg font-semibold">Lines and stock custody</h4><ul className="grid gap-3">{transfer.lines.map(line => <li key={line.id} className="space-y-3 rounded-xl border border-border p-4"><div className="flex flex-wrap items-start justify-between gap-3"><div><p className="font-semibold">Line {line.lineNumber} · {line.sku} · lot {line.lotCode}</p><p className="mt-1 break-all text-sm text-muted-foreground">{line.requestedQuantity} {line.unit} requested · source position {line.sourcePositionId.slice(0, 8)} · destination location {line.destinationLocationId.slice(0, 8)}</p></div><span className="text-sm">{line.picked ? 'Physical pick recorded' : transfer.status === 'PICKING' ? 'Awaiting full-line pick' : 'Not picked'}</span></div><dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-5"><Quantity label="Dispatched" value={line.quantities.dispatched ?? 0} unit={line.unit} /><Quantity label="Received" value={line.quantities.physicallyReceived ?? 0} unit={line.unit} /><Quantity label="Confirmed loss" value={line.quantities.confirmedLoss ?? 0} unit={line.unit} /><Quantity label="Returned" value={line.quantities.physicallyReturned ?? 0} unit={line.unit} /><Quantity label="Outstanding" value={line.quantities.outstanding ?? 0} unit={line.unit} /></dl></li>)}</ul></section>

    {transfer.status === 'REQUESTED' && sourceActions.includes('APPROVE_TRANSFER') && <DecisionAction title="Review request" primaryLabel="Approve and reserve stock" onSubmit={(reason, evidence) => { command('APPROVE', reason, evidence) }} busy={busy} />}
    {transfer.status === 'REQUESTED' && sourceActions.includes('APPROVE_TRANSFER') && <DecisionAction title="Reject request" primaryLabel="Reject transfer" onSubmit={(reason, evidence) => { command('REJECT', reason, evidence) }} busy={busy} />}
    {transfer.status === 'APPROVED' && sourceActions.includes('PICK_TRANSFER') && <DecisionAction title="Begin physical picking" primaryLabel="Start picking" onSubmit={(reason, evidence) => { command('START_PICK', reason, evidence) }} busy={busy} />}
    {transfer.status === 'PICKING' && sourceActions.includes('PICK_TRANSFER') && transfer.lines.filter(line => !line.picked).map(line => <PickAction key={line.id} transfer={transfer} line={line} busy={busy} onCommand={onCommand} />)}
    {transfer.status === 'PICKED' && sourceActions.includes('DISPATCH_TRANSFER') && <DispatchAction transfer={transfer} busy={busy} onCommand={onCommand} />}
    {['IN_TRANSIT', 'PARTIALLY_RECEIVED', 'RECONCILIATION_REQUIRED'].includes(transfer.status) && destinationActions.includes('RECEIVE_STOCK') && (canReadDestinationStock
      ? transfer.lines.filter(line => outstanding(line) > 0).map(line => <ReceiveAction key={line.id} transfer={transfer} line={line} position={receivingPositions.data?.get(line.id)} busy={busy} onCommand={onCommand} />)
      : <StateMessage state="forbidden" title="Destination inventory version unavailable" description="Receiving requires current inventory read access at the destination so stale stock cannot be overwritten." />)}
    {receivingPositions.isError && canReadDestinationStock && <p role="alert" className="text-sm text-destructive">Destination stock version could not be read. Refresh inventory access before receiving: {errorMessage(receivingPositions.error)} <Button variant="outline" onClick={() => { void receivingPositions.refetch() }}>Retry stock read</Button></p>}
    {['IN_TRANSIT', 'PARTIALLY_RECEIVED', 'RECONCILIATION_REQUIRED'].includes(transfer.status) && destinationActions.includes('MANAGE_TRANSFER_DISCREPANCIES') && transfer.lines.some(line => outstanding(line) > 0) && <DamageAction transfer={transfer} busy={busy} onCommand={onCommand} />}
    {transfer.status === 'RECONCILIATION_REQUIRED' && transfer.outstandingQuantity === 0 && (sourceActions.includes('MANAGE_TRANSFER_DISCREPANCIES') || destinationActions.includes('MANAGE_TRANSFER_DISCREPANCIES')) && <DecisionAction title="Close documented discrepancy" primaryLabel="Close discrepancy" onSubmit={(reason, evidence) => { command('CLOSE', reason, evidence) }} busy={busy} />}
    {['IN_TRANSIT', 'PARTIALLY_RECEIVED', 'RECONCILIATION_REQUIRED'].includes(transfer.status) && destinationActions.includes('MANAGE_TRANSFER_DISCREPANCIES') && <OpenDiscrepancyAction transfer={transfer} busy={busy} onCommand={onCommand} />}
    {['REQUESTED', 'APPROVED', 'PICKING', 'PICKED'].includes(transfer.status) && sourceActions.includes('CANCEL_TRANSFER') && <DecisionAction title="Cancel transfer before dispatch" primaryLabel="Cancel transfer" onSubmit={(reason, evidence) => { command('CANCEL', reason, evidence) }} busy={busy} />}

    <section aria-labelledby="custody-heading" className="space-y-3"><div className="flex flex-wrap items-center justify-between gap-3"><div><h4 id="custody-heading" className="text-lg font-semibold">Custody and physical evidence</h4><p className="mt-1 text-sm text-muted-foreground">Every entry links the responsible actor, quantity, remaining custody and any stock ledger movement.</p></div><Button variant="outline" disabled={custody.isFetching} onClick={() => { void custody.refetch() }}>Refresh timeline</Button></div>
      {custody.isPending ? <p role="status">Loading immutable custody entries…</p> : custody.isError ? <p role="alert" className="text-sm text-destructive">Custody history unavailable: {errorMessage(custody.error)} <Button variant="outline" onClick={() => { void custody.refetch() }}>Retry timeline read</Button></p> : custody.data.items.length === 0 ? <p className="rounded-xl border border-border p-4 text-sm">No physical custody movement is recorded. Approval and picking do not mean goods have been dispatched.</p> : <ol className="space-y-3">{custody.data.items.map((movement: TransferCustodyMovement) => <li key={movement.id} className="rounded-xl border border-border p-4"><div className="flex flex-wrap justify-between gap-2"><p className="font-semibold">{movement.type} · {movement.quantity} units</p><time className="text-sm text-muted-foreground" dateTime={movement.recordedAt}>{new Date(movement.recordedAt).toLocaleString()}</time></div><p className="mt-2 text-sm">Actor {movement.actorId} · outstanding {movement.outstandingBefore} → {movement.outstandingAfter} · line {movement.lineId.slice(0, 8)}</p><p className="mt-2 break-all text-sm">Reason: {movement.reason} · evidence: {movement.evidenceReference}</p>{movement.stockMovementId && <p className="mt-2 break-all text-xs text-muted-foreground">Stock movement {movement.stockMovementId} · position {movement.stockPositionId} · store {movement.stockStoreId}</p>}</li>)}</ol>}
    </section>
    <section aria-labelledby="discrepancy-heading" className="space-y-3"><h4 id="discrepancy-heading" className="text-lg font-semibold">Discrepancy evidence</h4>{discrepancyQuery.isPending ? <p role="status">Checking discrepancy records…</p> : discrepancyQuery.isError ? <p role="alert" className="text-sm text-destructive">Discrepancy records unavailable: {errorMessage(discrepancyQuery.error)} <Button variant="outline" onClick={() => { void discrepancyQuery.refetch() }}>Retry discrepancy read</Button></p> : (discrepancyQuery.data ?? discrepancies.filter(item => item.transferId === transfer.id)).length === 0 ? <p className="text-sm text-muted-foreground">No discrepancy has been recorded.</p> : <ul className="space-y-3">{(discrepancyQuery.data ?? discrepancies.filter(item => item.transferId === transfer.id)).map(item => <li key={item.id} className="rounded-xl border border-border p-4"><p className="font-semibold">{item.type} · {new Date(item.recordedAt).toLocaleString()}</p><p className="mt-2 text-sm">Expected: {item.expectedState} · observed: {item.observedState}</p><p className="mt-1 text-sm">{item.reason} · evidence: {item.evidenceReference}</p></li>)}</ul>}</section>
  </section>
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

function ReceiveAction({ transfer, line, position, busy, onCommand }: { transfer: Transfer, line: Transfer['lines'][number], position?: StockPosition | null, busy: boolean, onCommand: (intent: TransferCommandIntent) => Promise<void> }) {
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
  const [lineId, setLineId] = useState(transfer.lines.find(line => outstanding(line) > 0)?.id ?? '')
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
    <Field label="Transfer line">{id => <select id={id} value={lineId} onChange={event => { setLineId(event.target.value); setQuantity(String(outstanding(transfer.lines.find(item => item.id === event.target.value) ?? transfer.lines[0]!))) }} className={inputClass}>{transfer.lines.filter(item => outstanding(item) > 0).map(item => <option key={item.id} value={item.id}>Line {item.lineNumber} · {item.sku} · lot {item.lotCode} · {outstanding(item)} outstanding</option>)}</select>}</Field>
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
