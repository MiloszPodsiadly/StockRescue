import { AppLink } from '@/components/app-link'
import { useLayoutEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from '@tanstack/react-router'
import { Button } from '@/components/ui/button'
import { StateMessage } from '@/components/state-message'
import { errorMessage } from '@/api/client'
import { archiveStore, getStore } from '@/api/stores'
import type { Store } from '@/api/stores'
import { OrganizationPage } from '../organizations/organization-page'
import { useTenantKey, useTenantRequests } from '../organizations/tenant-requests'
import { StoreForm } from './store-form'

export function StorePage() {
  const { organizationId, storeId } = useParams({ strict: false })
  return <OrganizationPage organizationId={organizationId}>{organizationId && storeId && <StoreDetail key={storeId} organizationId={organizationId} storeId={storeId} />}</OrganizationPage>
}
function StoreDetail({ organizationId, storeId }: { organizationId: string, storeId: string }) {
  const client = useQueryClient()
  const scope = useTenantKey()
  const [editing, setEditing] = useState(false)
  const [deactivating, setDeactivating] = useState(false)
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const tenantSignal = useTenantRequests()
  const request = useRef<AbortController | null>(null)
  useLayoutEffect(() => () => { request.current?.abort() }, [])
  const key = [...scope, 'store', storeId] as const
  const store = useQuery({ queryKey: key, queryFn: ({ signal }) => getStore(organizationId, storeId, signal), refetchOnWindowFocus: true, refetchInterval: 30000 })
  async function saved(result: Store) {
    setEditing(false); setDeactivating(false); setReason(''); setConfirmed(false)
    client.setQueryData(key, result)
    await client.invalidateQueries({ queryKey: [...scope, 'stores'] })
  }
  const archive = useMutation({ mutationKey: [...key, 'archive'], retry: false, gcTime: 0, mutationFn: async () => {
    if (!store.data || !confirmed) throw new Error('Confirm store deactivation')
    const controller = new AbortController(); request.current = controller
    return archiveStore(organizationId, store.data, reason, AbortSignal.any([tenantSignal(), controller.signal]))
  }, onSuccess: saved })
  if (store.isPending) return <StateMessage state="loading" title="Loading store" description="Checking your current store access." />
  if (store.isError) return <div className="space-y-4"><StateMessage state="unavailable" title="Store unavailable" description={errorMessage(store.error)} /><Button onClick={() => { void store.refetch() }}>Check store access again</Button></div>
  const current = store.data
  return <section className="space-y-7">
    <div><p className="break-all text-xs font-semibold uppercase tracking-wider text-primary">{current.code}</p><h2 className="mt-3 break-words text-3xl font-semibold">{current.name}</h2><p className="mt-3 text-sm text-muted-foreground">{current.active ? 'Active' : 'Archived'} · {current.zoneId}</p></div>
    <Button variant="outline" disabled={store.isFetching || archive.isPending} onClick={() => { setEditing(false); setDeactivating(false); void store.refetch() }}>Reload saved store</Button>
    <AppLink href={`/organizations/${organizationId}/stores/${storeId}/storage-zones`} className="inline-flex text-primary underline">Storage zones</AppLink>
    {store.isFetching ? <StateMessage state="loading" title="Refreshing store" description="Confirming current data and permissions." /> : <><dl className="grid gap-5 rounded-xl border border-border p-5 sm:grid-cols-2">{[['Address', current.addressLine1], ['City', current.city], ['Postal code', current.postalCode], ['Country', current.countryCode]].map(([label, value]) => <div key={label}><dt className="text-sm text-muted-foreground">{label}</dt><dd className="mt-2 break-words">{value || 'Not provided'}</dd></div>)}</dl><div className="flex flex-wrap gap-3">{current.allowedActions.includes('EDIT_STORE') && <Button disabled={archive.isPending} onClick={() => { setEditing(value => !value); setDeactivating(false) }}>Edit store</Button>}{current.allowedActions.includes('ARCHIVE_STORE') && <Button variant="outline" disabled={archive.isPending} onClick={() => { setDeactivating(value => !value); setEditing(false); archive.reset() }}>Deactivate store</Button>}</div></>}
    {editing && current.allowedActions.includes('EDIT_STORE') && <div hidden={store.isFetching}><StoreForm key={current.version} organizationId={organizationId} store={current} onSaved={saved} /></div>}
    {archive.isError && <p role="alert" className="rounded-xl border border-destructive/30 p-4 text-sm text-destructive">{errorMessage(archive.error)} Reload the saved store before retrying an uncertain result.</p>}
    {deactivating && current.allowedActions.includes('ARCHIVE_STORE') && <form onSubmit={event => { event.preventDefault(); if (confirmed && !archive.isPending) archive.mutate() }} className="space-y-5 rounded-xl border border-border p-5"><h3 className="text-xl font-semibold">Confirm store deactivation</h3><p className="text-sm text-muted-foreground">This closes the store to new operations. Its identity, code and history remain available to administrators.</p><label className="block text-sm font-medium">Reason for deactivation<textarea required maxLength={500} value={reason} disabled={archive.isPending} onChange={event => { setReason(event.target.value) }} className="mt-2 w-full rounded-xl border border-border bg-background px-4 py-3 text-base" /></label><label className="flex items-start gap-3 text-sm"><input type="checkbox" required checked={confirmed} disabled={archive.isPending} onChange={event => { setConfirmed(event.target.checked) }} className="mt-1" />I confirm this store should be deactivated.</label><Button type="submit" disabled={!confirmed || archive.isPending}>{archive.isPending ? 'Deactivating store…' : 'Confirm deactivation'}</Button></form>}
    <AppLink href={`/organizations/${organizationId}/stores`} className="inline-flex text-primary underline">Back to stores</AppLink>
  </section>
}
