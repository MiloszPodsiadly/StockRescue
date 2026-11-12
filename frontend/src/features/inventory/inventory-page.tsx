import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from '@tanstack/react-router'
import { AppLink } from '@/components/app-link'
import { Button } from '@/components/ui/button'
import { Field, inputClass } from '@/components/form-field'
import { StateMessage } from '@/components/state-message'
import { ApiError, errorMessage } from '@/api/client'
import { listProducts } from '@/api/products'
import type { Product } from '@/api/products'
import { listLots } from '@/api/lots'
import { listStorageLocations } from '@/api/storage-locations'
import type { StorageLocation } from '@/api/storage-locations'
import { getStore } from '@/api/stores'
import type { Store } from '@/api/stores'
import {
  getInventoryCommandOutcome, getInventorySnapshot, listInventoryAllocations, listInventoryAvailableByLot,
  listInventoryFindings, listInventoryMovements, listInventoryPositions,
  listInventoryReconciliations, receiveInventoryGoods, reconcileInventory, repairInventoryProjection,
  releaseInventoryAllocation, runInventoryCommand, writeOffInventoryStock,
} from '@/api/inventory'
import type {
  AvailableByLot, InventoryAllocation, InventoryCommand, InventoryOperation,
  InventoryReceipt, ProjectionRepair, Reconciliation, ReconciliationFinding, StockCommand, StockPosition,
} from '@/api/inventory'
import { OrganizationPage } from '../organizations/organization-page'
import { useTenantKey, useTenantRequests } from '../organizations/tenant-requests'

type OperationChoice = 'reserve' | 'release' | 'adjust' | 'writeOff' | 'quarantine' | 'hold' | 'releaseHold' | 'sale'
type CommandDraft = { choice: OperationChoice, requestId: string, expectedVersion: number, quantity: number, reason: string, evidenceReference: string, allocationId?: string }
const refresh = { refetchOnWindowFocus: true, staleTime: 0 } as const

export function InventoryPage() {
  const { organizationId, storeId } = useParams({ strict: false })
  return <OrganizationPage organizationId={organizationId}>{organizationId && storeId && <InventoryWorkspace key={storeId} organizationId={organizationId} storeId={storeId} />}</OrganizationPage>
}

function InventoryWorkspace({ organizationId, storeId }: { organizationId: string, storeId: string }) {
  const scope = useTenantKey()
  const client = useQueryClient()
  const [productId, setProductId] = useState('')
  const [lotId, setLotId] = useState('')
  const [locationId, setLocationId] = useState('')
  const [offset, setOffset] = useState(0)
  const [selectedPositionId, setSelectedPositionId] = useState('')
  const store = useQuery({ queryKey: [...scope, 'store', storeId], queryFn: ({ signal }) => getStore(organizationId, storeId, signal), ...refresh })
  const canRead = Boolean(store.data?.allowedActions.includes('READ_INVENTORY'))
  const filters = { productId: productId || undefined, lotId: lotId || undefined, locationId: locationId || undefined, offset, limit: 25 }
  const inventoryKey = [...scope, 'inventory', storeId] as const
  const positions = useQuery({ queryKey: [...inventoryKey, 'positions', filters], enabled: canRead, queryFn: ({ signal }) => listInventoryPositions(organizationId, storeId, filters, signal), ...refresh })
  const availability = useQuery({ queryKey: [...inventoryKey, 'availability', filters], enabled: canRead, queryFn: ({ signal }) => listInventoryAvailableByLot(organizationId, storeId, filters, signal), ...refresh })
  const products = useQuery({ queryKey: [...scope, 'catalog', 'products', 'inventory'], enabled: canRead, queryFn: ({ signal }) => listProducts(organizationId, 'ACTIVE', '', 0, signal), ...refresh })
  const lots = useQuery({ queryKey: [...scope, 'catalog', 'lots', 'inventory', productId], enabled: canRead, queryFn: ({ signal }) => listLots(organizationId, productId || undefined, false, 0, signal), ...refresh })
  const locations = useQuery({ queryKey: [...inventoryKey, 'locations'], enabled: canRead, queryFn: ({ signal }) => listStorageLocations(organizationId, storeId, false, '', 0, signal), ...refresh })
  const selectedSnapshot = useQuery({ queryKey: [...inventoryKey, 'snapshot', selectedPositionId], enabled: canRead && Boolean(selectedPositionId), queryFn: ({ signal }) => getInventorySnapshot(organizationId, storeId, selectedPositionId, signal), ...refresh })
  const selected = selectedPositionId ? selectedSnapshot.data : undefined
  const commandRefresh = async () => {
    await client.invalidateQueries({ queryKey: inventoryKey })
  }

  if (store.isPending) return <StateMessage state="loading" title="Checking store access" description="Resolving your current store permissions." />
  if (store.isError) return <Unavailable title="Store unavailable" error={store.error} retry={() => { void store.refetch() }} />
  if (!canRead) return <StateMessage state="forbidden" title="Inventory access unavailable" description="Your current store assignment does not include inventory reading. Ask a store administrator to review your access." />

  return <section className="space-y-7">
    <header className="space-y-2"><AppLink href={`/organizations/${organizationId}/stores/${storeId}`} className="text-sm text-primary underline">{store.data.name}</AppLink><h2 className="text-3xl font-semibold">Inventory</h2><p className="max-w-3xl text-sm text-muted-foreground">Stock is confirmed from the saved ledger. Unknown counts stay unknown until a physical opening count is recorded.</p></header>

    <section aria-label="Inventory filters" className="grid gap-4 rounded-2xl border border-border bg-card p-5 sm:grid-cols-2 xl:grid-cols-3">
      <Field label="Product">{id => <select id={id} value={productId} onChange={event => { setProductId(event.target.value); setLotId(''); setOffset(0); setSelectedPositionId('') }} className={inputClass}><option value="">All products</option>{products.data?.items.map(product => <option key={product.id} value={product.id}>{product.sku} · {product.name}</option>)}</select>}</Field>
      <Field label="Lot">{id => <select id={id} value={lotId} onChange={event => { setLotId(event.target.value); setOffset(0); setSelectedPositionId('') }} className={inputClass}><option value="">All lots</option>{lots.data?.items.map(lot => <option key={lot.id} value={lot.id}>{lot.lotCode}</option>)}</select>}</Field>
      <Field label="Storage location">{id => <select id={id} value={locationId} onChange={event => { setLocationId(event.target.value); setOffset(0); setSelectedPositionId('') }} className={inputClass}><option value="">All locations</option>{locations.data?.items.map(location => <option key={location.id} value={location.id}>{location.code} · {location.name}</option>)}</select>}</Field>
    </section>
    {(products.isError || lots.isError || locations.isError) && <p role="status" className="rounded-xl border border-border p-4 text-sm text-muted-foreground">Some catalog labels are unavailable. Inventory remains scoped and can still be reviewed by its saved record references.</p>}
    <ReceiptForm organizationId={organizationId} storeId={storeId} store={store.data} products={products.data?.items ?? []} locations={locations.data?.items ?? []} scope={inventoryKey} onCommitted={commandRefresh} />
    {positions.isPending ? <StateMessage state="loading" title="Loading inventory" description="Reading the latest saved stock positions." /> : positions.isError ? <Unavailable title="Inventory could not be loaded" error={positions.error} retry={() => { void positions.refetch() }} /> : positions.data.items.length === 0 ? <StateMessage state="empty" title="No stock positions in this view" description="There are no recorded stock rows for the selected product, lot and location filters." /> : <>
      <PositionTable items={positions.data.items} productNames={new Map((products.data?.items ?? []).map(item => [item.id, `${item.sku} · ${item.name}`]))} lotNames={new Map((lots.data?.items ?? []).map(item => [item.id, item.lotCode]))} locationNames={new Map((locations.data?.items ?? []).map(item => [item.id, `${item.code} · ${item.name}`]))} selectedId={selectedPositionId} onSelect={id => setSelectedPositionId(id)} />
      {availability.isError ? <p role="alert" className="text-sm">Availability by lot could not be refreshed: {errorMessage(availability.error)}</p> : availability.data && <AvailabilityTable items={availability.data.items} lotNames={new Map((lots.data?.items ?? []).map(item => [item.id, item.lotCode]))} locationNames={new Map((locations.data?.items ?? []).map(item => [item.id, `${item.code} · ${item.name}`]))} observedAt={availability.data.observedAt} />}
      <div className="flex flex-wrap items-center gap-3"><Button variant="outline" disabled={offset === 0} onClick={() => { setOffset(value => Math.max(0, value - 25)); setSelectedPositionId('') }}>Previous positions</Button><span className="text-sm text-muted-foreground">Positions {offset + 1}–{offset + positions.data.items.length}</span><Button variant="outline" disabled={!positions.data.hasMore} onClick={() => { setOffset(value => value + 25); setSelectedPositionId('') }}>Next positions</Button></div>
    </>}

    {selectedPositionId && selectedSnapshot.isPending && <StateMessage state="loading" title="Refreshing selected position" description="Reading the current saved version before showing any stock operation." />}
    {selectedSnapshot.isError && <Unavailable title="Position could not be refreshed" error={selectedSnapshot.error} retry={() => { void selectedSnapshot.refetch() }} />}
    {selected && <SelectedPosition key={`${selected.id}:${selected.version}`} organizationId={organizationId} storeId={storeId} store={store.data} position={selected} scope={inventoryKey} onCommitted={commandRefresh} />}
    <ReconciliationPanel organizationId={organizationId} storeId={storeId} canReconcile={store.data.allowedActions.includes('RECONCILE_INVENTORY')} canRemediate={store.data.allowedActions.includes('REMEDIATE_INVENTORY')} scope={inventoryKey} onReviewPosition={setSelectedPositionId} onCommitted={commandRefresh} />
  </section>
}

