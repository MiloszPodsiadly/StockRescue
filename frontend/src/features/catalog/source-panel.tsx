import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { registerSourceEvidence, traceLotSources } from '@/api/lots'
import type { Store } from '@/api/stores'
import { Field, inputClass } from '@/components/form-field'
import { catalogRefresh, useCatalogScope } from './catalog-query'
import { Facts, Pager, Provenance, QueryNotice } from './catalog-ui'
import { DurableForm } from './durable-form'

export function SourcePanel({ organizationId, lotId }: { organizationId: string, lotId: string }) {
  const scope = useCatalogScope()
  const [offset, setOffset] = useState(0)
  const trace = useQuery({ queryKey: [...scope, 'sources', lotId, offset], queryFn: ({ signal }) => traceLotSources(organizationId, lotId, offset, signal), ...catalogRefresh })
  return <section aria-label="Source traceability" className="space-y-5"><h3 className="text-2xl font-semibold">Source observations</h3><p className="text-sm text-muted-foreground">Each observation is independent source evidence. It does not identify the exact origin of every physical unit.</p>
    {!trace.data || trace.isError || trace.isFetching ? <QueryNotice title={trace.isFetching ? 'Refreshing source visibility…' : 'Sources unavailable'} error={trace.error} reload={() => { void trace.refetch() }} /> : <><p className="text-sm">{trace.data.visibilityScope === 'ALL_ORGANIZATION_SOURCES' ? 'Showing recorded organization sources.' : 'Showing recorded sources for your currently assigned active stores.'}</p>
      {trace.data.items.length === 0 ? <p>No source observations are visible in this scope.</p> : <div className="space-y-4">{trace.data.items.map(source => <article key={source.id} className="space-y-3"><h4 className="break-words text-lg font-semibold">{source.supplierName ?? 'Supplier not supplied'}</h4><Facts values={[
        ['Supplier reference', source.supplierReference ?? 'Unknown'], ['Source evidence', <Provenance value={source.provenance} />], ['Store reference', source.storeId ?? 'Organization-level observation'], ['Observation reference', source.id], ['Recorded at', source.createdAt],
      ]} /></article>)}</div>}<Pager label="sources" offset={offset} hasMore={trace.data.hasMore} onChange={setOffset} /></>}
  </section>
}
export function SourceForm({ organizationId, lotId, store, onLock }: { organizationId: string, lotId: string, store?: Store, onLock: (value: boolean) => void }) {
  const [supplier, setSupplier] = useState('')
  const [supplierReference, setSupplierReference] = useState('')
  const [source, setSource] = useState('')
  const [reference, setReference] = useState('')
  const client = useQueryClient()
  const scope = useCatalogScope()
  const [revision, setRevision] = useState(0)
  // Replacing the form is deliberate only after confirmed success, never after a background trace refresh.
  return <SourceEntry key={revision} organizationId={organizationId} lotId={lotId} store={store} source={source} reference={reference} supplier={supplier} supplierReference={supplierReference}
    setSource={setSource} setReference={setReference} setSupplier={setSupplier} setSupplierReference={setSupplierReference} onLock={onLock}
    onRefresh={async () => { await client.invalidateQueries({ queryKey: scope }, { throwOnError: true }) }}
    onSaved={async () => { await client.invalidateQueries({ queryKey: [...scope, 'sources', lotId] }, { throwOnError: true }); setSupplier(''); setSupplierReference(''); setSource(''); setReference(''); setRevision(value => value + 1) }} />
}
function SourceEntry({ organizationId, lotId, store, supplier, supplierReference, source, reference, setSupplier, setSupplierReference, setSource, setReference, onSaved, onRefresh, onLock }: {
  organizationId: string, lotId: string, store?: Store, supplier: string, supplierReference: string, source: string, reference: string,
  setSupplier: (value: string) => void, setSupplierReference: (value: string) => void, setSource: (value: string) => void, setReference: (value: string) => void,
  onSaved: () => Promise<void>, onRefresh: () => Promise<void>, onLock: (locked: boolean) => void,
}) {
  return <DurableForm title="Record source observation" submitLabel="Save source observation" onLock={onLock}
    prepare={() => ({ requestId: crypto.randomUUID(), ...(store ? { storeId: store.id } : {}), ...(supplier ? { supplierName: supplier } : {}), ...(supplierReference ? { supplierReference } : {}), provenance: { source, ...(reference ? { reference } : {}) } })}
    execute={(body, signal) => registerSourceEvidence(organizationId, lotId, body, signal)} onSaved={onSaved} onRefresh={onRefresh}>
    <p className="text-sm text-muted-foreground">Observation scope: {store ? `${store.code} · ${store.name}` : 'Organization-level source'}.</p>
    <Field label="Supplier name (optional)">{id => <input id={id} maxLength={160} value={supplier} onChange={event => { setSupplier(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Supplier reference (optional)">{id => <input id={id} maxLength={256} value={supplierReference} onChange={event => { setSupplierReference(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Observation source">{id => <input id={id} required maxLength={256} value={source} onChange={event => { setSource(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Observation document reference">{id => <input id={id} maxLength={256} value={reference} onChange={event => { setReference(event.target.value) }} className={inputClass} />}</Field>
  </DurableForm>
}
