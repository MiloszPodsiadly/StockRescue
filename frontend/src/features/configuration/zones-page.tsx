import { AppLink } from '@/components/app-link'
import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from '@tanstack/react-router'
import { Button } from '@/components/ui/button'
import { StateMessage } from '@/components/state-message'
import { errorMessage } from '@/api/client'
import { getStore } from '@/api/stores'
import { archiveZone, createZone, listZones, renameZone } from '@/api/configuration'
import type { Temperature, Zone } from '@/api/configuration'
import { OrganizationPage } from '../organizations/organization-page'
import { useTenantCommand } from '../organizations/use-tenant-command'
import { useTenantKey } from '../organizations/tenant-requests'
import { CommandError, panelClass } from './settings-fields'
import { Field, inputClass } from '@/components/form-field'

export function ZonesPage() {
  const { organizationId, storeId } = useParams({ strict: false })
  return <OrganizationPage organizationId={organizationId}>{organizationId && storeId && <Zones key={storeId} organizationId={organizationId} storeId={storeId} />}</OrganizationPage>
}
function Zones({ organizationId, storeId }: { organizationId: string, storeId: string }) {
  const client = useQueryClient()
  const scope = useTenantKey()
  const [includeInactive, setIncludeInactive] = useState(false)
  const [offset, setOffset] = useState(0)
  const [notice, setNotice] = useState('')
  const store = useQuery({ queryKey: [...scope, 'store', storeId], queryFn: ({ signal }) => getStore(organizationId, storeId, signal), refetchInterval: 30000 })
  const zones = useQuery({ queryKey: [...scope, 'zones', storeId, includeInactive, offset], queryFn: ({ signal }) => listZones(organizationId, storeId, includeInactive, offset, signal), refetchInterval: 30000 })
  async function reload() { await client.invalidateQueries({ queryKey: [...scope, 'zones', storeId] }) }
  async function saved() { setNotice('Storage zone configuration saved.'); await reload() }
  if (store.isPending || zones.isPending) return <StateMessage state="loading" title="Loading storage zones" description="Checking this store's configuration and your access." />
  if (store.isError || zones.isError) return <div className="space-y-4"><StateMessage state="unavailable" title="Storage zones unavailable" description={errorMessage(store.error ?? zones.error)} /><Button onClick={() => { void store.refetch(); void zones.refetch() }}>Reload saved zones</Button></div>
  return <section className="space-y-7">
    <div><AppLink href={`/organizations/${organizationId}/stores/${storeId}`} className="text-primary underline">{store.data.name}</AppLink><h2 className="mt-3 text-3xl font-semibold">Storage zones</h2><p className="mt-3 text-muted-foreground">Temperature classes declare compatibility. They do not verify temperature, available space or transport capacity. A zone's class stays fixed; deactivation is permanent.</p></div>
    {notice && <p role="status">{notice}</p>}
    <div className="flex flex-wrap items-center gap-4"><label className="flex gap-2 text-sm"><input type="checkbox" checked={includeInactive} onChange={event => { setIncludeInactive(event.target.checked); setOffset(0) }} />Include inactive zones</label><Button variant="outline" disabled={zones.isFetching} onClick={() => { void reload() }}>Reload saved zones</Button></div>
    {zones.isFetching && <StateMessage state="loading" title="Refreshing zones" description="Checking saved values." />}
    <div hidden={zones.isFetching} className="space-y-7">
      {zones.data.canManage && <NewZone organizationId={organizationId} storeId={storeId} onSaved={saved} />}
      {!zones.data.items.length && <StateMessage state="empty" title="No storage zones" description="This store has no zones matching the current filter. Compatibility is unconfigured." />}
      <div className="space-y-4">{zones.data.items.map(zone => <ZoneCard key={`${zone.id}:${zone.version}`} zone={zone} canManage={zones.data.canManage} organizationId={organizationId} storeId={storeId} onSaved={saved} />)}</div>
      <div className="flex gap-3"><Button variant="outline" disabled={!offset} onClick={() => { setOffset(value => Math.max(0, value - 100)) }}>Previous zones</Button><Button variant="outline" disabled={!zones.data.hasMore} onClick={() => { setOffset(value => value + 100) }}>Next zones</Button></div>
    </div>
  </section>
}
function NewZone({ organizationId, storeId, onSaved }: { organizationId: string, storeId: string, onSaved: () => Promise<void> }) {
  const [name, setName] = useState('')
  const [temperature, setTemperature] = useState<Temperature>('AMBIENT')
  const command = useTenantCommand(async () => { setName(''); await onSaved() })
  return <form className={panelClass} onSubmit={event => { event.preventDefault(); if (!command.isPending) command.mutate(signal => createZone(organizationId, storeId, name, temperature, signal)) }}>
    <h3 className="text-xl font-semibold">Create a storage zone</h3>
    <Field label="Zone name">{id => <input id={id} required maxLength={100} value={name} disabled={command.isPending} onChange={event => { setName(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Temperature class">{id => <select id={id} value={temperature} disabled={command.isPending} onChange={event => { setTemperature(event.target.value as Temperature) }} className={inputClass}>{(['AMBIENT', 'CHILLED', 'FROZEN'] as const).map(value => <option key={value}>{value}</option>)}</select>}</Field>
    {command.isError && <CommandError error={command.error} />}
    <Button type="submit" disabled={command.isPending}>{command.isPending ? 'Saving zone…' : 'Create zone'}</Button>
  </form>
}
function ZoneCard({ zone, canManage, organizationId, storeId, onSaved }: { zone: Zone, canManage: boolean, organizationId: string, storeId: string, onSaved: () => Promise<void> }) {
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(zone.name)
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const command = useTenantCommand(onSaved)
  return <article className={panelClass}>
    <h3 className="break-words text-xl font-semibold">{zone.name}</h3><p className="text-sm text-muted-foreground">{zone.temperatureClass} · {zone.active ? 'Active' : 'Inactive'} · Version {zone.version}</p>
    {canManage && zone.active && <Button variant="outline" onClick={() => { setEditing(value => !value) }} disabled={command.isPending}>Edit {zone.name}</Button>}
    {editing && <form className="space-y-5" onSubmit={event => { event.preventDefault(); if (!command.isPending) command.mutate(signal => renameZone(organizationId, storeId, zone, name, reason, signal)) }}>
      <Field label="Updated zone name">{id => <input id={id} required maxLength={100} value={name} disabled={command.isPending} onChange={event => { setName(event.target.value) }} className={inputClass} />}</Field>
      <Field label="Reason for zone change">{id => <textarea id={id} required maxLength={500} value={reason} disabled={command.isPending} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
      <Button type="submit" disabled={command.isPending}>Save zone name</Button>
      <label className="flex items-start gap-3 text-sm"><input type="checkbox" checked={confirmed} disabled={command.isPending} onChange={event => { setConfirmed(event.target.checked) }} />Permanently deactivate this zone. Routes using it will be unavailable.</label>
      <Button type="button" variant="outline" disabled={!confirmed || !reason.trim() || command.isPending} onClick={() => { command.mutate(signal => archiveZone(organizationId, storeId, zone, reason, signal)) }}>Deactivate zone</Button>
    </form>}
    {command.isError && <CommandError error={command.error} />}
  </article>
}