export function PositionTable({ items, productNames, lotNames, locationNames, selectedId, onSelect }: { items: StockPosition[], productNames: Map<string, string>, lotNames: Map<string, string>, locationNames: Map<string, string>, selectedId: string, onSelect: (id: string) => void }) {
  return <div className="overflow-x-auto rounded-2xl border border-border"><table className="w-full min-w-[64rem] text-left text-sm"><caption className="sr-only">Stock by product, lot and storage location</caption><thead className="bg-muted/50"><tr>{['Product', 'Lot', 'Location', 'On hand', 'Reserved', 'Quarantined', 'Other blocked', 'Available', 'Version', 'Observed'].map(label => <th key={label} scope="col" className="px-4 py-3 font-semibold">{label}</th>)}<th scope="col" className="px-4 py-3 font-semibold">Details</th></tr></thead><tbody>{items.map(position => {
    const unknown = position.initialization === 'NOT_INITIALIZED' || position.onHand === null
    return <tr key={position.id} className={`border-t border-border ${selectedId === position.id ? 'bg-accent/50' : ''}`}>
      <td className="px-4 py-3">{productNames.get(position.productId) ?? position.productId}</td><td className="px-4 py-3">{lotNames.get(position.lotId) ?? position.lotId}</td><td className="px-4 py-3">{locationNames.get(position.locationId) ?? position.locationId}</td>
      {[position.onHand, position.reserved, position.quarantined, position.otherBlocked, position.available].map((quantity, index) => <td key={index} className="px-4 py-3">{unknown || quantity === null ? <span className="font-medium text-amber-800">Unknown</span> : `${quantity} ${position.unit}`}</td>)}
      <td className="px-4 py-3">v{position.version}</td><td className="px-4 py-3"><time dateTime={position.observedAt}>{new Date(position.observedAt).toLocaleString()}</time></td><td className="px-4 py-3"><Button variant="outline" aria-pressed={selectedId === position.id} onClick={() => onSelect(position.id)}>{selectedId === position.id ? 'Selected' : 'Review position'}</Button></td>
    </tr>
  })}</tbody></table></div>
}

