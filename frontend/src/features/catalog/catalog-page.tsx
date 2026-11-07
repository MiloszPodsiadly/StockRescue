import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from '@tanstack/react-router'
import { AppLink } from '@/components/app-link'
import { Button } from '@/components/ui/button'
import { Field, inputClass } from '@/components/form-field'
import { archiveProduct, correctProductCategory, createProduct, getProduct, listProducts } from '@/api/products'
import type { Product, ProductState, CorrectProductCategory } from '@/api/products'
import { listLots } from '@/api/lots'
import { OrganizationPage } from '../organizations/organization-page'
import { useOrganization } from '../organizations/organization-context'
import { catalogRefresh, useCatalogScope, accessFailed } from './catalog-query'
import { Facts, Pager, QueryNotice } from './catalog-ui'
import { DurableForm } from './durable-form'
import { LotForm } from './lot-form'

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
    {creating && products.data.canCreate && <ProductForm organizationId={organizationId} onCancel={() => { setCreating(false) }} onRefresh={reload} onSaved={async product => { await navigate({ to: '/organizations/$organizationId/catalog/products/$productId', params: { organizationId, productId: product.id } }) }} />}
  </section>
}
export function ProductForm({ organizationId, onSaved, onRefresh, onCancel }: { organizationId: string, onSaved: (product: Product) => Promise<void>, onRefresh: () => Promise<void>, onCancel?: () => void }) {
  const [sku, setSku] = useState('')
  const [name, setName] = useState('')
  const [gtin, setGtin] = useState('')
  const [category, setCategory] = useState<Product['category']>('UNCLASSIFIED')
  const [temperature, setTemperature] = useState<Product['temperatureClass']>('AMBIENT')
  return <DurableForm title="Create product" submitLabel="Save product" confirmation="I confirm this descriptive category; if the category is unknown, I will keep it unclassified." prepare={() => ({ requestId: crypto.randomUUID(), sku, name, ...(gtin ? { gtin } : {}), baseUnit: 'EA' as const, category, temperatureClass: temperature })}
    execute={(body, signal) => createProduct(organizationId, body, signal)} onSaved={onSaved} onRefresh={onRefresh} onCancel={onCancel}>
    <Field label="SKU" help="A stable product code, unique in this organization, including archived products.">{(id, description) => <input id={id} required maxLength={64} pattern="[A-Za-z0-9][A-Za-z0-9._\/\-]{0,63}" aria-describedby={description} value={sku} onChange={event => { setSku(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Product name">{id => <input id={id} required maxLength={160} value={name} onChange={event => { setName(event.target.value) }} className={inputClass} />}</Field>
    <Field label="GTIN (optional)" help="Copy every digit, including leading zeros.">{(id, description) => <input id={id} inputMode="numeric" pattern="[0-9]{8}|[0-9]{12}|[0-9]{13}|[0-9]{14}" maxLength={14} aria-describedby={description} value={gtin} onChange={event => { setGtin(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Base unit">{id => <input id={id} value="EA" readOnly className={inputClass} />}</Field>
    <Field label="Product category" help="This is a descriptive grouping, not a legal classification or food-safety approval.">{id => <select id={id} value={category} onChange={event => { setCategory(event.target.value as Product['category']) }} className={inputClass}><option value="UNCLASSIFIED">Unclassified — category unknown</option><option value="DAIRY">Dairy</option><option value="BAKERY">Bakery</option></select>}</Field>
    <Field label="Declared temperature class">{id => <select id={id} value={temperature} onChange={event => { setTemperature(event.target.value as Product['temperatureClass']) }} className={inputClass}>{['AMBIENT', 'CHILLED', 'FROZEN'].map(value => <option key={value}>{value}</option>)}</select>}</Field>
  </DurableForm>
}
export function ProductPage() {
  const { organizationId, productId } = useParams({ strict: false })
  return <OrganizationPage organizationId={organizationId}>{organizationId && productId && <ProductDetail key={productId} organizationId={organizationId} productId={productId} />}</OrganizationPage>
}
function ProductDetail({ organizationId, productId }: { organizationId: string, productId: string }) {
  const scope = useCatalogScope()
  const client = useQueryClient()
  const [archiving, setArchiving] = useState<Product | null>(null)
  const [reclassifying, setReclassifying] = useState(false)
  const [registering, setRegistering] = useState(false)
  const key = [...scope, 'product', productId]
  const product = useQuery({ queryKey: key, queryFn: ({ signal }) => getProduct(organizationId, productId, signal), ...catalogRefresh })
  async function reload() { const result = await product.refetch(); if (result.error) throw result.error; setArchiving(null); setReclassifying(false); setRegistering(false) }
  if (!product.data || accessFailed(product.error)) return <QueryNotice title={product.isPending ? 'Loading product…' : 'Product unavailable'} error={product.error} reload={() => { void product.refetch() }} />
  const current = product.data
  return <section className="space-y-7"><AppLink href={`/organizations/${organizationId}/catalog`} className="text-primary underline">Back to catalog</AppLink><div><p className="break-all text-xs font-semibold uppercase tracking-wider text-primary">{current.sku}</p><h2 className="mt-3 break-words text-3xl font-semibold">{current.name}</h2></div>
    {product.isError && <QueryNotice title="Product refresh failed" error={product.error} reload={() => { void product.refetch() }} />}
    <Facts values={[["GTIN", current.gtin ?? 'Not supplied'], ['Base unit', current.baseUnit], ['Product category', `${current.category.replaceAll('_', ' ')} — descriptive only`], ['Declared temperature class', current.temperatureClass], ['Status', current.active ? 'Active' : 'Archived'], ['Version', current.version], ['Product reference', current.id]]} />
    <Button variant="outline" onClick={() => { void product.refetch() }}>Reload product</Button>
    {current.active && current.allowedActions.includes('MANAGE_CATALOG') && <div className="flex flex-wrap gap-3"><Button disabled={registering || archiving !== null || reclassifying} onClick={() => { setRegistering(true) }}>Register lot</Button><Button variant="outline" disabled={registering || archiving !== null || reclassifying} onClick={() => { setArchiving(current) }}>Archive product</Button><Button variant="outline" disabled={registering || archiving !== null || reclassifying} onClick={() => { setReclassifying(true) }}>Correct product category</Button></div>}
    {archiving && <ProductArchive key={archiving.version} organizationId={organizationId} product={archiving} onCancel={() => { setArchiving(null) }} onRefresh={reload} onSaved={async result => { client.setQueryData(key, result); setArchiving(null); await client.invalidateQueries({ queryKey: [...scope, 'products'] }) }} />}
    {reclassifying && <ProductCategoryCorrection key={current.version} organizationId={organizationId} product={current} onCancel={() => { setReclassifying(false) }} onRefresh={reload} onSaved={async result => { client.setQueryData(key, result); setReclassifying(false); await client.invalidateQueries({ queryKey: [...scope, 'products'] }); await client.invalidateQueries({ queryKey: [...scope, 'eligibility'] }) }} />}
    {registering && <LotForm organizationId={organizationId} productId={productId} onRefresh={reload} onCancel={() => { setRegistering(false) }} />}
    <ProductLots organizationId={organizationId} productId={productId} />
  </section>
}
function ProductCategoryCorrection({ organizationId, product, onSaved, onRefresh, onCancel }: { organizationId: string, product: Product, onSaved: (product: Product) => Promise<void>, onRefresh: () => Promise<void>, onCancel: () => void }) {
  const [category, setCategory] = useState<Product['category']>(product.category)
  const [reason, setReason] = useState('')
  return <DurableForm title="Correct product category" submitLabel="Save category correction" confirmation="I reviewed the product evidence and confirm this category correction." prepare={() => ({ requestId: crypto.randomUUID(), expectedVersion: product.version, category, reason })}
    execute={(body, signal) => correctProductCategory(organizationId, product.id, body satisfies CorrectProductCategory, signal)} onSaved={onSaved} onRefresh={onRefresh} onCancel={onCancel}>
    <p className="text-sm text-muted-foreground">Changing a category selects a different policy scope. It does not approve the product or change its storage class.</p>
    <Field label="Correct product category">{id => <select id={id} value={category} onChange={event => { setCategory(event.target.value as Product['category']) }} className={inputClass}><option value="UNCLASSIFIED">Unclassified — category unknown</option><option value="DAIRY">Dairy</option><option value="BAKERY">Bakery</option></select>}</Field>
    <Field label="Reason for category correction">{id => <textarea id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
  </DurableForm>
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
    {organization.role !== 'ORG_ADMIN' && <p className="text-sm text-muted-foreground">Lot identity registration and correction require catalog management access.</p>}
  </section>
}
