import { useRef, useState } from 'react'
import { useQueries } from '@tanstack/react-query'
import { errorMessage } from '@/api/client'
import { evaluateLotEligibility } from '@/api/food-safety'
import type { Product } from '@/api/products'
import { listLots } from '@/api/lots'
import type { StorageLocation } from '@/api/storage-locations'
import { executeInventoryCommandIntent, listInventoryPositions, prepareInventoryCommandIntent } from '@/api/inventory'
import type { InventoryReceiptDocument } from '@/api/inventory'
import type { Store } from '@/api/stores'
import { Button } from '@/components/ui/button'
import { Field, inputClass } from '@/components/form-field'
import { useTenantRequests } from '../organizations/tenant-requests'

type ReceiptLineDraft = { id: string, documentLineReference: string, productId: string, lotId: string, locationId: string, quantity: string }
type Props = { organizationId: string, storeId: string, store: Store, products: Product[], locations: StorageLocation[], scope: readonly unknown[], intentsLoaded: boolean, pendingIntent: boolean, onCommitted: () => Promise<void> }
const refresh = { refetchOnWindowFocus: true, staleTime: 0 } as const
const newLine = (): ReceiptLineDraft => ({ id: crypto.randomUUID(), documentLineReference: '', productId: '', lotId: '', locationId: '', quantity: '' })
const maximumStockQuantity = 9_223_372_036_854_775_807n