function AvailabilityTable({ items, lotNames, locationNames, observedAt }: { items: AvailableByLot[], lotNames: Map<string, string>, locationNames: Map<string, string>, observedAt: string }) {
  return <section aria-label="Available stock by lot" className="space-y-3"><div><h3 className="text-lg font-semibold">Availability by lot and position</h3><p className="text-sm text-muted-foreground">This is a separate point-in-time read, observed <time dateTime={observedAt}>{new Date(observedAt).toLocaleString()}</time>.</p></div><ul className="grid gap-3 md:grid-cols-2">{items.map(item => <li key={item.positionId} className="rounded-xl border border-border p-4 text-sm"><p className="font-medium">Lot {lotNames.get(item.lotId) ?? item.lotId}</p><p>{locationNames.get(item.locationId) ?? item.locationId}</p><p className="mt-2">Available: {item.initialization === 'NOT_INITIALIZED' || item.available === null ? 'Unknown' : `${item.available} ${item.unit}`}</p><p className="text-muted-foreground">{item.trust} · position version {item.positionVersion}</p></li>)}</ul></section>
}

function SelectedPosition({ organizationId, storeId, store, position, scope, onCommitted }: { organizationId: string, storeId: string, store: Store, position: StockPosition, scope: readonly unknown[], onCommitted: () => Promise<void> }) {
  const { id, initialization, trust, onHand, reserved, quarantined, otherBlocked, available, unit, version } = position
  const history = useQuery({ queryKey: [...scope, 'movements', id], queryFn: ({ signal }) => listInventoryMovements(organizationId, storeId, id, 0, signal), ...refresh })
  const allocations = useQuery({ queryKey: [...scope, 'allocations', id], queryFn: ({ signal }) => listInventoryAllocations(organizationId, storeId, id, signal), ...refresh })
  return <section className="space-y-6 rounded-2xl border border-border p-5" aria-label="Selected inventory position">
    <header className="space-y-2"><h3 className="text-2xl font-semibold">Position details</h3><p className="break-all text-xs text-muted-foreground">Position {id} · version {version} · observed {new Date(position.observedAt).toLocaleString()}</p></header>
    {initialization === 'NOT_INITIALIZED' ? <StateMessage state="unavailable" title="Physical quantity is unknown" description="No verified opening count has been recorded. This is not a zero balance; receive or count physical stock before relying on availability." /> : <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">{[['On hand', onHand], ['Reserved', reserved], ['Quarantined', quarantined], ['Other blocked', otherBlocked], ['Available', available]].map(([label, value]) => <div key={String(label)} className="rounded-xl bg-muted/40 p-4"><p className="text-sm text-muted-foreground">{label}</p><p className="mt-1 text-xl font-semibold">{value === null ? 'Unknown' : `${value} ${unit}`}</p></div>)}</div>}
    {trust === 'FROZEN' && <p role="status" className="rounded-xl border border-amber-500/40 bg-amber-50 p-4 text-sm text-amber-950">This position is frozen for review. New operations may be rejected until its evidence is reconciled.</p>}
    <InventoryOperations organizationId={organizationId} storeId={storeId} store={store} position={position} allocations={allocations.data?.items ?? []} onCommitted={onCommitted} />
    <div className="grid gap-6 xl:grid-cols-2">
      <section aria-label="Reservation and hold status" className="space-y-3"><h4 className="text-lg font-semibold">Reservations and holds</h4>{allocations.isPending ? <p role="status">Loading allocation history…</p> : allocations.isError ? <p role="alert">{errorMessage(allocations.error)}</p> : allocations.data.items.length === 0 ? <p className="text-sm text-muted-foreground">No active or historical allocations are recorded for this position.</p> : <ul className="space-y-2">{allocations.data.items.map(item => <li key={item.id} className="rounded-xl border border-border p-3 text-sm">{item.bucket} · {item.remaining} {unit} · {item.state} · version {item.version}{item.ownedByCurrentUser ? ' · created by you' : ''}</li>)}</ul>}</section>
      <section aria-label="Movement history" className="space-y-3"><h4 className="text-lg font-semibold">Movement history</h4>{history.isPending ? <p role="status">Loading committed movements…</p> : history.isError ? <p role="alert">{errorMessage(history.error)}</p> : history.data.items.length === 0 ? <p className="text-sm text-muted-foreground">No movement has been recorded yet.</p> : <ol className="space-y-2">{history.data.items.map(item => <li key={item.id} className="rounded-xl border border-border p-3 text-sm"><div className="flex flex-wrap justify-between gap-2"><strong>{item.operation.replaceAll('_', ' ')}</strong><time dateTime={item.recordedAt}>{new Date(item.recordedAt).toLocaleString()}</time></div><p className="mt-1">On hand {signed(item.deltaOnHand)} · reserved {signed(item.deltaReserved)} · quarantined {signed(item.deltaQuarantined)} · other blocked {signed(item.deltaOtherBlocked)}</p><p className="mt-1 text-muted-foreground">{item.reason} · Evidence: {item.evidenceReference} · version {item.positionVersion}</p></li>)}</ol>}</section>
    </div>
  </section>
}

function ReceiptForm({ organizationId, storeId, store, products, locations, scope, onCommitted }: { organizationId: string, storeId: string, store: Store, products: Product[], locations: StorageLocation[], scope: readonly unknown[], onCommitted: () => Promise<void> }) {
  const tenantKey = useTenantKey()
  const client = useQueryClient()
  const tenantSignal = useTenantRequests()
  const [productId, setProductId] = useState('')
  const [lotId, setLotId] = useState('')
  const [locationId, setLocationId] = useState('')
  const [quantity, setQuantity] = useState('')
  const [supplierKey, setSupplierKey] = useState('')
  const [documentType, setDocumentType] = useState('DELIVERY_NOTE')
  const [documentReference, setDocumentReference] = useState('')
  const [documentLineReference, setDocumentLineReference] = useState('1')
  const [physicallyReceivedAt, setPhysicallyReceivedAt] = useState(() => new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16))
  const [reason, setReason] = useState('')
  const [evidenceReference, setEvidenceReference] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [draft, setDraft] = useState<InventoryReceipt | null>(null)
  const [recovered, setRecovered] = useState<InventoryCommand | null>(null)
  const [recoveryError, setRecoveryError] = useState('')
  const lots = useQuery({ queryKey: [...scope, 'catalog', 'inventory-receipt-lots', productId], enabled: Boolean(productId), queryFn: ({ signal }) => listLots(organizationId, productId, false, 0, signal), ...refresh })
  const currentPosition = useQuery({ queryKey: [...scope, 'inventory', storeId, 'receipt-position', productId, lotId, locationId], enabled: Boolean(productId && lotId && locationId), queryFn: ({ signal }) => listInventoryPositions(organizationId, storeId, { productId, lotId, locationId, limit: 2 }, signal), ...refresh })
  const mutation = useMutation({ mutationKey: [...tenantKey, 'inventory', storeId, 'receipt'], retry: false, gcTime: 0, mutationFn: (body: InventoryReceipt) => receiveInventoryGoods(organizationId, storeId, body, tenantSignal()), onSuccess: async () => { setDraft(null); setConfirmed(false); setRecovered(null); await onCommitted() } })
  const allowed = store.allowedActions.includes('RECEIVE_STOCK')
  const definitelyRejected = mutation.error instanceof ApiError && mutation.error.status >= 400 && mutation.error.status < 500
  const matchingPosition = currentPosition.data?.items[0]
  const canConfirmVersion = Boolean(currentPosition.data && !currentPosition.isError && currentPosition.data.items.length <= 1)
  const eligibleToSubmit = allowed && canConfirmVersion && confirmed && !mutation.isPending && !draft && productId && lotId && locationId && Number.isSafeInteger(Number(quantity)) && Number(quantity) > 0 && supplierKey.trim() && documentReference.trim() && documentLineReference.trim() && reason.trim() && evidenceReference.trim()
  function prepareReceipt() {
    if (!eligibleToSubmit) return
    const receivedAt = new Date(physicallyReceivedAt)
    if (Number.isNaN(receivedAt.getTime())) return
    const payload: InventoryReceipt = { requestId: crypto.randomUUID(), expectedVersion: matchingPosition?.version ?? 0, locationId, productId, lotId, quantity: Number(quantity), supplierKey: supplierKey.trim(), documentType: documentType.trim(), documentReference: documentReference.trim(), documentLineReference: documentLineReference.trim(), physicallyReceivedAt: receivedAt.toISOString(), reason: reason.trim(), evidenceReference: evidenceReference.trim() }
    setDraft(payload); setRecoveryError(''); setRecovered(null); mutation.mutate(payload)
  }
  async function recoverReceipt() {
    if (!draft) return
    try {
      const result = await getInventoryCommandOutcome(organizationId, storeId, 'RECEIVE', draft.requestId, tenantSignal())
      mutation.reset(); setDraft(null); setConfirmed(false); setRecovered(result)
      await client.invalidateQueries({ queryKey: [...tenantKey, 'inventory', storeId] })
    } catch (error) {
      setRecoveryError(error instanceof ApiError && error.status === 404 ? 'No committed result is visible for this request key yet. Keep these receipt details and retry only with the same request key.' : errorMessage(error))
    }
  }
  function reviseRejectedReceipt() {
    setDraft(null); setConfirmed(false); setRecovered(null); setRecoveryError('The server rejected the previous receipt. Review its reason, correct the document or version, then submit a new request.')
    mutation.reset(); void onCommitted()
  }
  return <section className="space-y-4 rounded-xl border border-border p-5" aria-label="Physical goods receipt">
    <div><h4 className="text-lg font-semibold">Record physical receipt</h4><p className="mt-1 text-sm text-muted-foreground">This books units that are already physically present. It does not approve future deliveries.</p></div>
    {!allowed ? <p className="text-sm text-muted-foreground">Your current assignment cannot receive stock into this store.</p> : <form onSubmit={event => { event.preventDefault(); prepareReceipt() }} className="grid gap-4 sm:grid-cols-2">
      <Field label="Product">{id => <select id={id} required value={productId} disabled={Boolean(draft) || mutation.isPending} onChange={event => { setProductId(event.target.value); setLotId('') }} className={inputClass}><option value="">Choose a product</option>{products.map(product => <option key={product.id} value={product.id}>{product.sku} · {product.name}</option>)}</select>}</Field>
      <Field label="Lot">{id => <select id={id} required value={lotId} disabled={!productId || Boolean(draft) || mutation.isPending} onChange={event => setLotId(event.target.value)} className={inputClass}><option value="">Choose a lot</option>{lots.data?.items.map(lot => <option key={lot.id} value={lot.id}>{lot.lotCode}</option>)}</select>}</Field>
      <Field label="Storage location">{id => <select id={id} required value={locationId} disabled={Boolean(draft) || mutation.isPending} onChange={event => setLocationId(event.target.value)} className={inputClass}><option value="">Choose a location</option>{locations.map(location => <option key={location.id} value={location.id}>{location.code} · {location.name}</option>)}</select>}</Field>
      <Field label="Received quantity (EA)">{id => <input id={id} inputMode="numeric" pattern="[0-9]+" required min="1" value={quantity} disabled={Boolean(draft) || mutation.isPending} onChange={event => setQuantity(event.target.value)} className={inputClass} />}</Field>
      <Field label="Supplier identity key">{id => <input id={id} required maxLength={200} value={supplierKey} disabled={Boolean(draft) || mutation.isPending} onChange={event => setSupplierKey(event.target.value)} className={inputClass} />}</Field>
      <Field label="Source document type">{id => <select id={id} value={documentType} disabled={Boolean(draft) || mutation.isPending} onChange={event => setDocumentType(event.target.value)} className={inputClass}><option>DELIVERY_NOTE</option><option>INVOICE</option><option>OTHER</option></select>}</Field>
      <Field label="Source document reference">{id => <input id={id} required maxLength={200} value={documentReference} disabled={Boolean(draft) || mutation.isPending} onChange={event => setDocumentReference(event.target.value)} className={inputClass} />}</Field>
      <Field label="Document line reference">{id => <input id={id} required maxLength={64} value={documentLineReference} disabled={Boolean(draft) || mutation.isPending} onChange={event => setDocumentLineReference(event.target.value)} className={inputClass} />}</Field>
      <Field label="Physically received at">{id => <input id={id} type="datetime-local" required value={physicallyReceivedAt} disabled={Boolean(draft) || mutation.isPending} onChange={event => setPhysicallyReceivedAt(event.target.value)} className={inputClass} />}</Field>
      <Field label="Reason for receipt">{id => <textarea id={id} required maxLength={500} value={reason} disabled={Boolean(draft) || mutation.isPending} onChange={event => setReason(event.target.value)} className={inputClass} />}</Field>
      <Field label="Evidence source link or reference">{id => <input id={id} required maxLength={256} value={evidenceReference} disabled={Boolean(draft) || mutation.isPending} onChange={event => setEvidenceReference(event.target.value)} className={inputClass} />}</Field>
      {currentPosition.isPending && productId && lotId && locationId && <p role="status" className="sm:col-span-2">Checking the current position version before receipt…</p>}
      {currentPosition.isError && <p role="alert" className="sm:col-span-2 text-sm">The current position could not be checked. Reload it before recording this receipt.</p>}
      {canConfirmVersion && <p className="sm:col-span-2 text-sm text-muted-foreground">{matchingPosition ? `This receipt will use expected position version ${matchingPosition.version}.` : 'No position exists for this product, lot and location yet; the receipt will initialize it from the physical goods.'}</p>}
      <label className="flex items-start gap-3 text-sm sm:col-span-2"><input type="checkbox" checked={confirmed} disabled={Boolean(draft) || mutation.isPending} onChange={event => setConfirmed(event.target.checked)} className="mt-1" />I confirmed these units are already physically present at the selected storage position.</label>
      <div className="flex flex-wrap gap-3 sm:col-span-2"><Button type="submit" disabled={!eligibleToSubmit}>{mutation.isPending ? 'Saving physical receipt…' : 'Confirm physical receipt'}</Button>{draft && mutation.isError && (definitelyRejected ? <Button type="button" variant="outline" onClick={reviseRejectedReceipt}>Review rejected receipt</Button> : <><Button type="button" variant="outline" disabled={mutation.isPending} onClick={() => mutation.mutate(draft)}>Retry same receipt</Button><Button type="button" variant="outline" onClick={() => { void recoverReceipt() }}>Check saved result</Button></>)}</div>
      {draft && mutation.isError && <p role="alert" className="sm:col-span-2 rounded-xl border border-amber-500/40 p-4 text-sm">{errorMessage(mutation.error)} {definitelyRejected ? 'The server returned a rejection; review it before preparing a new request.' : 'The receipt may have been committed.'} Request key {draft.requestId} and its original document data are preserved.</p>}
      {recoveryError && <p role="status" className="sm:col-span-2 rounded-xl border border-border p-4 text-sm">{recoveryError}</p>}
      {mutation.isSuccess && <p role="status" className="sm:col-span-2">Receipt confirmed in the inventory ledger. The saved position has been refreshed.</p>}
      {recovered && <p role="status" className="sm:col-span-2">A previously committed receipt was found at position version {recovered.positionVersion}. The saved position has been refreshed.</p>}
    </form>}
  </section>
}

