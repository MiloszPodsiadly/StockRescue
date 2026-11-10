import { useCallback, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from '@tanstack/react-router'
import { createStorageLocation, renameStorageLocation, archiveStorageLocation, listStorageLocations } from '@/api/storage-locations'
import type { StorageLocation } from '@/api/storage-locations'
import { getStore } from '@/api/stores'
import { listZones } from '@/api/configuration'
import { AppLink } from '@/components/app-link'
import { Field, inputClass } from '@/components/form-field'
import { Button } from '@/components/ui/button'
import { OrganizationPage } from '../organizations/organization-page'
import { accessFailed, catalogRefresh, useCatalogScope } from './catalog-query'
import { Pager, QueryNotice } from './catalog-ui'
import { DurableForm } from './durable-form'

export function LocationsPage() {
  const { organizationId, storeId } = useParams({ strict: false })
  return <OrganizationPage organizationId={organizationId}>{organizationId && storeId && <LocationsStore organizationId={organizationId} storeId={storeId} />}</OrganizationPage>
}
function LocationsStore({ organizationId, storeId }: { organizationId: string, storeId: string }) {
  const scope = useCatalogScope()
  const store = useQuery({ queryKey: [...scope, 'store', storeId], queryFn: ({ signal }) => getStore(organizationId, storeId, signal), ...catalogRefresh })
  if (!store.data || accessFailed(store.error)) return <QueryNotice title={store.isPending ? 'Loading location context…' : 'Store unavailable'} error={store.error} reload={() => { void store.refetch() }} />
  return <section className="space-y-7"><AppLink href={`/organizations/${organizationId}/stores/${storeId}`} className="text-primary underline">{store.data.name}</AppLink><h2 className="text-3xl font-semibold">Storage locations</h2><LocationsPanel key={storeId} organizationId={organizationId} storeId={storeId} /></section>
}
export function LocationsPanel({ organizationId, storeId, onLock }: { organizationId: string, storeId: string, onLock?: (locked: boolean) => void }) {
  const scope = useCatalogScope()
  const client = useQueryClient()
  const [offset, setOffset] = useState(0)
  const [archived, setArchived] = useState(false)
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState<{ location: StorageLocation, archive: boolean } | null>(null)
  const [locked, setLocked] = useState(false)
  const lock = useCallback((value: boolean) => { setLocked(value); onLock?.(value) }, [onLock])
  const locations = useQuery({ queryKey: [...scope, 'locations', storeId, archived, offset], queryFn: ({ signal }) => listStorageLocations(organizationId, storeId, archived, '', offset, signal), ...catalogRefresh })
  async function reload() {
    await client.invalidateQueries({ queryKey: [...scope, 'locations', storeId] }, { throwOnError: true })
    await client.invalidateQueries({ queryKey: [...scope, 'storage-choices', storeId] })
    await client.invalidateQueries({ queryKey: [...scope, 'eligibility'] })
    setCreating(false); setEditing(null)
  }
  if (!locations.data || accessFailed(locations.error)) return <QueryNotice title={locations.isPending ? 'Loading locations…' : 'Locations unavailable'} error={locations.error} reload={() => { void locations.refetch() }} />
  return <section aria-label="Storage location configuration" className="space-y-5"><h3 className="text-2xl font-semibold">Location configuration</h3><p className="text-sm text-muted-foreground">Codes and zone assignments stay fixed. Configuration describes declared storage compatibility.</p>
    <label className="flex gap-3 text-sm"><input type="checkbox" checked={archived} onChange={event => { setArchived(event.target.checked); setOffset(0) }} />Include archived locations</label>
    {locations.isError && <QueryNotice title="Location refresh failed" error={locations.error} reload={() => { void locations.refetch() }} />}
    {locations.isFetching ? <p role="status">Refreshing locations…</p> : locations.data.items.length === 0 ? <p>No locations recorded in this view.</p> : <ul className="space-y-4">{locations.data.items.map(location => <li key={location.id} className="min-w-0 space-y-3 rounded-xl border border-border p-5"><h4 className="break-words text-lg font-semibold">{location.code} · {location.name}</h4><p className="text-sm">{location.temperatureClass} · {location.active ? 'Active' : 'Archived'} · {location.usable ? 'Configuration usable' : 'Configuration unusable'} · Version {location.version}</p><p className="break-all text-xs text-muted-foreground">Location reference: {location.id}</p>
      {locations.data.canManage && location.active && <div className="flex flex-wrap gap-3"><Button variant="outline" disabled={locked} onClick={() => { setCreating(false); setEditing({ location, archive: false }) }}>Rename location {location.code}</Button><Button variant="outline" disabled={locked} onClick={() => { setCreating(false); setEditing({ location, archive: true }) }}>Archive location {location.code}</Button></div>}
    </li>)}</ul>}
    <Pager label="locations" offset={offset} hasMore={locations.data.hasMore} onChange={setOffset} />
    {locations.data.canManage && !creating && !editing && <Button onClick={() => { setCreating(true) }}>Create storage location</Button>}
    {creating && (locations.data.canManage || locked) && <NewLocation organizationId={organizationId} storeId={storeId} onSaved={reload} onRefresh={reload} onLock={lock} />}
    {editing && <LocationEdit key={`${editing.location.id}:${editing.location.version}:${editing.archive}`} organizationId={organizationId} storeId={storeId} location={editing.location} archive={editing.archive} onSaved={reload} onRefresh={reload} onLock={lock} />}
  </section>
}
function NewLocation({ organizationId, storeId, onSaved, onRefresh, onLock }: { organizationId: string, storeId: string, onSaved: () => Promise<void>, onRefresh: () => Promise<void>, onLock: (locked: boolean) => void }) {
  const scope = useCatalogScope()
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [zoneId, setZoneId] = useState('')
  const [offset, setOffset] = useState(0)
  const zones = useQuery({ queryKey: [...scope, 'location-zones', storeId, offset], queryFn: ({ signal }) => listZones(organizationId, storeId, false, offset, signal), ...catalogRefresh })
  return <DurableForm title="Create storage location" submitLabel="Save storage location" canSubmit={Boolean(zoneId)} onLock={onLock}
    prepare={() => ({ requestId: crypto.randomUUID(), code, name, zoneId })} execute={(body, signal) => createStorageLocation(organizationId, storeId, body, signal)} onSaved={onSaved} onRefresh={onRefresh}>
    <Field label="Location code">{id => <input id={id} required maxLength={32} pattern="[A-Za-z0-9][A-Za-z0-9_\-]{0,31}" value={code} onChange={event => { setCode(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Location name">{id => <input id={id} required maxLength={160} value={name} onChange={event => { setName(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Location zone">{id => <select id={id} required value={zoneId} onChange={event => { setZoneId(event.target.value) }} className={inputClass}><option value="">Choose an active zone</option>{zoneId && !zones.data?.items.some(zone => zone.id === zoneId) && <option value={zoneId}>Previously selected zone — refresh to check status</option>}{zones.data?.items.map(zone => <option key={zone.id} value={zone.id}>{zone.name} · {zone.temperatureClass}</option>)}</select>}</Field>
    {zones.isError && <p role="alert">Active zones could not be refreshed.</p>}{zones.data && <Pager label="location zones" offset={offset} hasMore={zones.data.hasMore} onChange={setOffset} size={100} />}
    <AppLink href={`/organizations/${organizationId}/stores/${storeId}/storage-zones`} className="text-sm text-primary underline">Configure storage zones</AppLink>
  </DurableForm>
}
function LocationEdit({ organizationId, storeId, location, archive, onSaved, onRefresh, onLock }: { organizationId: string, storeId: string, location: StorageLocation, archive: boolean, onSaved: () => Promise<void>, onRefresh: () => Promise<void>, onLock: (locked: boolean) => void }) {
  const [name, setName] = useState(location.name)
  const [reason, setReason] = useState('')
  return <DurableForm title={archive ? `Review archival of ${location.code}` : `Rename ${location.code}`} submitLabel={archive ? 'Confirm location archival' : 'Save location name'} onLock={onLock}
    confirmation={archive ? 'I reviewed this location and confirm archival.' : undefined}
    prepare={() => ({ requestId: crypto.randomUUID(), expectedVersion: location.version, name, reason })}
    execute={(body, signal) => archive ? archiveStorageLocation(organizationId, storeId, location.id, { requestId: body.requestId, expectedVersion: body.expectedVersion, reason: body.reason }, signal) : renameStorageLocation(organizationId, storeId, location.id, body, signal)} onSaved={onSaved} onRefresh={onRefresh}>
    {!archive && <Field label="Updated location name">{id => <input id={id} required maxLength={160} value={name} onChange={event => { setName(event.target.value) }} className={inputClass} />}</Field>}
    <Field label="Reason for location change">{id => <textarea id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
  </DurableForm>
}
