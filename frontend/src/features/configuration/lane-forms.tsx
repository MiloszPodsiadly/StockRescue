import { AppLink } from '@/components/app-link'
import { useState } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { errorMessage } from '@/api/client'
import { listStores } from '@/api/stores'
import { listZones } from '@/api/configuration'
import type { CostRevision, Lane, LaneCreation, LaneUpdate } from '@/api/configuration'
import { panelClass } from './settings-fields'
import { Field, inputClass } from '@/components/form-field'
import { useTenantKey } from '../organizations/tenant-requests'

export function LaneSetupForm({ organizationId, lane, draft, saving, onSubmit }: { organizationId: string, lane?: Lane, draft?: LaneUpdate, saving: boolean, onSubmit: (body: LaneCreation | LaneUpdate) => void }) {
  const [origin, setOrigin] = useState(lane?.originStoreId ?? '')
  const [destination, setDestination] = useState(lane?.destinationStoreId ?? '')
  const [originZone, setOriginZone] = useState(draft?.originZoneId ?? lane?.originZoneId ?? '')
  const [destinationZone, setDestinationZone] = useState(draft?.destinationZoneId ?? lane?.destinationZoneId ?? '')
  const [active, setActive] = useState(draft?.active ?? lane?.active ?? true)
  const [reason, setReason] = useState(draft?.reason ?? '')
  return <form className={panelClass} onSubmit={event => {
    event.preventDefault()
    if (saving) return
    onSubmit(lane ? { originZoneId: active ? originZone : lane.originZoneId ?? undefined, destinationZoneId: active ? destinationZone : lane.destinationZoneId ?? undefined, active, version: lane.version, reason } : { originStoreId: origin, destinationStoreId: destination, originZoneId: originZone, destinationZoneId: destinationZone, reason })
  }}>
    <h3 className="text-xl font-semibold">{lane ? 'Edit lane configuration' : 'Create a transport lane'}</h3>
    {lane ? <p className="break-words text-sm">{lane.originStoreName} → {lane.destinationStoreName}. Store endpoints stay fixed.</p> : <div className="grid gap-5 sm:grid-cols-2"><StoreSelect organizationId={organizationId} label="Origin store" value={origin} excluded={destination} disabled={saving} onChange={value => { setOrigin(value); setOriginZone('') }} /><StoreSelect organizationId={organizationId} label="Destination store" value={destination} excluded={origin} disabled={saving} onChange={value => { setDestination(value); setDestinationZone('') }} /></div>}
    {lane && <label className="flex gap-3 text-sm"><input type="checkbox" checked={active} disabled={saving} onChange={event => { setActive(event.target.checked) }} />Lane enabled for new configuration</label>}
    {active && <div className="grid gap-5 sm:grid-cols-2"><ZoneSelect key={`origin:${origin}`} organizationId={organizationId} storeId={origin} label="Origin zone" value={originZone} disabled={saving} onChange={setOriginZone} /><ZoneSelect key={`destination:${destination}`} organizationId={organizationId} storeId={destination} label="Destination zone" value={destinationZone} disabled={saving} onChange={setDestinationZone} /></div>}
    <p className="text-sm text-muted-foreground">Select active zones with the same temperature class. Changing zones requires a new cost revision. A configured lane does not confirm vehicle or transport capacity.</p>
    <Field label="Reason for lane configuration">{id => <textarea id={id} required maxLength={500} value={reason} disabled={saving} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
    <Button type="submit" disabled={saving || active && (!originZone || !destinationZone)}>{saving ? 'Saving lane…' : lane ? 'Save lane configuration' : 'Create lane'}</Button>
  </form>
}
function StoreSelect({ organizationId, label, value, excluded, disabled, onChange }: { organizationId: string, label: string, value: string, excluded: string, disabled: boolean, onChange: (value: string) => void }) {
  const scope = useTenantKey()
  const stores = useInfiniteQuery({ queryKey: [...scope, 'lane-store-options'], initialPageParam: 0, queryFn: ({ signal, pageParam }) => listStores(organizationId, 'ACTIVE', '', pageParam, signal), getNextPageParam: (page, pages) => page.hasMore ? pages.reduce((sum, item) => sum + item.items.length, 0) : undefined })
  return <div className="space-y-2"><Field label={label}>{id => <select id={id} required value={value} disabled={disabled || stores.isPending} onChange={event => { onChange(event.target.value) }} className={inputClass}><option value="">Select a store</option>{stores.data?.pages.flatMap(page => page.items).filter(store => store.id !== excluded).map(store => <option key={store.id} value={store.id}>{store.name} ({store.code})</option>)}</select>}</Field>{stores.isError && <p role="alert" className="text-sm text-destructive">{errorMessage(stores.error)}</p>}{(stores.hasNextPage || stores.isError) && <Button type="button" variant="outline" disabled={stores.isFetching} onClick={() => { if (stores.hasNextPage) void stores.fetchNextPage(); else void stores.refetch() }}>Load more stores</Button>}</div>
}
function ZoneSelect({ organizationId, storeId, label, value, disabled, onChange }: { organizationId: string, storeId: string, label: string, value: string, disabled: boolean, onChange: (value: string) => void }) {
  const scope = useTenantKey()
  const zones = useInfiniteQuery({ queryKey: [...scope, 'zone-options', storeId], enabled: Boolean(storeId), initialPageParam: 0, queryFn: ({ signal, pageParam }) => listZones(organizationId, storeId, false, pageParam, signal), getNextPageParam: (page, pages) => page.hasMore ? pages.reduce((sum, item) => sum + item.items.length, 0) : undefined })
  const items = zones.data?.pages.flatMap(page => page.items) ?? []
  return <div className="space-y-2"><Field label={label}>{id => <select id={id} required value={value} disabled={disabled || !storeId || zones.isPending} onChange={event => { onChange(event.target.value) }} className={inputClass}><option value="">Select an active zone</option>{value && !items.some(zone => zone.id === value) && <option value={value}>Previously selected zone — check active zones</option>}{items.map(zone => <option key={zone.id} value={zone.id}>{zone.name} · {zone.temperatureClass}</option>)}</select>}</Field>{storeId && zones.isSuccess && !items.length && <p className="text-sm text-muted-foreground">No active zones. <AppLink className="text-primary underline" href={`/organizations/${organizationId}/stores/${storeId}/storage-zones`}>Configure this store's zones</AppLink>.</p>}{zones.isError && <p role="alert" className="text-sm text-destructive">{errorMessage(zones.error)}</p>}{(zones.hasNextPage || zones.isError) && <Button type="button" variant="outline" disabled={zones.isFetching} onClick={() => { if (zones.hasNextPage) void zones.fetchNextPage(); else void zones.refetch() }}>Load more zones</Button>}</div>
}

export function CostRevisionForm({ lane, codes, saving, onSubmit }: { lane: Lane, codes: string[], saving: boolean, onSubmit: (body: CostRevision) => void }) {
  const [amount, setAmount] = useState('')
  const [currency, setCurrency] = useState('')
  const [duration, setDuration] = useState('')
  const [scheduled, setScheduled] = useState(false)
  const [effectiveAt, setEffectiveAt] = useState('')
  const [reason, setReason] = useState('')
  return <form className={panelClass} onSubmit={event => {
    event.preventDefault()
    if (saving) return
    onSubmit({ laneVersion: lane.version, expectedRevision: lane.latestRevision, costAmount: amount, currencyCode: currency, durationMinutes: Number(duration), ...(scheduled ? { effectiveAt: new Date(`${effectiveAt}Z`).toISOString() } : {}), reason })
  }}>
    <h3 className="text-xl font-semibold">Approve a new cost revision</h3><p className="text-sm text-muted-foreground">Declared cost for one journey on this directed lane, with its duration in whole minutes. Each approval preserves the earlier values and records your membership. No profitability is calculated.</p>
    <div className="grid gap-5 sm:grid-cols-3">
      <Field label="Cost amount" help="Exact decimal; up to 15 whole digits and 4 decimal places. Zero is allowed only when explicitly entered.">{(id, description) => <input id={id} aria-describedby={description} required inputMode="decimal" pattern="(0|[1-9][0-9]{0,14})(\.[0-9]{1,4})?" maxLength={20} value={amount} disabled={saving} onChange={event => { setAmount(event.target.value) }} className={inputClass} />}</Field>
      <Field label="Currency">{id => <select id={id} required value={currency} disabled={saving} onChange={event => { setCurrency(event.target.value) }} className={inputClass}><option value="">Select currency</option>{codes.map(code => <option key={code}>{code}</option>)}</select>}</Field>
      <Field label="Journey duration (minutes)">{id => <input id={id} required type="number" min={1} max={2147483647} step={1} value={duration} disabled={saving} onChange={event => { setDuration(event.target.value) }} className={inputClass} />}</Field>
    </div>
    <label className="flex gap-3 text-sm"><input type="checkbox" checked={scheduled} disabled={saving} onChange={event => { setScheduled(event.target.checked) }} />Schedule a future revision in UTC</label>
    {scheduled && <Field label="Effective time (UTC)" help="Must be in the future and later than the latest approved revision.">{(id, description) => <input id={id} aria-describedby={description} type="datetime-local" step={1} required value={effectiveAt} disabled={saving} onChange={event => { setEffectiveAt(event.target.value) }} className={inputClass} />}</Field>}
    <p className="break-words text-sm text-muted-foreground">{lane.latestEffectiveAt ? `Latest approved effective time: ${lane.latestEffectiveAt}. ` : 'No approved revision yet. '}{!scheduled && 'Effective time will be the server approval time.'}</p>
    <Field label="Reason for cost revision">{id => <textarea id={id} required maxLength={500} value={reason} disabled={saving} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
    <Button type="submit" disabled={saving || !codes.length}>{saving ? 'Approving revision…' : 'Approve cost revision'}</Button>
  </form>
}