function InventoryOperations({ organizationId, storeId, store, position, allocations, onCommitted }: { organizationId: string, storeId: string, store: Store, position: StockPosition, allocations: InventoryAllocation[], onCommitted: () => Promise<void> }) {
  const scope = useTenantKey()
  const tenantSignal = useTenantRequests()
  const [choice, setChoice] = useState<OperationChoice>('reserve')
  const [adjustmentDirection, setAdjustmentDirection] = useState<'increase' | 'decrease'>('increase')
  const [quantity, setQuantity] = useState('')
  const [reason, setReason] = useState('')
  const [evidenceReference, setEvidenceReference] = useState('')
  const [allocationId, setAllocationId] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [draft, setDraft] = useState<CommandDraft | null>(null)
  const [recoveryError, setRecoveryError] = useState('')
  const mutation = useMutation({ mutationKey: [...scope, 'inventory', storeId, 'command'], retry: false, gcTime: 0, mutationFn: (command: CommandDraft) => executeCommand(organizationId, storeId, position.id, command, tenantSignal()), onSuccess: async () => { setDraft(null); setConfirmed(false); setQuantity(''); setReason(''); setEvidenceReference(''); setAllocationId(''); await onCommitted() } })
  const choices = useMemo(() => {
    const actions: { value: OperationChoice, label: string, permission: Store['allowedActions'][number] }[] = [
    { value: 'reserve' as const, label: 'Reserve stock', permission: 'RESERVE_STOCK' },
    { value: 'release' as const, label: 'Release reservation', permission: 'RELEASE_RESERVATION' },
    { value: 'adjust' as const, label: 'Adjust physical count', permission: 'ADJUST_STOCK' },
    { value: 'writeOff' as const, label: 'Write off stock', permission: 'WRITE_OFF_STOCK' },
    { value: 'quarantine' as const, label: 'Quarantine stock', permission: 'QUARANTINE_STOCK' },
    { value: 'hold' as const, label: 'Place other hold', permission: 'MANAGE_INVENTORY_ALLOCATIONS' },
    { value: 'releaseHold' as const, label: 'Release other hold', permission: 'MANAGE_INVENTORY_ALLOCATIONS' },
    { value: 'sale' as const, label: 'Record manual sale', permission: 'RECORD_SALE' },
    ]
    return actions.filter(action => store.allowedActions.includes(action.permission))
  }, [store.allowedActions])
  const activeChoice = choices.find(action => action.value === choice) ?? choices[0]
  const effectiveChoice = activeChoice?.value ?? choice
  const matchingAllocations = allocations.filter(item => item.remaining > 0 && item.state === 'ACTIVE' && (effectiveChoice === 'release' ? item.bucket === 'RESERVED' : effectiveChoice === 'releaseHold' ? item.bucket !== 'RESERVED' : effectiveChoice === 'writeOff' ? true : false))
  const requiresAllocation = effectiveChoice === 'release' || effectiveChoice === 'releaseHold'
  const allowed = activeChoice && position.initialization === 'INITIALIZED' && position.trust !== 'FROZEN'
  const definitelyRejected = mutation.error instanceof ApiError && mutation.error.status >= 400 && mutation.error.status < 500
  const validQuantity = Number.isSafeInteger(Number(quantity)) && Number(quantity) > 0
  const valid = allowed && confirmed && !mutation.isPending && !draft && validQuantity && reason.trim() && evidenceReference.trim() && (!requiresAllocation || Boolean(allocationId))
  function submit() {
    if (!valid || !activeChoice) return
    const magnitude = Number(quantity)
    const command: CommandDraft = { choice: effectiveChoice, requestId: crypto.randomUUID(), expectedVersion: position.version, quantity: effectiveChoice === 'adjust' && adjustmentDirection === 'decrease' ? -magnitude : magnitude, reason: reason.trim(), evidenceReference: evidenceReference.trim(), ...(allocationId ? { allocationId } : {}) }
    setDraft(command); setRecoveryError(''); mutation.mutate(command)
  }
  async function recover() {
    if (!draft) return
    try {
      await getInventoryCommandOutcome(organizationId, storeId, operationFor(draft.choice), draft.requestId, tenantSignal())
      setDraft(null); setRecoveryError('The committed result was found. Refreshing the saved position now.')
      mutation.reset(); await onCommitted()
    } catch (error) {
      setRecoveryError(error instanceof ApiError && error.status === 404 ? 'No committed result is visible for this request key yet. Retry only with the preserved key and request details.' : errorMessage(error))
    }
  }
  function reviseRejectedCommand() {
    setDraft(null); setConfirmed(false); setRecoveryError('The server rejected the previous command. Review the refreshed stock, edit the quantity or evidence, and submit a new request.')
    mutation.reset(); void onCommitted()
  }
  if (choices.length === 0) return <p className="text-sm text-muted-foreground">Your current role has no stock-changing actions for this position.</p>
  return <section className="space-y-4 rounded-xl border border-border p-5" aria-label="Inventory operations">
    <div><h4 className="text-lg font-semibold">Stock operation</h4><p className="mt-1 text-sm text-muted-foreground">Every request uses position version {position.version}. The server checks current permissions, stock and safety evidence when saving.</p></div>
    <form onSubmit={event => { event.preventDefault(); submit() }} className="grid gap-4 sm:grid-cols-2">
      <Field label="Operation">{id => <select id={id} value={choice} disabled={Boolean(draft) || mutation.isPending} onChange={event => { setChoice(event.target.value as OperationChoice); setAllocationId('') }} className={inputClass}>{choices.map(action => <option key={action.value} value={action.value}>{action.label}</option>)}</select>}</Field>
      {effectiveChoice === 'adjust' && <Field label="Adjustment direction">{id => <select id={id} value={adjustmentDirection} disabled={Boolean(draft) || mutation.isPending} onChange={event => setAdjustmentDirection(event.target.value as 'increase' | 'decrease')} className={inputClass}><option value="increase">Increase recorded on-hand</option><option value="decrease">Decrease recorded on-hand</option></select>}</Field>}
      <Field label={`${effectiveChoice === 'adjust' ? 'Adjustment amount' : 'Quantity'} (${position.unit})`}>{id => <input id={id} required inputMode="numeric" pattern="[0-9]+" min="1" value={quantity} disabled={Boolean(draft) || mutation.isPending} onChange={event => setQuantity(event.target.value)} className={inputClass} />}</Field>
      {(requiresAllocation || choice === 'writeOff') && <Field label={requiresAllocation ? 'Reservation or hold to release' : 'Specific allocation to write off (optional)'}>{id => <select id={id} required={requiresAllocation} value={allocationId} disabled={Boolean(draft) || mutation.isPending} onChange={event => setAllocationId(event.target.value)} className={inputClass}><option value="">{requiresAllocation ? 'Choose an active allocation' : 'Use available stock'}</option>{matchingAllocations.map(allocation => <option key={allocation.id} value={allocation.id}>{allocation.bucket} · {allocation.remaining} {position.unit} · {allocation.id.slice(0, 8)}</option>)}</select>}</Field>}
      <Field label="Reason">{id => <textarea id={id} required maxLength={500} value={reason} disabled={Boolean(draft) || mutation.isPending} onChange={event => setReason(event.target.value)} className={inputClass} />}</Field>
      <Field label="Evidence link or source reference">{id => <input id={id} required maxLength={256} value={evidenceReference} disabled={Boolean(draft) || mutation.isPending} onChange={event => setEvidenceReference(event.target.value)} className={inputClass} />}</Field>
      {choice === 'sale' && <p className="rounded-xl border border-amber-500/40 bg-amber-50 p-4 text-sm text-amber-950 sm:col-span-2">A safety assessment describes current evidence; it does not grant permission to sell. The backend rechecks current lot, recall, expiry and storage evidence when committing the sale.</p>}
      <label className="flex items-start gap-3 text-sm sm:col-span-2"><input type="checkbox" checked={confirmed} disabled={Boolean(draft) || mutation.isPending} onChange={event => setConfirmed(event.target.checked)} className="mt-1" />I reviewed this position, quantity, reason and evidence reference before submitting.</label>
      <div className="flex flex-wrap gap-3 sm:col-span-2"><Button type="submit" disabled={!valid}>{mutation.isPending ? 'Saving operation…' : activeChoice.label}</Button>{draft && mutation.isError && (definitelyRejected ? <Button type="button" variant="outline" onClick={reviseRejectedCommand}>Review rejected command</Button> : <><Button type="button" variant="outline" disabled={mutation.isPending} onClick={() => mutation.mutate(draft)}>Retry same operation</Button><Button type="button" variant="outline" onClick={() => { void recover() }}>Check saved result</Button></>)}</div>
      {mutation.isError && draft && <p role="alert" className="sm:col-span-2 rounded-xl border border-amber-500/40 p-4 text-sm">{errorMessage(mutation.error)} {definitelyRejected ? 'The server returned a rejection; review it before preparing a new request.' : 'The operation may have been committed.'} Request key {draft.requestId} and the exact payload remain available.</p>}
      {recoveryError && <p role="status" className="sm:col-span-2 rounded-xl border border-border p-4 text-sm">{recoveryError}</p>}
      {mutation.isSuccess && <p role="status" className="sm:col-span-2">The backend confirmed this inventory operation. The saved position is being refreshed.</p>}
    </form>
  </section>
}