export function InventoryReceiptForm({ organizationId, storeId, store, products, locations, scope, intentsLoaded, pendingIntent, onCommitted }: Props) {
  const tenantSignal = useTenantRequests()
  const submittingRef = useRef(false)
  const [submitting, setSubmitting] = useState(false)
  const [lines, setLines] = useState<ReceiptLineDraft[]>([newLine()])
  const [supplierKey, setSupplierKey] = useState('')
  const [documentType, setDocumentType] = useState('DELIVERY_NOTE')
  const [documentReference, setDocumentReference] = useState('')
  const [physicallyReceivedAt, setPhysicallyReceivedAt] = useState(() => new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16))
  const [reason, setReason] = useState('')
  const [evidenceReference, setEvidenceReference] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [submissionError, setSubmissionError] = useState('')

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
  const allowed = store.allowedActions.includes('RECEIVE_STOCK')
  const versionsReady = lines.every((line, index) => Boolean(line.productId && line.lotId && line.locationId && positions[index]?.data && !positions[index]?.isFetching && !positions[index]?.isError && (positions[index]?.data.items.length ?? 2) <= 1))
  const validLines = lines.length > 0 && lines.every(line => Boolean(line.documentLineReference.trim() && line.productId && line.lotId && line.locationId && Number.isSafeInteger(Number(line.quantity)) && Number(line.quantity) > 0))
  const uniqueLineReferences = new Set(lines.map(line => line.documentLineReference.trim())).size === lines.length
  const positionKeys = lines.map(line => `${line.productId}:${line.lotId}:${line.locationId}`)
  const repeatedPositionKeys = new Set(positionKeys.filter((key, index) => positionKeys.indexOf(key) < index))
  const expectedVersionByPosition = new Map<string, number>()
  const expectedVersionsAgree = lines.every((_, index) => {
    const key = positionKeys[index]
    const version = positions[index]?.data?.items[0]?.version ?? 0
    const previous = expectedVersionByPosition.get(key)
    if (previous === undefined) { expectedVersionByPosition.set(key, version); return true }
    return previous === version
  })
  const quantityByPosition = new Map<string, bigint>()
  for (const [index, line] of lines.entries()) {
    if (!/^\d+$/.test(line.quantity)) continue
    quantityByPosition.set(positionKeys[index], (quantityByPosition.get(positionKeys[index]) ?? 0n) + BigInt(line.quantity))
  }
  const groupTotalsWithinRange = [...quantityByPosition.values()].every(total => total <= maximumStockQuantity)
  const readyToSubmit = allowed && intentsLoaded && !pendingIntent && versionsReady && expectedVersionsAgree && groupTotalsWithinRange && validLines && uniqueLineReferences && confirmed && !submitting && Boolean(supplierKey.trim() && documentReference.trim() && reason.trim() && evidenceReference.trim())

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

  async function submitDocument() {
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
        expectedVersion: expectedVersionByPosition.get(positionKeys[index]) ?? 0,
      })),
    }
    submittingRef.current = true
    setSubmitting(true)
    setSubmissionError('')
    try {
      const intent = await prepareInventoryCommandIntent(organizationId, storeId, {
        prepareId: crypto.randomUUID(), operation: 'RECEIPT_DOCUMENT', payload,
      }, tenantSignal())
      await executeInventoryCommandIntent(organizationId, storeId, intent.id, tenantSignal())
      resetAfterCommit()
      await onCommitted()
    } catch (error) {
      setSubmissionError(errorMessage(error))
      await onCommitted()
    } finally {
      submittingRef.current = false
      setSubmitting(false)
    }
  }

  return <section className="space-y-4 rounded-xl border border-border p-5" aria-label="Physical goods receipt">
    <div><h4 className="text-lg font-semibold">Record physical receipt document</h4><p className="mt-1 text-sm text-muted-foreground">Submit one source document with its receipt lines. The backend commits every line atomically and rechecks current eligibility for each lot.</p></div>
    {!allowed ? <p className="text-sm text-muted-foreground">Your current assignment cannot receive stock into this store.</p> : !intentsLoaded ? <p role="status" className="text-sm">Checking for a saved receipt document before enabling a new one.</p> : pendingIntent ? <p role="status" className="text-sm">Resolve the saved receipt document in the recovery panel above before preparing another document.</p> : <form onSubmit={event => { event.preventDefault(); void submitDocument() }} className="grid min-w-0 gap-4 sm:grid-cols-2">
      <div className="space-y-4 sm:col-span-2" aria-label="Receipt document lines">
        <div className="flex flex-wrap items-center justify-between gap-3"><h5 className="text-base font-semibold">Document lines</h5><Button type="button" variant="outline" disabled={submitting || lines.length >= 50} onClick={() => setLines(current => [...current, newLine()])}>Add receipt line</Button></div>
        {repeatedPositionKeys.size > 0 && <p role="status" className="rounded-lg border border-border bg-muted/30 p-3 text-sm sm:col-span-2">Lines for the same product, lot and storage position keep their separate supplier references and quantities. Their quantities will aggregate into one stock movement using one shared current position version.</p>}
        {validLines && !expectedVersionsAgree && <p role="alert" className="text-sm sm:col-span-2">Repeated lines for one position resolved to different current versions. Refresh the position versions before submitting.</p>}
        {validLines && !groupTotalsWithinRange && <p role="alert" className="text-sm sm:col-span-2">The combined quantity for a position exceeds the supported stock amount. Reduce the quantities before submitting.</p>}
        {lines.map((line, index) => <fieldset key={line.id} className="grid min-w-0 gap-4 rounded-xl border border-border p-4 sm:grid-cols-2"><legend className="px-1 font-medium">Line {index + 1}</legend>
          <Field label={`Line ${index + 1} reference`}>{id => <input id={id} required maxLength={64} value={line.documentLineReference} disabled={submitting} onChange={event => updateLine(index, { documentLineReference: event.target.value })} className={inputClass} />}</Field>
          <Field label={`Line ${index + 1} product`}>{id => <select id={id} required value={line.productId} disabled={submitting} onChange={event => updateLine(index, { productId: event.target.value, lotId: '' })} className={inputClass}><option value="">Choose a product</option>{products.map(product => <option key={product.id} value={product.id}>{product.sku} · {product.name}</option>)}</select>}</Field>
          <Field label={`Line ${index + 1} lot`}>{id => <select id={id} required value={line.lotId} disabled={!line.productId || submitting} onChange={event => updateLine(index, { lotId: event.target.value })} className={inputClass}><option value="">Choose a lot</option>{lots[index]?.data?.items.map(lot => <option key={lot.id} value={lot.id}>{lot.lotCode}</option>)}</select>}</Field>
          <Field label={`Line ${index + 1} storage location`}>{id => <select id={id} required value={line.locationId} disabled={submitting} onChange={event => updateLine(index, { locationId: event.target.value })} className={inputClass}><option value="">Choose a location</option>{locations.map(location => <option key={location.id} value={location.id}>{location.code} · {location.name}</option>)}</select>}</Field>
          <Field label={`Line ${index + 1} received quantity (EA)`}>{id => <input id={id} inputMode="numeric" pattern="[0-9]+" maxLength={16} required min="1" value={line.quantity} disabled={submitting} onChange={event => updateLine(index, { quantity: event.target.value })} className={inputClass} />}</Field>
          {line.productId && line.lotId && line.locationId && positions[index]?.isFetching && <p role="status" className="sm:col-span-2">Checking line {index + 1} position version…</p>}
          {positions[index]?.isError && <p role="alert" className="sm:col-span-2 text-sm">Line {index + 1} position version could not be checked. Refresh before submitting this document.</p>}
          {positions[index]?.data && <p className="sm:col-span-2 text-sm text-muted-foreground">{positions[index].data.items[0] ? `Expected position version ${positions[index].data.items[0].version}.` : 'No position exists yet; this receipt will initialize it from the physical goods.'}</p>}
          {eligibility[index]?.isPending && line.lotId && line.locationId && <p role="status" className="sm:col-span-2 text-sm">Checking current eligibility for line {index + 1}…</p>}
          {eligibility[index]?.data && eligibility[index].data.decision !== 'ALLOWED' && <p role="alert" className="sm:col-span-2 rounded-lg border border-amber-500/40 bg-amber-50 p-3 text-sm text-amber-950">Line {index + 1}: current lot and storage evidence needs review ({eligibility[index].data.decision.toLowerCase().replaceAll('_', ' ')}). The backend may receive this stock into quarantine.</p>}
          {eligibility[index]?.isError && <p role="status" className="sm:col-span-2 rounded-lg border border-amber-500/40 p-3 text-sm">Line {index + 1}: eligibility could not be assessed here. The backend will recheck and may quarantine the received stock.</p>}
          {lines.length > 1 && <Button type="button" variant="outline" className="sm:col-span-2 sm:justify-self-start" disabled={submitting} onClick={() => setLines(current => current.filter((_, lineIndex) => lineIndex !== index))}>Remove line {index + 1}</Button>}
        </fieldset>)}
        {validLines && !uniqueLineReferences && <p role="alert" className="text-sm">Each receipt line needs a unique document line reference.</p>}
      </div>
      <Field label="Supplier identity key">{id => <input id={id} required maxLength={200} value={supplierKey} disabled={submitting} onChange={event => setSupplierKey(event.target.value)} className={inputClass} />}</Field>
      <Field label="Source document type">{id => <select id={id} value={documentType} disabled={submitting} onChange={event => setDocumentType(event.target.value)} className={inputClass}><option>DELIVERY_NOTE</option><option>INVOICE</option><option>OTHER</option></select>}</Field>
      <Field label="Source document reference">{id => <input id={id} required maxLength={200} value={documentReference} disabled={submitting} onChange={event => setDocumentReference(event.target.value)} className={inputClass} />}</Field>
      <Field label="Physically received at">{id => <input id={id} type="datetime-local" required value={physicallyReceivedAt} disabled={submitting} onChange={event => setPhysicallyReceivedAt(event.target.value)} className={inputClass} />}</Field>
      <Field label="Reason for receipt">{id => <textarea id={id} required maxLength={500} value={reason} disabled={submitting} onChange={event => setReason(event.target.value)} className={inputClass} />}</Field>
      <Field label="Evidence source link or reference">{id => <input id={id} required maxLength={256} value={evidenceReference} disabled={submitting} onChange={event => setEvidenceReference(event.target.value)} className={inputClass} />}</Field>
      {lines.some((line, index) => line.productId && line.lotId && line.locationId && positions[index]?.isFetching) && <p role="status" className="sm:col-span-2">Checking each current position version before receipt…</p>}
      {lines.every((line, index) => line.productId && line.lotId && line.locationId && positions[index]?.data && !positions[index]?.isError) && <p className="sm:col-span-2 text-sm text-muted-foreground">The document will use the displayed expected position version for every line. Confirm only after comparing each line and its source evidence.</p>}
      <label className="flex items-start gap-3 text-sm sm:col-span-2"><input type="checkbox" checked={confirmed} disabled={submitting} onChange={event => setConfirmed(event.target.checked)} className="mt-1" />I confirmed all quantities are physically present and reviewed the complete source document, lines, reason and evidence.</label>
      <div className="flex flex-wrap gap-3 sm:col-span-2"><Button type="submit" disabled={!readyToSubmit}>{submitting ? 'Preparing and saving receipt…' : 'Confirm physical receipt document'}</Button></div>
      {submissionError && <p role="status" className="sm:col-span-2 rounded-xl border border-border p-4 text-sm">{submissionError} Check the recovery panel above before submitting this receipt again.</p>}
    </form>}
  </section>
}
