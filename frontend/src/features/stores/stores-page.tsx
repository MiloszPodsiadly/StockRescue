import { AppLink } from '@/components/app-link'
import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from '@tanstack/react-router'
import { Button } from '@/components/ui/button'
import { StateMessage } from '@/components/state-message'
import { errorMessage } from '@/api/client'
import { listStores } from '@/api/stores'
import type { StoreState } from '@/api/stores'
import { OrganizationPage } from '../organizations/organization-page'
import { StoreForm } from './store-form'
import { useTenantKey } from '../organizations/tenant-requests'

export function StoresPage() {
  const { organizationId } = useParams({ strict: false })
  return <OrganizationPage organizationId={organizationId}>{organizationId && <StoreList organizationId={organizationId} />}</OrganizationPage>
}
function StoreList({ organizationId }: { organizationId: string }) {
  const client = useQueryClient()
  const [offset, setOffset] = useState(0)
  const [state, setState] = useState<StoreState>('ACTIVE')
  const [draft, setDraft] = useState('')
  const [search, setSearch] = useState('')
  const key = [...useTenantKey(), 'stores'] as const
  const stores = useQuery({ queryKey: [...key, state, search, offset], queryFn: ({ signal }) => listStores(organizationId, state, search, offset, signal), refetchOnWindowFocus: true, refetchInterval: 30000 })
  if (stores.isPending) return <StateMessage state="loading" title="Loading stores" description="Checking current store access." />
  if (stores.isError) return <div className="space-y-4"><StateMessage state="unavailable" title="Stores unavailable" description={errorMessage(stores.error)} /><Button onClick={() => { void stores.refetch() }}>Check store access again</Button></div>
  return <section aria-labelledby="stores-heading" className="space-y-7">
    <div><h2 id="stores-heading" className="text-2xl font-semibold">Stores</h2><p className="mt-3 text-muted-foreground">Store configuration and access for this organization.</p></div>
    <form onSubmit={event => { event.preventDefault(); setSearch(draft.trim()); setOffset(0) }} className="flex flex-wrap items-end gap-3"><label className="min-w-0 flex-1 text-sm font-medium">Search stores<input maxLength={100} value={draft} onChange={event => { setDraft(event.target.value) }} className="mt-2 w-full rounded-xl border border-border bg-background px-4 py-3 text-base" /></label><Button type="submit">Search</Button><label className="min-w-0 text-sm font-medium">Store state<select value={state} onChange={event => { setState(event.target.value as StoreState); setOffset(0) }} className="mt-2 block w-full rounded-xl border border-border bg-background px-4 py-3 text-base"><option value="ACTIVE">Active</option><option value="ARCHIVED">Archived</option><option value="ALL">All states</option></select></label></form>
    <Button variant="outline" disabled={stores.isFetching} onClick={() => { void stores.refetch() }}>Reload stores</Button>
    {stores.isFetching ? <StateMessage state="loading" title="Refreshing stores" description="Checking current values and access." /> : stores.data.items.length === 0 ? <StateMessage state="empty" title="No stores in this view" description="Change the search or filter. Administrators can create stores below; staff see only assigned active stores." /> : <ul className="grid gap-4 sm:grid-cols-2">{stores.data.items.map(store => <li key={store.id} className="min-w-0 rounded-2xl border border-border bg-card p-6"><p className="break-all text-xs font-semibold uppercase tracking-wider text-primary">{store.code}</p><h3 className="mt-3 break-words text-xl font-semibold"><AppLink href={`/organizations/${organizationId}/stores/${store.id}`} className="underline decoration-border underline-offset-4">{store.name}</AppLink></h3><p className="mt-3 break-words text-sm text-muted-foreground">{store.zoneId} · {store.active ? 'Active' : 'Archived'}</p>{store.city && <p className="mt-2 break-words text-sm">{store.city}</p>}</li>)}</ul>}
    {(offset > 0 || stores.data.hasMore) && <div className="flex flex-wrap gap-3"><Button variant="outline" disabled={!offset || stores.isFetching} onClick={() => { setOffset(value => Math.max(0, value - 25)) }}>Previous stores</Button><Button variant="outline" disabled={!stores.data.hasMore || stores.isFetching} onClick={() => { setOffset(value => value + 25) }}>More stores</Button></div>}
    {stores.data.canCreate && <div hidden={stores.isFetching}><StoreForm organizationId={organizationId} onSaved={async () => { await client.invalidateQueries({ queryKey: key }) }} /></div>}
  </section>
}