async function executeCommand(organizationId: string, storeId: string, positionId: string, draft: CommandDraft, signal: AbortSignal): Promise<InventoryCommand> {
  const body: StockCommand = { requestId: draft.requestId, expectedVersion: draft.expectedVersion, quantity: draft.quantity, reason: draft.reason, evidenceReference: draft.evidenceReference }
  if (draft.choice === 'release' || draft.choice === 'releaseHold') return releaseInventoryAllocation(organizationId, storeId, positionId, draft.allocationId!, draft.choice === 'release' ? 'reservations' : 'holds', body, signal)
  if (draft.choice === 'writeOff') return writeOffInventoryStock(organizationId, storeId, positionId, { stock: body, ...(draft.allocationId ? { allocationId: draft.allocationId } : {}) }, signal)
  const action = ({ reserve: 'reservations', adjust: 'adjustments', quarantine: 'quarantines', hold: 'holds', sale: 'sales' } as const)[draft.choice]
  return runInventoryCommand(organizationId, storeId, positionId, action, body, signal)
}

function operationFor(choice: OperationChoice): InventoryOperation {
  return ({ reserve: 'RESERVE', release: 'RELEASE', adjust: 'ADJUST', writeOff: 'WRITE_OFF', quarantine: 'QUARANTINE', hold: 'HOLD', releaseHold: 'RELEASE_HOLD', sale: 'SALE' } as const)[choice]
}

