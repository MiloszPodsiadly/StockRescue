import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from '@tanstack/react-router'
import { getLot, listLotCorrections } from '@/api/lots'
import type { ManufacturerLot } from '@/api/lots'
import { getProduct } from '@/api/products'
import { getStore } from '@/api/stores'
import { AppLink } from '@/components/app-link'
import { Button } from '@/components/ui/button'
import { OrganizationPage } from '../organizations/organization-page'
import { useOrganization } from '../organizations/organization-context'
import { accessFailed, catalogRefresh, useCatalogScope } from './catalog-query'
import { DateMarking, Facts, Pager, Provenance, QueryNotice } from './catalog-ui'
import { LotForm } from './lot-form'
import { SourcePanel, SourceForm } from './source-panel'
import { StorePicker } from './store-picker'
import { LocationsPanel } from './locations-panel'
import { PolicyPanel, SafetyEvidenceForm } from './policy-panel'
import { EligibilityPanel } from './eligibility-panel'

export function LotPage() {
  const { organizationId, lotId } = useParams({ strict: false })
  return <OrganizationPage organizationId={organizationId}>{organizationId && lotId && <LotDetail key={lotId} organizationId={organizationId} lotId={lotId} />}</OrganizationPage>
}
function LotDetail({ organizationId, lotId }: { organizationId: string, lotId: string }) {
  const scope = useCatalogScope()
  const client = useQueryClient()
  const organization = useOrganization()
  const [editing, setEditing] = useState<ManufacturerLot | null>(null)
  const [safetyEditing, setSafetyEditing] = useState<ManufacturerLot | null>(null)
  const [storeId, setStoreId] = useState('')
  const [sourceFrozen, setSourceFrozen] = useState(false)
  const [locationFrozen, setLocationFrozen] = useState(false)
  const lot = useQuery({ queryKey: [...scope, 'lot', lotId], queryFn: ({ signal }) => getLot(organizationId, lotId, signal), ...catalogRefresh })
  const productId = lot.data?.productId ?? ''
  const product = useQuery({ queryKey: [...scope, 'product', productId], enabled: productId !== '' && !accessFailed(lot.error), queryFn: ({ signal }) => getProduct(organizationId, productId, signal), ...catalogRefresh })
  async function reload() {
    await client.invalidateQueries({ queryKey: [...scope, 'lot', lotId] }, { throwOnError: true })
    await client.invalidateQueries({ queryKey: [...scope, 'product', productId] }, { throwOnError: true })
    await client.invalidateQueries({ queryKey: [...scope, 'corrections', lotId] }, { throwOnError: true })
    await client.invalidateQueries({ queryKey: [...scope, 'eligibility'] })
    setEditing(null); setSafetyEditing(null)
  }
  if (!lot.data || accessFailed(lot.error) || !product.data || accessFailed(product.error)) return <QueryNotice title={lot.isPending || product.isPending ? 'Loading lot context…' : 'Lot unavailable'} error={lot.error ?? product.error} reload={() => { void lot.refetch(); if (productId) void product.refetch() }} />
  const current = lot.data
  const administrator = organization.role === 'ORG_ADMIN'
  const editable = administrator && current.active && product.data.active
  return <section className="space-y-8"><AppLink href={`/organizations/${organizationId}/catalog/products/${productId}`} className="text-primary underline">{product.data.name}</AppLink><div><p className="text-xs font-semibold uppercase tracking-wider text-primary">Manufacturer lot</p><h2 className="mt-3 break-all text-3xl font-semibold">{current.lotCode}</h2></div>
    {(lot.isError || product.isError) && <QueryNotice title="Lot context refresh failed" error={lot.error ?? product.error} reload={() => { void reload().catch(() => {}) }} />}
    <Facts values={[["Manufacturer identity", current.identityState === 'CONFIRMED' ? 'Confirmed' : 'Candidate — unverified'], ['Manufacturer reference', current.manufacturerIdentityKey ?? 'Unknown'], ['Manufacturer evidence', <Provenance value={current.manufacturerProvenance} />], ['Lot status', current.active ? 'Active' : 'Archived'], ['Lot version', current.version], ['Recall evidence', current.recallState], ['Damage evidence', current.damageState], ['Safety-status source', <Provenance value={current.statusProvenance} />]]} />
    <DateMarking value={current.dateMarking} />
    <Button variant="outline" onClick={() => { void client.invalidateQueries({ queryKey: scope }) }}>Reload lot evidence</Button>
    {editable && <div className="flex flex-wrap gap-3"><Button variant="outline" disabled={editing !== null || safetyEditing !== null} onClick={() => { setEditing(current) }}>Review metadata correction</Button><Button variant="outline" disabled={editing !== null || safetyEditing !== null} onClick={() => { setSafetyEditing(current) }}>Record safety notice</Button></div>}
    {editing && <LotForm organizationId={organizationId} productId={productId} lot={editing} onRefresh={reload} onSaved={reload} onCancel={() => { setEditing(null) }} />}
    {safetyEditing && <SafetyEvidenceForm organizationId={organizationId} lot={safetyEditing} onRefresh={reload} onSaved={reload} />}
    <CorrectionHistory organizationId={organizationId} lotId={lotId} />
    <SourcePanel organizationId={organizationId} lotId={lotId} />
    <StorePicker organizationId={organizationId} value={storeId} onChange={setStoreId} disabled={sourceFrozen || locationFrozen} />
    {storeId ? <StoreEvidence key={storeId} organizationId={organizationId} storeId={storeId} lot={current} productActive={product.data.active} sourceFrozen={sourceFrozen} onSourceLock={setSourceFrozen} onLocationLock={setLocationFrozen} />
      : administrator && ((current.active && product.data.active) || sourceFrozen) ? <SourceForm key="organization-source" organizationId={organizationId} lotId={lotId} onLock={setSourceFrozen} /> : <p className="text-sm text-muted-foreground">Choose an assigned store to record source evidence and assess its storage context.</p>}
    <PolicyPanel organizationId={organizationId} category={product.data.category} temperature={product.data.temperatureClass} />
  </section>
}
function StoreEvidence({ organizationId, storeId, lot, productActive, sourceFrozen, onSourceLock, onLocationLock }: {
  organizationId: string, storeId: string, lot: ManufacturerLot, productActive: boolean, sourceFrozen: boolean,
  onSourceLock: (value: boolean) => void, onLocationLock: (value: boolean) => void,
}) {
  const scope = useCatalogScope()
  const store = useQuery({ queryKey: [...scope, 'store', storeId], queryFn: ({ signal }) => getStore(organizationId, storeId, signal), ...catalogRefresh })
  if (!store.data || accessFailed(store.error)) return <QueryNotice title={store.isPending ? 'Checking store context…' : 'Store context unavailable'} error={store.error} reload={() => { void store.refetch() }} />
  return <section className="space-y-8" aria-label="Selected store evidence"><h3 className="break-words text-2xl font-semibold">Store context · {store.data.name}</h3>{store.isError && <QueryNotice title="Store refresh failed" error={store.error} reload={() => { void store.refetch() }} />}
    {((store.data.active && store.data.allowedActions.includes('REGISTER_SOURCE_EVIDENCE') && lot.active && productActive) || sourceFrozen) && <SourceForm organizationId={organizationId} lotId={lot.id} store={store.data} onLock={onSourceLock} />}
    <LocationsPanel organizationId={organizationId} storeId={storeId} onLock={onLocationLock} />
    <EligibilityPanel organizationId={organizationId} lot={lot} store={store.data} />
  </section>
}
function CorrectionHistory({ organizationId, lotId }: { organizationId: string, lotId: string }) {
  const scope = useCatalogScope()
  const [offset, setOffset] = useState(0)
  const history = useQuery({ queryKey: [...scope, 'corrections', lotId, offset], queryFn: ({ signal }) => listLotCorrections(organizationId, lotId, offset, signal), ...catalogRefresh })
  return <section aria-label="Lot evidence history" className="space-y-4"><h3 className="text-2xl font-semibold">Evidence history</h3>
    {!history.data || history.isError || history.isFetching ? <QueryNotice title={history.isFetching ? 'Refreshing evidence history…' : 'Evidence history unavailable'} error={history.error} reload={() => { void history.refetch() }} /> : <>
      {history.data.items.length === 0 && <p>No corrections or safety notices recorded.</p>}
      {history.data.items.map(item => <details key={item.id} className="min-w-0 space-y-4 rounded-xl border border-border p-5"><summary className="cursor-pointer break-words font-semibold">Version {item.resultVersion} · {item.kind === 'METADATA' ? 'Metadata correction' : 'Safety status evidence'}</summary><p className="mt-4 break-words">{item.reason}</p><Facts values={[
        ['Recorded at', item.recordedAt], ['Acting membership', item.actorMembershipId], ['Previous raw label', <span className="whitespace-pre-wrap">{item.before.dateMarking.labeledValue ?? 'Not supplied'}</span>], ['Revised raw label', <span className="whitespace-pre-wrap">{item.after.dateMarking.labeledValue ?? 'Not supplied'}</span>],
        ['Previous manufacturer source', <Provenance value={item.before.manufacturerProvenance} />], ['Revised manufacturer source', <Provenance value={item.after.manufacturerProvenance} />], ['Previous label evidence', <Provenance value={item.before.dateMarking.provenance} />], ['Revised label evidence', <Provenance value={item.after.dateMarking.provenance} />],
        ['Recall evidence before / after', `${item.before.recallState} / ${item.after.recallState}`], ['Damage evidence before / after', `${item.before.damageState} / ${item.after.damageState}`], ['Recorded safety source', <Provenance value={item.after.statusProvenance} />],
      ]} /></details>)}<Pager label="corrections" offset={offset} hasMore={history.data.hasMore} onChange={setOffset} />
    </>}
  </section>
}
