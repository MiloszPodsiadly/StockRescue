import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from '@tanstack/react-router'
import { AppLink } from '@/components/app-link'
import { Button } from '@/components/ui/button'
import { Field, inputClass } from '@/components/form-field'
import { archiveProduct, createProduct, getOperatorClassificationConfirmation, getProduct, isValidGtin, listProducts, reviewProductGtin } from '@/api/products'
import type { OperatorClassificationConfirmation, Product, ProductState } from '@/api/products'
import { listLots } from '@/api/lots'
import { listStores } from '@/api/stores'
import { OrganizationPage } from '../organizations/organization-page'
import { useOrganization } from '../organizations/organization-context'
import { catalogRefresh, useCatalogScope, accessFailed } from './catalog-query'
import { Facts, Pager, QueryNotice } from './catalog-ui'
import { DurableForm } from './durable-form'
import { LotForm } from './lot-form'
import { OperatorClassificationForm } from './operator-classification-form'
import { StorePicker } from './store-picker'

export function CatalogPage() {
  const { organizationId } = useParams({ strict: false })
  return <OrganizationPage organizationId={organizationId}>{organizationId && <CatalogExplorer organizationId={organizationId} />}</OrganizationPage>
}
function CatalogExplorer({ organizationId }: { organizationId: string }) {
  const scope = useCatalogScope()
  const navigate = useNavigate()
  const [state, setState] = useState<ProductState>('ACTIVE')
  const [search, setSearch] = useState('')
  const [q, setQ] = useState('')
  const [offset, setOffset] = useState(0)
  const [creating, setCreating] = useState(false)
  const products = useQuery({ queryKey: [...scope, 'products', state, q, offset], queryFn: ({ signal }) => listProducts(organizationId, state, q, offset, signal), ...catalogRefresh })
  async function reload() { const result = await products.refetch(); if (result.error) throw result.error }
  if (!products.data || accessFailed(products.error)) return <QueryNotice title={products.isPending ? 'Loading catalog…' : 'Catalog unavailable'} error={products.error} reload={() => { void products.refetch() }} />
  return <section className="space-y-7">
    <div><p className="text-xs font-semibold uppercase tracking-wider text-primary">Products and provenance</p><h2 className="mt-3 text-3xl font-semibold">Catalog Explorer</h2><p className="mt-3 text-muted-foreground">Find products, preserve their original lot labels and review source evidence.</p></div>
    <form className="grid gap-4 rounded-2xl border border-border p-5 sm:grid-cols-[minmax(0,1fr)_auto_auto]" onSubmit={event => { event.preventDefault(); setQ(search); setOffset(0) }}>
      <Field label="Search SKU, name or GTIN">{id => <input id={id} maxLength={100} value={search} onChange={event => { setSearch(event.target.value) }} className={inputClass} />}</Field>
      <Field label="Product status">{id => <select id={id} value={state} onChange={event => { setState(event.target.value as ProductState); setOffset(0) }} className={inputClass}><option value="ACTIVE">Active</option><option value="ARCHIVED">Archived</option><option value="ALL">All products</option></select>}</Field>
      <Button type="submit" className="self-end">Search catalog</Button>
    </form>
    {products.isError && <QueryNotice title="Catalog refresh failed" error={products.error} reload={() => { void products.refetch() }} />}
    {products.isFetching ? <p role="status">Refreshing catalog…</p> : products.data.items.length === 0 ? <p>No products match this view.</p> : <ul className="grid gap-4 sm:grid-cols-2">{products.data.items.map(product => <li key={product.id} className="min-w-0 space-y-3 rounded-2xl border border-border bg-card p-5"><p className="break-all text-xs font-semibold uppercase tracking-wider text-primary">{product.sku}</p><h3 className="break-words text-xl font-semibold"><AppLink href={`/organizations/${organizationId}/catalog/products/${product.id}`} className="text-primary underline">{product.name}</AppLink></h3><p className="text-sm">{product.category.replaceAll('_', ' ')} · {product.temperatureClass} · {product.baseUnit} · {product.active ? 'Active' : 'Archived'}</p><p className="break-all text-sm text-muted-foreground">GTIN: {product.gtin ?? 'Not supplied'}</p></li>)}</ul>}
    <Pager label="products" offset={offset} hasMore={products.data.hasMore} onChange={setOffset} />
    {products.data.canCreate && !creating && <Button onClick={() => { setCreating(true) }}>Create product</Button>}
    {creating && products.data.canCreate && <ProductForm key={organizationId} organizationId={organizationId} onCancel={() => { setCreating(false) }} onRefresh={reload} onSaved={async product => { await navigate({ to: '/organizations/$organizationId/catalog/products/$productId', params: { organizationId, productId: product.id } }) }} />}
  </section>
}
export function ProductForm({ organizationId, onSaved, onRefresh, onCancel }: { organizationId: string, onSaved: (product: Product) => Promise<void>, onRefresh: () => Promise<void>, onCancel?: () => void }) {
  const scope = useCatalogScope()
  const [sku, setSku] = useState('')
  const [name, setName] = useState('')
  const [gtin, setGtin] = useState('')
  const [debouncedGtin, setDebouncedGtin] = useState('')
  const [category, setCategory] = useState<Product['category']>('UNCLASSIFIED')
  const [temperature, setTemperature] = useState<Product['temperatureClass'] | ''>('')
  const [reviewOffset, setReviewOffset] = useState(0)
  const [visitedPageRevisions, setVisitedPageRevisions] = useState<Map<number, string>>(() => new Map())
  const [reviewedRevision, setReviewedRevision] = useState<string | null>(null)
  const [previewGeneration, setPreviewGeneration] = useState(0)
  const gtinValid = isValidGtin(gtin)
  useEffect(() => {
    const timeout = window.setTimeout(() => { setDebouncedGtin(gtin) }, 250)
    return () => { window.clearTimeout(timeout) }
  }, [gtin])
  const review = useQuery({ queryKey: [...scope, 'gtin-review', debouncedGtin, reviewOffset, previewGeneration],
    queryFn: ({ signal }) => reviewProductGtin(organizationId, debouncedGtin, reviewOffset, signal), enabled: debouncedGtin === gtin && gtinValid, ...catalogRefresh })
  const currentReview = review.data?.gtin === gtin && review.data.offset === reviewOffset ? review.data : undefined
  const currentPageOffset = currentReview?.offset ?? null
  const currentRevision = currentReview?.matchSetRevision ?? null
  const pageOffsets = currentReview ? Array.from({ length: Math.floor(currentReview.offset / currentReview.limit) + 1 }, (_, index) => index * currentReview.limit) : []
  const allMatchesVisited = Boolean(currentReview && !currentReview.hasMore && pageOffsets.every(offset =>
    offset === currentPageOffset || visitedPageRevisions.get(offset) === currentRevision))
  const hasMatches = Boolean(currentReview?.matches.length)
  const matchesAcknowledged = !hasMatches || (allMatchesVisited && currentRevision !== null && reviewedRevision === currentRevision)
  const canSubmit = temperature !== '' && (!gtin || gtinValid && debouncedGtin === gtin && currentReview !== undefined && !review.isError && !review.isFetching && matchesAcknowledged)
  function changeGtin(value: string) {
    setGtin(value); setReviewOffset(0); setVisitedPageRevisions(new Map()); setReviewedRevision(null)
    setPreviewGeneration(generation => generation + 1)
  }
  function changeReviewPage(offset: number) {
    if (currentPageOffset !== null && currentRevision) {
      setVisitedPageRevisions(previous => new Map(previous).set(currentPageOffset, currentRevision))
    }
    setReviewOffset(offset); setReviewedRevision(null)
  }
  function refreshGtinReview() {
    setReviewOffset(0); setVisitedPageRevisions(new Map()); setReviewedRevision(null)
    setPreviewGeneration(generation => generation + 1)
  }
  function prepareProduct() {
    if (temperature === '') throw new Error('Choose the declared temperature class from product evidence.')
    return { requestId: crypto.randomUUID(), sku, name, ...(gtin ? { gtin } : {}),
      ...(hasMatches && currentRevision && reviewedRevision === currentRevision ? { gtinReviewAcknowledged: true, gtinReviewRevision: currentRevision } : {}),
      baseUnit: 'EA' as const, category, temperatureClass: temperature }
  }
  return <DurableForm title="Create product" submitLabel="Save product" canSubmit={canSubmit}
    onPreconditionConflict={code => { if (code === 'GTIN_REVIEW_REQUIRED') refreshGtinReview() }}
    prepare={prepareProduct}
    execute={(body, signal) => createProduct(organizationId, body, signal)} onSaved={onSaved} onRefresh={onRefresh} onCancel={onCancel}>
    <p className="text-sm text-muted-foreground">The initial category and declared storage class are provisional. A source-backed product review is required before the limited safety checks can pass.</p>
    <Field label="SKU" help="A stable product code, unique in this organization, including archived products.">{(id, description) => <input id={id} required maxLength={64} pattern="[A-Za-z0-9][A-Za-z0-9._\/\-]{0,63}" aria-describedby={description} value={sku} onChange={event => { setSku(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Product name">{id => <input id={id} required maxLength={160} value={name} onChange={event => { setName(event.target.value) }} className={inputClass} />}</Field>
    <Field label="GTIN (optional)" help="Copy every digit, including leading zeros.">{(id, description) => <input id={id} inputMode="numeric" pattern="[0-9]{8}|[0-9]{12}|[0-9]{13}|[0-9]{14}" maxLength={14} aria-describedby={description} value={gtin} onChange={event => { changeGtin(event.target.value) }} className={inputClass} />}</Field>
    {gtin && !gtinValid && <p role="alert">Enter an 8, 12, 13 or 14 digit GTIN with a valid check digit.</p>}
    {gtin && gtinValid && debouncedGtin !== gtin && <p role="status">Waiting to check the exact organization matches…</p>}
    {gtin && gtinValid && debouncedGtin === gtin && review.isPending && <p role="status">Checking exact organization matches for this GTIN…</p>}
    {gtin && gtinValid && debouncedGtin === gtin && review.isError && <div role="alert" className="space-y-3 rounded-xl border border-destructive/40 p-4 text-sm"><p>The current GTIN matches could not be checked. Product creation is paused until the check succeeds.</p><Button type="button" variant="outline" onClick={() => { void review.refetch() }}>Retry GTIN check</Button></div>}
    {currentReview && currentReview.matches.length === 0 && reviewOffset === 0 && !review.isError && <p role="status">No active or archived catalog products currently use this GTIN.</p>}
    {currentReview && currentReview.matches.length > 0 && <section aria-label="Potential GTIN matches" role="alert" className="space-y-4 rounded-xl border border-amber-500/60 bg-amber-500/5 p-4">
      <div><h4 className="font-semibold">Potential duplicate GTIN</h4><p className="mt-2 text-sm">Review every exact match before creating a separate product. Matching GTINs can be legitimate, so the catalog will keep both records separate.</p></div>
      <ul className="space-y-2">{currentReview.matches.map(match => <li key={match.productId} className="break-all rounded-lg border border-border p-3"><span className="font-semibold">{match.sku}</span><span className="ml-2 text-sm">{match.status === 'ACTIVE' ? 'Active product' : 'Archived product'}</span><span className="mt-1 block">{match.name}</span><span className="mt-1 block text-xs text-muted-foreground">GTIN: {match.gtin} · Product ID: {match.productId}</span></li>)}</ul>
      <p className="text-sm">Showing matches {currentReview.offset + 1}–{currentReview.offset + currentReview.matches.length}{currentReview.hasMore ? ' and more' : ''} for {currentReview.gtin}.</p>
      <div className="flex flex-wrap gap-3">{currentReview.offset > 0 && <Button type="button" variant="outline" onClick={() => { changeReviewPage(Math.max(0, currentReview.offset - currentReview.limit)) }}>Previous matches</Button>}
        {currentReview.hasMore && currentReview.offset < 10000 && <Button type="button" variant="outline" onClick={() => { changeReviewPage(currentReview.offset + currentReview.limit) }}>Next matches</Button>}</div>
      {allMatchesVisited && currentRevision && <label className="flex items-start gap-3 text-sm"><input type="checkbox" checked={reviewedRevision === currentRevision} onChange={event => { setReviewedRevision(event.target.checked ? currentRevision : null) }} />I reviewed the current matches and still want to create a separate product with this GTIN.</label>}
      {currentReview.hasMore && currentReview.offset >= 10000 && <p role="alert" className="text-sm">More matches exist beyond the review limit. Creation stays blocked until every match can be reviewed.</p>}
    </section>}
    <Field label="Base unit">{id => <input id={id} value="EA" readOnly className={inputClass} />}</Field>
    <Field label="Product category" help="Initial descriptive value only; it remains provisional until confirmed from a cited source.">{id => <select id={id} value={category} onChange={event => { setCategory(event.target.value as Product['category']) }} className={inputClass}><option value="UNCLASSIFIED">Unclassified — category unknown</option><option value="DAIRY">Dairy</option><option value="BAKERY">Bakery</option></select>}</Field>
    <Field label="Declared temperature class" help="Initial storage requirement only; it remains provisional until confirmed from a cited source.">{id => <select id={id} required value={temperature} onChange={event => { setTemperature(event.target.value as Product['temperatureClass'] | '') }} className={inputClass}><option value="">Choose from product evidence</option>{['AMBIENT', 'CHILLED', 'FROZEN'].map(value => <option key={value} value={value}>{value}</option>)}</select>}</Field>
  </DurableForm>
}
export function ProductPage() {
  const { organizationId, productId } = useParams({ strict: false })
  return <OrganizationPage organizationId={organizationId}>{organizationId && productId && <ProductDetail key={productId} organizationId={organizationId} productId={productId} />}</OrganizationPage>
}
function ProductDetail({ organizationId, productId }: { organizationId: string, productId: string }) {
  const scope = useCatalogScope()
  const client = useQueryClient()
  const organization = useOrganization()
  const [archiving, setArchiving] = useState<Product | null>(null)
  const [confirmingClassification, setConfirmingClassification] = useState(false)
  const [registering, setRegistering] = useState(false)
  const [registrationStoreId, setRegistrationStoreId] = useState('')
  const [registrationFrozen, setRegistrationFrozen] = useState(false)
  const key = [...scope, 'product', productId]
  const product = useQuery({ queryKey: key, queryFn: ({ signal }) => getProduct(organizationId, productId, signal), ...catalogRefresh })
  const classificationKey = [...scope, 'operator-classification-confirmation', productId, product.data?.version ?? null]
  const classification = useQuery({ queryKey: classificationKey,
    queryFn: ({ signal }) => getOperatorClassificationConfirmation(organizationId, productId, signal), ...catalogRefresh })
  const storeLotAccess = useQuery({ queryKey: [...scope, 'store-lot-registration-access'], enabled: organization.role === 'MEMBER',
    queryFn: ({ signal }) => listStores(organizationId, 'ACTIVE', '', 0, signal), ...catalogRefresh, refetchOnWindowFocus: true })
  async function reload() {
    const [productResult, classificationResult] = await Promise.all([product.refetch(), classification.refetch()])
    if (productResult.error) throw productResult.error
    if (classificationResult.error) throw classificationResult.error
    setArchiving(null); setConfirmingClassification(false); setRegistering(false); setRegistrationStoreId(''); setRegistrationFrozen(false)
  }
  if (!product.data || accessFailed(product.error)) return <QueryNotice title={product.isPending ? 'Loading product…' : 'Product unavailable'} error={product.error} reload={() => { void product.refetch() }} />
  const current = product.data
  const administrator = organization.role === 'ORG_ADMIN' && current.allowedActions.includes('MANAGE_CATALOG')
  const canRegisterStoreLot = organization.role === 'MEMBER' && storeLotAccess.data?.canRegisterCandidateLots === true
  const classificationEvidence = classification.data
  return <section className="space-y-7"><AppLink href={`/organizations/${organizationId}/catalog`} className="text-primary underline">Back to catalog</AppLink><div><p className="break-all text-xs font-semibold uppercase tracking-wider text-primary">{current.sku}</p><h2 className="mt-3 break-words text-3xl font-semibold">{current.name}</h2></div>
    {product.isError && <QueryNotice title="Product refresh failed" error={product.error} reload={() => { void product.refetch() }} />}
    {organization.role === 'MEMBER' && storeLotAccess.isError && <QueryNotice title="Store access unavailable" error={storeLotAccess.error} reload={() => { void storeLotAccess.refetch() }} />}
    <Facts values={[["GTIN", current.gtin ?? 'Not supplied'], ['Base unit', current.baseUnit], ['Product category', `${current.category.replaceAll('_', ' ')} — descriptive only`], ['Declared temperature class', current.temperatureClass], ['Status', current.active ? 'Active' : 'Archived'], ['Version', current.version], ['Product reference', current.id]]} />
    <Button variant="outline" onClick={() => { void product.refetch() }}>Reload product</Button>
    <section className="space-y-3 rounded-2xl border border-border p-5" aria-label="Product classification evidence">
      <div><h3 className="text-xl font-semibold">Product classification evidence</h3><p className="mt-2 text-sm text-muted-foreground">An operator confirmation records the source you reviewed. It is attributed evidence, not independent verification or a food-safety approval.</p></div>
      {!classificationEvidence || classification.isError ? <QueryNotice title={classification.isPending ? 'Loading classification evidence…' : 'Classification evidence unavailable'} error={classification.error} reload={() => { void classification.refetch() }} /> : <OperatorClassificationStatus confirmation={classificationEvidence} />}
    </section>
    {current.active && (administrator || canRegisterStoreLot) && <div className="flex flex-wrap gap-3">{administrator ? <><Button disabled={registering || archiving !== null || confirmingClassification} onClick={() => { setRegistering(true) }}>Register lot</Button><Button variant="outline" disabled={registering || archiving !== null || confirmingClassification} onClick={() => { setArchiving(current) }}>Archive product</Button><Button variant="outline" disabled={registering || archiving !== null || confirmingClassification} onClick={() => { setConfirmingClassification(true) }}>Confirm product classification</Button></> : <Button disabled={registering || archiving !== null || confirmingClassification} onClick={() => { setRegistering(true) }}>Register candidate lot</Button>}</div>}
    {archiving && <ProductArchive key={archiving.version} organizationId={organizationId} product={archiving} onCancel={() => { setArchiving(null) }} onRefresh={reload} onSaved={async result => { client.setQueryData(key, result); setArchiving(null); await client.invalidateQueries({ queryKey: [...scope, 'products'] }) }} />}
    {confirmingClassification && administrator && current.active && <OperatorClassificationForm key={current.version} organizationId={organizationId} product={current} onCancel={() => { setConfirmingClassification(false) }} onRefresh={reload} onSaved={async result => { client.setQueryData(key, result.product); client.setQueryData([...scope, 'operator-classification-confirmation', productId, result.product.version], result); setConfirmingClassification(false); await client.invalidateQueries({ queryKey: [...scope, 'products'] }); await client.invalidateQueries({ queryKey: [...scope, 'eligibility'] }) }} />}
    {registering && administrator && <LotForm organizationId={organizationId} productId={productId} onRefresh={reload} onCancel={() => { setRegistering(false) }} />}
    {registering && !administrator && canRegisterStoreLot && <div className="space-y-5"><StorePicker organizationId={organizationId} value={registrationStoreId} onChange={setRegistrationStoreId} disabled={registrationFrozen} requiredAction="REGISTER_STORE_LOT" />{registrationStoreId && <LotForm key={registrationStoreId} organizationId={organizationId} productId={productId} storeId={registrationStoreId} onRefresh={reload} onLock={setRegistrationFrozen} onCancel={() => { setRegistrationStoreId('') }} />}</div>}
    <ProductLots organizationId={organizationId} productId={productId} />
  </section>
}
function OperatorClassificationStatus({ confirmation }: { confirmation: OperatorClassificationConfirmation }) {
  const evidence = confirmation.confirmation
  if (!evidence) return <p role="status">No operator classification confirmation has been recorded. Any safety assessment remains subject to its own evidence checks.</p>
  const message = confirmation.status === 'CURRENT_OPERATOR_CONFIRMED' ? 'The cited source was recorded against the current product version.'
    : confirmation.status === 'STALE' ? 'This confirmation refers to an older product version. Review the current source and confirm again.'
      : 'The category, declared temperature or source evidence is incomplete. This confirmation does not satisfy product attribute checks.'
  return <div className="space-y-2 text-sm"><p role="status">{message} It remains attributed operator evidence, not independent verification.</p>
    <dl className="grid gap-2 sm:grid-cols-2"><div><dt className="font-medium">Recorded category</dt><dd>{evidence.confirmedCategory.replaceAll('_', ' ')}</dd></div>
      <div><dt className="font-medium">Recorded temperature class</dt><dd>{evidence.confirmedTemperatureClass}</dd></div>
      <div><dt className="font-medium">Product profile</dt><dd>{evidence.productProfile.replaceAll('_', ' ').toLowerCase()}</dd></div>
      <div><dt className="font-medium">Evidence source</dt><dd>{evidence.sourceType?.replaceAll('_', ' ') ?? 'Not supplied'}</dd></div>
      <div className="break-words"><dt className="font-medium">Source reference</dt><dd>{evidence.evidenceReference ?? 'Not supplied'}</dd></div>
      <div><dt className="font-medium">Recorded</dt><dd>{new Date(evidence.recordedAt).toLocaleString()}</dd></div>
      <div className="break-all"><dt className="font-medium">Recorded by account</dt><dd>{evidence.actorUserId}</dd></div>
      <div><dt className="font-medium">Product version</dt><dd>{evidence.productVersion}</dd></div></dl>
  </div>
}
function ProductArchive({ organizationId, product, onSaved, onRefresh, onCancel }: { organizationId: string, product: Product, onSaved: (product: Product) => Promise<void>, onRefresh: () => Promise<void>, onCancel: () => void }) {
  const [reason, setReason] = useState('')
  return <DurableForm title="Confirm product archival" submitLabel="Confirm product archival" confirmation="I reviewed this product and confirm archival." prepare={() => ({ requestId: crypto.randomUUID(), expectedVersion: product.version, reason })}
    execute={(body, signal) => archiveProduct(organizationId, product.id, body, signal)} onSaved={onSaved} onRefresh={onRefresh} onCancel={onCancel}>
    <p className="text-sm text-muted-foreground">The product code and history are retained. New lot registration will close for this product.</p>
    <Field label="Reason for product archival">{id => <textarea id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
  </DurableForm>
}
function ProductLots({ organizationId, productId }: { organizationId: string, productId: string }) {
  const scope = useCatalogScope()
  const organization = useOrganization()
  const [offset, setOffset] = useState(0)
  const [archived, setArchived] = useState(false)
  const lots = useQuery({ queryKey: [...scope, 'lots', productId, archived, offset], queryFn: ({ signal }) => listLots(organizationId, productId, archived, offset, signal), ...catalogRefresh })
  return <section className="space-y-5" aria-label="Product lots"><h3 className="text-2xl font-semibold">Manufacturer lots</h3><label className="flex gap-3 text-sm"><input type="checkbox" checked={archived} onChange={event => { setArchived(event.target.checked); setOffset(0) }} />Include archived lots</label>
    {!lots.data || lots.isError || lots.isFetching ? <QueryNotice title={lots.isFetching ? 'Refreshing lots…' : 'Lots unavailable'} error={lots.error} reload={() => { void lots.refetch() }} /> : <>{lots.data.items.length === 0 ? <p>No lots recorded for this product.</p> : <ul className="space-y-3">{lots.data.items.map(lot => <li key={lot.id} className="min-w-0 rounded-xl border border-border p-5"><AppLink href={`/organizations/${organizationId}/catalog/lots/${lot.id}`} className="break-all text-lg font-semibold text-primary underline">{lot.lotCode}</AppLink><p className="mt-2 text-sm">{lot.identityState === 'CONFIRMED' ? 'Confirmed manufacturer identity' : 'Candidate manufacturer identity'} · {lot.dateMarking.type} · {lot.dateMarking.precision}</p><p className="mt-2 break-words whitespace-pre-wrap text-sm">Label: {lot.dateMarking.labeledValue ?? 'Not supplied'}</p></li>)}</ul>}<Pager label="lots" offset={offset} hasMore={lots.data.hasMore} onChange={setOffset} /></>}
    {organization.role !== 'ORG_ADMIN' && <p className="text-sm text-muted-foreground">Store Managers can record candidate lot metadata for an assigned store. Manufacturer identity corrections remain limited to catalog administrators.</p>}
  </section>
}