function ReconciliationPanel({ organizationId, storeId, canReconcile, canRemediate, scope, onReviewPosition, onCommitted }: { organizationId: string, storeId: string, canReconcile: boolean, canRemediate: boolean, scope: readonly unknown[], onReviewPosition: (positionId: string) => void, onCommitted: () => Promise<void> }) {
  const client = useQueryClient()
  const tenantSignal = useTenantRequests()
  const [recoveryRequired, setRecoveryRequired] = useState(false)
  const runs = useQuery({ queryKey: [...scope, 'reconciliations'], queryFn: ({ signal }) => listInventoryReconciliations(organizationId, storeId, signal), ...refresh })
  const reconcile = useMutation({ mutationKey: [...scope, 'reconcile'], retry: false, gcTime: 0, mutationFn: () => reconcileInventory(organizationId, storeId, tenantSignal()), onError: () => setRecoveryRequired(true), onSuccess: async () => { setRecoveryRequired(false); await onCommitted() } })
  async function recoverHistory() {
    const history = await runs.refetch()
    if (!history.error) { reconcile.reset(); setRecoveryRequired(false) }
  }
  const latest: Reconciliation | undefined = runs.data?.items[0]
  const findings = useQuery({ queryKey: [...scope, 'findings', latest?.id], enabled: Boolean(latest), queryFn: ({ signal }) => listInventoryFindings(organizationId, storeId, latest!.id, signal), ...refresh })
  return <section className="space-y-4 rounded-2xl border border-border p-5" aria-label="Inventory reconciliation">
    <header><h3 className="text-xl font-semibold">Reconciliation</h3><p className="mt-1 text-sm text-muted-foreground">Compares saved position buckets with receipts, movements and allocation evidence. Findings require review; this view does not silently alter stock.</p></header>
    {runs.isPending ? <p role="status">Loading reconciliation history…</p> : runs.isError ? <Unavailable title="Reconciliation history unavailable" error={runs.error} retry={() => { void runs.refetch() }} /> : latest ? <div className="space-y-3 rounded-xl bg-muted/40 p-4"><p className="font-medium">Latest run: {latest.severity} · {latest.checkedPositions} positions checked</p><p className="text-sm text-muted-foreground">Completed {new Date(latest.completedAt).toLocaleString()}</p>{findings.isPending ? <p role="status">Reading findings…</p> : findings.isError ? <p role="alert">{errorMessage(findings.error)}</p> : findings.data.items.length ? <><p className="text-sm text-muted-foreground">Review each affected position. Projection rebuild is limited to organization administrators and requires valid immutable ledger and allocation evidence.</p><ul className="space-y-2">{findings.data.items.map(item => <li key={`${item.positionId}:${item.code}`} className="space-y-3 rounded-lg border border-border bg-background p-3 text-sm"><div className="flex flex-wrap items-center justify-between gap-3"><div><strong>{item.severity} · {item.code}</strong><p>Position {item.positionId}, version {item.positionVersion}</p><p>Expected: {item.expected ?? 'Unknown'} · observed: {item.observed ?? 'Unknown'}</p></div><Button variant="outline" onClick={() => onReviewPosition(item.positionId)}>Review position</Button></div>{canRemediate && item.severity === 'CRITICAL' && item.code === 'PROJECTION_MISMATCH' && <ProjectionRepairForm organizationId={organizationId} storeId={storeId} runId={latest.id} finding={item} scope={scope} onCommitted={async () => { await onCommitted(); await client.invalidateQueries({ queryKey: [...scope, 'reconciliations'] }) }} />}</li>)}</ul></> : <p role="status" className="text-sm">No discrepancies were returned for this run.</p>}</div> : <p className="text-sm text-muted-foreground">No reconciliation run has been saved for this store.</p>}
    {canReconcile && <div className="space-y-3"><Button disabled={reconcile.isPending || recoveryRequired} onClick={() => reconcile.mutate()}>{reconcile.isPending ? 'Checking saved evidence…' : 'Run inventory reconciliation'}</Button>{reconcile.isError && <div className="space-y-2"><p role="alert" className="text-sm">{errorMessage(reconcile.error)} Reload the reconciliation history before starting another run.</p><Button type="button" variant="outline" disabled={runs.isFetching} onClick={() => { void recoverHistory() }}>{runs.isFetching ? 'Reloading history…' : 'Reload reconciliation history'}</Button></div>}{reconcile.isSuccess && <p role="status" className="text-sm">Reconciliation finished. Review its findings above before any corrective action.</p>}</div>}
    {!canReconcile && <p className="text-sm text-muted-foreground">Your current assignment can review reconciliation history but cannot start a new run.</p>}
  </section>
}

