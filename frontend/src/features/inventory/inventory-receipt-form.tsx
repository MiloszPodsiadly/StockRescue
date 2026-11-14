import { useRef, useState } from 'react'
import { useMutation, useQueries, useQueryClient } from '@tanstack/react-query'
import { ApiError, errorMessage } from '@/api/client'
import { evaluateLotEligibility } from '@/api/food-safety'
import type { Product } from '@/api/products'
import { listLots } from '@/api/lots'
import type { StorageLocation } from '@/api/storage-locations'
import { receiveInventoryGoodsDocument, getInventoryReceiptDocumentOutcome, listInventoryPositions } from '@/api/inventory'
import type { InventoryReceiptDocument, InventoryReceiptDocumentResult } from '@/api/inventory'
import type { Store } from '@/api/stores'
import { Button } from '@/components/ui/button'
import { Field, inputClass } from '@/components/form-field'
import { useTenantKey, useTenantRequests } from '../organizations/tenant-requests'
import { clearPendingInventoryCommand, savePendingInventoryCommand, usePendingInventoryCommand } from './inventory-pending-commands'

type ReceiptLineDraft = { id: string, documentLineReference: string, productId: string, lotId: string, locationId: string, quantity: string }
type Props = { organizationId: string, storeId: string, store: Store, products: Product[], locations: StorageLocation[], scope: readonly unknown[], onCommitted: () => Promise<void> }
const subject = 'receipt-document'
const refresh = { refetchOnWindowFocus: true, staleTime: 0 } as const
const newLine = (): ReceiptLineDraft => ({ id: crypto.randomUUID(), documentLineReference: '', productId: '', lotId: '', locationId: '', quantity: '' })
const toLocalDateTime = (value: string) => {
  const date = new Date(value)
  date.setMinutes(date.getMinutes() - date.getTimezoneOffset())
  return date.toISOString().slice(0, 16)
}

export function InventoryReceiptForm({ organizationId, storeId, store, products, locations, scope, onCommitted }: Props) {
  const tenant = useTenantKey()
  const client = useQueryClient()
  const tenantSignal = useTenantRequests()
  const pending = usePendingInventoryCommand<InventoryReceiptDocument>(tenant, storeId, subject)
  const submittingRef = useRef(false)
  const [submitting, setSubmitting] = useState(false)
  const [lines, setLines] = useState<ReceiptLineDraft[]>(() => pending ? pending.payload.lines.map(line => ({ ...line, id: crypto.randomUUID(), quantity: String(line.quantity) })) : [newLine()])
  const [supplierKey, setSupplierKey] = useState(() => pending?.payload.supplierKey ?? '')
  const [documentType, setDocumentType] = useState(() => pending?.payload.documentType ?? 'DELIVERY_NOTE')
  const [documentReference, setDocumentReference] = useState(() => pending?.payload.documentReference ?? '')
  const [physicallyReceivedAt, setPhysicallyReceivedAt] = useState(() => pending ? toLocalDateTime(pending.payload.physicallyReceivedAt) : new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16))
  const [reason, setReason] = useState(() => pending?.payload.reason ?? '')
  const [evidenceReference, setEvidenceReference] = useState(() => pending?.payload.evidenceReference ?? '')
  const [confirmed, setConfirmed] = useState(false)
  const [recovered, setRecovered] = useState<{ result: InventoryReceiptDocumentResult, replayed: boolean } | null>(null)
  const [recoveryError, setRecoveryError] = useState('')

  const lots = useQueries({ queries: lines.map(line => ({
    queryKey: [...scope, 'catalog', 'inventory-receipt-lots', line.productId],
    enabled: Boolean(line.productId),
    queryFn: ({ signal }) => listLots(organizationId, line.productId, false, 0, signal),
    ...refresh,
  })) })
  const positions = useQueries({ queries: lines.map(line => ({
    queryKey: [...scope, 'inventory', storeId, 'receipt-position', line.productId, line.lotId, line.locationId],
    enabled: Boolean(line.productId && line.lotId && line.locationId),
    queryFn: ({ signal }) => listInventoryPositions(organizationId, storeId, { productId: line.productId, lotId: line.lotId, locationId: line.locationId, limit: 2 }, signal),
    ...refresh,
  })) })
  const eligibility = useQueries({ queries: lines.map(line => ({
    queryKey: [...scope, 'inventory', storeId, 'receipt-eligibility', line.lotId, line.locationId],
    enabled: Boolean(line.lotId && line.locationId),
    queryFn: ({ signal }) => evaluateLotEligibility(organizationId, line.lotId, { storeId, locationId: line.locationId }, 'SALE', signal),
    ...refresh,
  })) })
  const mutation = useMutation({
    mutationKey: [...tenant, 'inventory', storeId, subject],
    retry: false,
    gcTime: 0,
    mutationFn: (body: InventoryReceiptDocument) => receiveInventoryGoodsDocument(organizationId, storeId, body, tenantSignal()),
    onError: (error, body) => {
      submittingRef.current = false
      setSubmitting(false)
      if (error instanceof ApiError && error.status === 401) return
      const rejected = error instanceof ApiError && error.status >= 400 && error.status < 500
      savePendingInventoryCommand(tenant, storeId, subject, { payload: body, phase: rejected ? 'rejected' : 'unknown', message: errorMessage(error) })
    },
    onSuccess: async response => {
      submittingRef.current = false
      setSubmitting(false)
      clearPendingInventoryCommand(tenant, storeId, subject)
      setRecovered({ result: response.result, replayed: !response.created })
      setRecoveryError('')
      resetAfterCommit()
      await onCommitted()
    },
  })

  const allowed = store.allowedActions.includes('RECEIVE_STOCK')
  const versionsReady = lines.every((line, index) => Boolean(line.productId && line.lotId && line.locationId && positions[index]?.data && !positions[index]?.isFetching && !positions[index]?.isError && (positions[index]?.data.items.length ?? 2) <= 1))
  const validLines = lines.length > 0 && lines.every(line => Boolean(line.documentLineReference.trim() && line.productId && line.lotId && line.locationId && Number.isSafeInteger(Number(line.quantity)) && Number(line.quantity) > 0))
  const uniqueLineReferences = new Set(lines.map(line => line.documentLineReference.trim())).size === lines.length
  const positionKeys = lines.map(line => `${line.productId}:${line.lotId}:${line.locationId}`)
  const uniquePositions = new Set(positionKeys).size === positionKeys.length
  const readyToSubmit = allowed && versionsReady && validLines && uniqueLineReferences && uniquePositions && confirmed && !mutation.isPending && !pending && !submitting && Boolean(supplierKey.trim() && documentReference.trim() && reason.trim() && evidenceReference.trim())

  function resetAfterCommit() {
    setLines([newLine()])
    setSupplierKey('')
    setDocumentType('DELIVERY_NOTE')
    setDocumentReference('')
    setPhysicallyReceivedAt(new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16))
    setReason('')
    setEvidenceReference('')
    setConfirmed(false)
  }

  function updateLine(index: number, changes: Partial<ReceiptLineDraft>) {
    setLines(current => current.map((line, lineIndex) => lineIndex === index ? { ...line, ...changes } : line))
  }

  function submitDocument() {
    if (!readyToSubmit || submittingRef.current) return
    const receivedAt = new Date(physicallyReceivedAt)
    if (Number.isNaN(receivedAt.getTime())) return
    const payload: InventoryReceiptDocument = {
      requestId: crypto.randomUUID(),
      supplierKey: supplierKey.trim(),
      documentType,
      documentReference: documentReference.trim(),
      physicallyReceivedAt: receivedAt.toISOString(),
      reason: reason.trim(),
      evidenceReference: evidenceReference.trim(),
      lines: lines.map((line, index) => ({
        documentLineReference: line.documentLineReference.trim(),
        productId: line.productId,
        lotId: line.lotId,
        locationId: line.locationId,
        quantity: Number(line.quantity),
        expectedVersion: positions[index]!.data!.items[0]?.version ?? 0,
      })),
    }
    submittingRef.current = true
    setSubmitting(true)
    savePendingInventoryCommand(tenant, storeId, subject, { payload, phase: 'in-flight' })
    setRecoveryError('')
    setRecovered(null)
    mutation.mutate(payload)
  }

  async function checkSavedResult() {
    if (!pending) return
    try {
      const result = await getInventoryReceiptDocumentOutcome(organizationId, storeId, pending.payload.requestId, tenantSignal())
      clearPendingInventoryCommand(tenant, storeId, subject)
      mutation.reset()
      setRecovered({ result, replayed: true })
      setRecoveryError('')
      resetAfterCommit()
      await client.invalidateQueries({ queryKey: [...tenant, 'inventory', storeId] })
    } catch (error) {
      const message = error instanceof ApiError && error.status === 404
        ? 'No saved result is visible yet. This does not prove the original request cannot still commit. Keep this document and use only its exact request key for a retry.'
        : errorMessage(error)
      savePendingInventoryCommand(tenant, storeId, subject, { ...pending, phase: 'unknown', message })
      setRecoveryError(message)
    }
  }

  function retryExactDocument() {
    if (!allowed || !pending || pending.phase !== 'unknown' || submittingRef.current) return
    submittingRef.current = true
    setSubmitting(true)
    savePendingInventoryCommand(tenant, storeId, subject, { ...pending, phase: 'in-flight', message: undefined })
    setRecoveryError('')
    mutation.mutate(pending.payload)
  }

  function reviewRejectedDocument() {
    clearPendingInventoryCommand(tenant, storeId, subject)
    mutation.reset()
    setConfirmed(false)
    setRecovered(null)
    setRecoveryError('The server rejected this document. Review the reason and refreshed position versions before preparing a new request.')
    void onCommitted()
  }

  return <section className="space-y-4 rounded-xl border border-border p-5" aria-label="Physical goods receipt">
    <div><h4 className="text-lg font-semibold">Record physical receipt document</h4><p className="mt-1 text-sm text-muted-foreground">Submit one source document with its receipt lines. The backend commits every line atomically and rechecks current eligibility for each lot.</p></div>
    {!allowed && !pending ? <p className="text-sm text-muted-foreground">Your current assignment cannot receive stock into this store.</p> : <form onSubmit={event => { event.preventDefault(); submitDocument() }} className="grid min-w-0 gap-4 sm:grid-cols-2">
      <div className="space-y-4 sm:col-span-2" aria-label="Receipt document lines">
        <div className="flex flex-wrap items-center justify-between gap-3"><h5 className="text-base font-semibold">Document lines</h5><Button type="button" variant="outline" disabled={Boolean(pending) || mutation.isPending || lines.length >= 50} onClick={() => setLines(current => [...current, newLine()])}>Add receipt line</Button></div>
        {lines.map((line, index) => <fieldset key={line.id} className="grid min-w-0 gap-4 rounded-xl border border-border p-4 sm:grid-cols-2"><legend className="px-1 font-medium">Line {index + 1}</legend>
          <Field label={`Line ${index + 1} reference`}>{id => <input id={id} required maxLength={64} value={line.documentLineReference} disabled={Boolean(pending) || mutation.isPending} onChange={event => updateLine(index, { documentLineReference: event.target.value })} className={inputClass} />}</Field>
          <Field label={`Line ${index + 1} product`}>{id => <select id={id} required value={line.productId} disabled={Boolean(pending) || mutation.isPending} onChange={event => updateLine(index, { productId: event.target.value, lotId: '' })} className={inputClass}><option value="">Choose a product</option>{products.map(product => <option key={product.id} value={product.id}>{product.sku} · {product.name}</option>)}</select>}</Field>
          <Field label={`Line ${index + 1} lot`}>{id => <select id={id} required value={line.lotId} disabled={!line.productId || Boolean(pending) || mutation.isPending} onChange={event => updateLine(index, { lotId: event.target.value })} className={inputClass}><option value="">Choose a lot</option>{lots[index]?.data?.items.map(lot => <option key={lot.id} value={lot.id}>{lot.lotCode}</option>)}</select>}</Field>
          <Field label={`Line ${index + 1} storage location`}>{id => <select id={id} required value={line.locationId} disabled={Boolean(pending) || mutation.isPending} onChange={event => updateLine(index, { locationId: event.target.value })} className={inputClass}><option value="">Choose a location</option>{locations.map(location => <option key={location.id} value={location.id}>{location.code} · {location.name}</option>)}</select>}</Field>
          <Field label={`Line ${index + 1} received quantity (EA)`}>{id => <input id={id} inputMode="numeric" pattern="[0-9]+" required min="1" value={line.quantity} disabled={Boolean(pending) || mutation.isPending} onChange={event => updateLine(index, { quantity: event.target.value })} className={inputClass} />}</Field>
          {line.productId && line.lotId && line.locationId && positions[index]?.isFetching && <p role="status" className="sm:col-span-2">Checking line {index + 1} position version…</p>}
          {positions[index]?.isError && <p role="alert" className="sm:col-span-2 text-sm">Line {index + 1} position version could not be checked. Refresh before submitting this document.</p>}
          {positions[index]?.data && <p className="sm:col-span-2 text-sm text-muted-foreground">{positions[index].data.items[0] ? `Expected position version ${positions[index].data.items[0].version}.` : 'No position exists yet; this receipt will initialize it from the physical goods.'}</p>}
          {eligibility[index]?.isPending && line.lotId && line.locationId && <p role="status" className="sm:col-span-2 text-sm">Checking current eligibility for line {index + 1}…</p>}
          {eligibility[index]?.data && eligibility[index].data.decision !== 'ALLOWED' && <p role="alert" className="sm:col-span-2 rounded-lg border border-amber-500/40 bg-amber-50 p-3 text-sm text-amber-950">Line {index + 1}: current lot and storage evidence needs review ({eligibility[index].data.decision.toLowerCase().replaceAll('_', ' ')}). The backend may receive this stock into quarantine.</p>}
          {eligibility[index]?.isError && <p role="status" className="sm:col-span-2 rounded-lg border border-amber-500/40 p-3 text-sm">Line {index + 1}: eligibility could not be assessed here. The backend will recheck and may quarantine the received stock.</p>}
          {lines.length > 1 && <Button type="button" variant="outline" className="sm:col-span-2 sm:justify-self-start" disabled={Boolean(pending) || mutation.isPending} onClick={() => setLines(current => current.filter((_, lineIndex) => lineIndex !== index))}>Remove line {index + 1}</Button>}
        </fieldset>)}
        {validLines && !uniqueLineReferences && <p role="alert" className="text-sm">Each receipt line needs a unique document line reference.</p>}
        {validLines && !uniquePositions && <p role="alert" className="text-sm">Use one line per product, lot and storage position in this document.</p>}
      </div>
      <Field label="Supplier identity key">{id => <input id={id} required maxLength={200} value={supplierKey} disabled={Boolean(pending) || mutation.isPending} onChange={event => setSupplierKey(event.target.value)} className={inputClass} />}</Field>
      <Field label="Source document type">{id => <select id={id} value={documentType} disabled={Boolean(pending) || mutation.isPending} onChange={event => setDocumentType(event.target.value)} className={inputClass}><option>DELIVERY_NOTE</option><option>INVOICE</option><option>OTHER</option></select>}</Field>
      <Field label="Source document reference">{id => <input id={id} required maxLength={200} value={documentReference} disabled={Boolean(pending) || mutation.isPending} onChange={event => setDocumentReference(event.target.value)} className={inputClass} />}</Field>
      <Field label="Physically received at">{id => <input id={id} type="datetime-local" required value={physicallyReceivedAt} disabled={Boolean(pending) || mutation.isPending} onChange={event => setPhysicallyReceivedAt(event.target.value)} className={inputClass} />}</Field>
      <Field label="Reason for receipt">{id => <textarea id={id} required maxLength={500} value={reason} disabled={Boolean(pending) || mutation.isPending} onChange={event => setReason(event.target.value)} className={inputClass} />}</Field>
      <Field label="Evidence source link or reference">{id => <input id={id} required maxLength={256} value={evidenceReference} disabled={Boolean(pending) || mutation.isPending} onChange={event => setEvidenceReference(event.target.value)} className={inputClass} />}</Field>
      {lines.some((line, index) => line.productId && line.lotId && line.locationId && positions[index]?.isFetching) && <p role="status" className="sm:col-span-2">Checking each current position version before receipt…</p>}
      {lines.every((line, index) => line.productId && line.lotId && line.locationId && positions[index]?.data && !positions[index]?.isError) && <p className="sm:col-span-2 text-sm text-muted-foreground">The document will use the displayed expected position version for every line. Confirm only after comparing each line and its source evidence.</p>}
      <label className="flex items-start gap-3 text-sm sm:col-span-2"><input type="checkbox" checked={confirmed} disabled={Boolean(pending) || mutation.isPending} onChange={event => setConfirmed(event.target.checked)} className="mt-1" />I confirmed all quantities are physically present and reviewed the complete source document, lines, reason and evidence.</label>
      <div className="flex flex-wrap gap-3 sm:col-span-2"><Button type="submit" disabled={!readyToSubmit}>{mutation.isPending ? 'Saving receipt document…' : 'Confirm physical receipt document'}</Button>
        {pending?.phase === 'in-flight' && <Button type="button" variant="outline" onClick={() => { void checkSavedResult() }}>Check saved result</Button>}
        {pending?.phase === 'unknown' && <><Button type="button" variant="outline" disabled={!allowed || submitting} onClick={retryExactDocument}>Retry exact document</Button><Button type="button" variant="outline" onClick={() => { void checkSavedResult() }}>Check saved result</Button></>}
        {pending?.phase === 'rejected' && <><Button type="button" variant="outline" onClick={() => { void checkSavedResult() }}>Check saved result</Button><Button type="button" variant="outline" onClick={reviewRejectedDocument}>Review rejected document</Button></>}
      </div>
      {pending && <p role={pending.phase === 'rejected' ? 'alert' : 'status'} className="sm:col-span-2 rounded-xl border border-amber-500/40 p-4 text-sm">{pending.message ?? (pending.phase === 'in-flight' ? 'The result is still being confirmed. Check the saved result before taking another action.' : '')}{pending.phase === 'unknown' && ' The receipt may have been committed.'}{pending.phase === 'in-flight' && ' Check the saved result before taking another action.'} Request key {pending.payload.requestId} and every reviewed line are preserved in memory. The form is locked until this request is resolved.</p>}
      {pending && <section aria-label="Pending receipt document details" className="sm:col-span-2 space-y-2 rounded-xl border border-border p-4 text-sm"><h5 className="font-semibold">Original document held for recovery</h5><p>Supplier {pending.payload.supplierKey} · {pending.payload.documentType} {pending.payload.documentReference}</p><p>Received {new Date(pending.payload.physicallyReceivedAt).toLocaleString()} · {pending.payload.reason} · Evidence: {pending.payload.evidenceReference}</p><ul className="space-y-1">{pending.payload.lines.map(line => <li key={line.documentLineReference}>Line {line.documentLineReference}: {line.quantity} EA · product {line.productId} · lot {line.lotId} · location {line.locationId} · expected version {line.expectedVersion}</li>)}</ul></section>}
      {recoveryError && <p role="status" className="sm:col-span-2 rounded-xl border border-border p-4 text-sm">{recoveryError}</p>}
      {recovered && <section aria-label="Committed receipt lines" className="space-y-3 rounded-xl border border-border p-4 sm:col-span-2"><h5 className="font-semibold">{recovered.replayed ? 'Saved receipt document found' : 'Receipt document committed'}</h5><p className="text-sm text-muted-foreground">Movement {recovered.result.movementId}. Each row shows the committed position totals.</p><ul className="space-y-2">{recovered.result.lines.map(line => <li key={line.documentLineReference} className="rounded-lg bg-muted/40 p-3 text-sm"><p className="font-medium">Line {line.documentLineReference} · version {line.version}</p><p>{line.allocationId ? 'Received into quarantine' : 'Received into available stock'} · on hand {line.onHand} EA · available {line.available} EA · quarantined {line.quarantined} EA</p><p className="break-all text-muted-foreground">Position {line.positionId}{line.allocationId ? ` · quarantine allocation ${line.allocationId}` : ''}</p></li>)}</ul></section>}
    </form>}
  </section>
}