function ProjectionRepairForm({ organizationId, storeId, runId, finding, scope, onCommitted }: { organizationId: string, storeId: string, runId: string, finding: ReconciliationFinding, scope: readonly unknown[], onCommitted: () => Promise<void> }) {
  const tenantKey = useTenantKey()
  const tenantSignal = useTenantRequests()
  const [reason, setReason] = useState('')
  const [evidenceReference, setEvidenceReference] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [draft, setDraft] = useState<ProjectionRepair | null>(null)
  const [recoveryError, setRecoveryError] = useState('')
  const [recovered, setRecovered] = useState(false)
  const position = useQuery({ queryKey: [...scope, 'snapshot', finding.positionId], queryFn: ({ signal }) => getInventorySnapshot(organizationId, storeId, finding.positionId, signal), ...refresh })
  const mutation = useMutation({ mutationKey: [...tenantKey, 'inventory', storeId, 'projection-repair', runId, finding.positionId], retry: false, gcTime: 0, mutationFn: (body: ProjectionRepair) => repairInventoryProjection(organizationId, storeId, runId, finding.positionId, body, tenantSignal()), onSuccess: async () => { setDraft(null); setConfirmed(false); setRecovered(false); await onCommitted() } })
  const definitelyRejected = mutation.error instanceof ApiError && mutation.error.status >= 400 && mutation.error.status < 500
  const current = position.data
  const canSubmit = current?.trust === 'FROZEN' && confirmed && !draft && !mutation.isPending && reason.trim() && evidenceReference.trim()
  function submit() {
    if (!canSubmit || !current) return
    const body: ProjectionRepair = { requestId: crypto.randomUUID(), expectedVersion: current.version, reason: reason.trim(), evidenceReference: evidenceReference.trim() }
    setDraft(body); setRecoveryError(''); setRecovered(false); mutation.mutate(body)
  }
  async function recover() {
    if (!draft) return
    try {
      await getInventoryCommandOutcome(organizationId, storeId, 'REMEDIATE', draft.requestId, tenantSignal())
      mutation.reset(); setDraft(null); setRecovered(true); await onCommitted()
    } catch (error) {
      setRecoveryError(error instanceof ApiError && error.status === 404 ? 'No committed repair is visible for this request key yet. Retry only with the preserved request key and details.' : errorMessage(error))
    }
  }
  function reviseRejectedRepair() {
    setDraft(null); setConfirmed(false); setRecoveryError('The server rejected this repair. Reload the position and review the reconciliation evidence before preparing another request.')
    mutation.reset(); void position.refetch()
  }
  return <section className="space-y-3 rounded-lg border border-amber-500/40 bg-amber-50 p-4 text-amber-950" aria-label={`Projection repair for position ${finding.positionId}`}>
    <h4 className="font-semibold">Authorized projection recovery</h4>
    {position.isPending ? <p role="status">Checking the current frozen position version…</p> : position.isError ? <p role="alert">The current position could not be checked. Reload it before preparing a repair.</p> : current?.trust !== 'FROZEN' ? <p role="status">This position is no longer frozen. Run reconciliation again before considering any recovery.</p> : <form onSubmit={event => { event.preventDefault(); submit() }} className="grid gap-3 sm:grid-cols-2">
      <p className="sm:col-span-2 text-sm">The server will rebuild this projection only if the immutable ledger and allocation history still agree. History is never rewritten. Current frozen version: {current.version}.</p>
      <Field label="Reason for projection recovery">{id => <textarea id={id} required maxLength={500} value={reason} disabled={Boolean(draft) || mutation.isPending} onChange={event => setReason(event.target.value)} className={inputClass} />}</Field>
      <Field label="Evidence reference">{id => <input id={id} required maxLength={256} value={evidenceReference} disabled={Boolean(draft) || mutation.isPending} onChange={event => setEvidenceReference(event.target.value)} className={inputClass} />}</Field>
      <label className="flex items-start gap-3 text-sm sm:col-span-2"><input type="checkbox" checked={confirmed} disabled={Boolean(draft) || mutation.isPending} onChange={event => setConfirmed(event.target.checked)} className="mt-1" />I reviewed this critical finding and authorize a projection rebuild from validated saved evidence.</label>
      <div className="flex flex-wrap gap-3 sm:col-span-2"><Button type="submit" disabled={!canSubmit}>{mutation.isPending ? 'Validating saved evidence…' : 'Rebuild frozen projection'}</Button>{draft && mutation.isError && (definitelyRejected ? <Button type="button" variant="outline" onClick={reviseRejectedRepair}>Review rejected repair</Button> : <><Button type="button" variant="outline" onClick={() => mutation.mutate(draft)}>Retry same repair</Button><Button type="button" variant="outline" onClick={() => { void recover() }}>Check saved result</Button></>)}</div>
      {draft && mutation.isError && <p role="alert" className="sm:col-span-2">{errorMessage(mutation.error)} {definitelyRejected ? 'Review the position before preparing another request.' : 'The repair may have committed.'} Request key {draft.requestId} and its reviewed details are preserved.</p>}
      {recoveryError && <p role="status" className="sm:col-span-2">{recoveryError}</p>}
      {(mutation.isSuccess || recovered) && <p role="status" className="sm:col-span-2">Projection recovery was confirmed. Run a new reconciliation to verify the position.</p>}
    </form>}
  </section>
}

function Unavailable({ title, error, retry }: { title: string, error: unknown, retry: () => void }) {
  const status = error instanceof ApiError ? error.status : undefined
  const state = status === 403 ? 'forbidden' : status === 409 ? 'conflict' : status === 503 || !status ? 'unavailable' : 'error'
  const detail = status === 401 ? 'Your session ended. Sign in again to review inventory.' : status === 403 ? 'Your current store access does not permit this request.' : status === 409 ? 'Inventory changed while this view was open. Reload the saved position before continuing.' : status === 422 ? 'The submitted values failed inventory checks. Review the fields and current stock.' : status === 503 ? 'The service is temporarily unavailable. Saved stock was not changed by this read request.' : errorMessage(error)
  return <div className="space-y-4"><StateMessage state={state} title={title} description={`${detail} ${errorMessage(error)}`} /><Button variant="outline" onClick={retry}>Reload current inventory</Button></div>
}

function signed(value: number) { return value > 0 ? `+${value}` : String(value) }
