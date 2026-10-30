import { AppLink } from '@/components/app-link'
import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from '@tanstack/react-router'
import { Button } from '@/components/ui/button'
import { StateMessage } from '@/components/state-message'
import { errorMessage } from '@/api/client'
import { createLane, getLane, laneHistory, listLanes, reviseLane, supportedCurrencies, updateLane } from '@/api/configuration'
import type { Lane, LaneCreation, LaneUpdate } from '@/api/configuration'
import { OrganizationPage } from '../organizations/organization-page'
import { useTenantCommand } from '../organizations/use-tenant-command'
import { useTenantKey } from '../organizations/tenant-requests'
import { CostRevisionForm, LaneSetupForm } from './lane-forms'
import { CommandError, panelClass } from './settings-fields'

export function LanesPage() {
  const { organizationId } = useParams({ strict: false })
  return <OrganizationPage organizationId={organizationId}>{organizationId && <Lanes organizationId={organizationId} />}</OrganizationPage>
}
function Lanes({ organizationId }: { organizationId: string }) {
  const client = useQueryClient()
  const scope = useTenantKey()
  const [offset, setOffset] = useState(0)
  const [notice, setNotice] = useState('')
  const [createdLane, setCreatedLane] = useState<Lane | null>(null)
  const lanes = useQuery({ queryKey: [...scope, 'lanes', offset], queryFn: ({ signal }) => listLanes(organizationId, offset, signal), refetchInterval: 30000 })
  const command = useTenantCommand(async () => { setNotice('Transport lane saved. Add a cost revision to finish its configuration.'); await client.invalidateQueries({ queryKey: [...scope, 'lanes'] }) })
  if (lanes.isPending) return <StateMessage state="loading" title="Loading transport lanes" description="Checking organization configuration and your access." />
  if (lanes.isError) return <div className="space-y-4"><StateMessage state="unavailable" title="Transport lanes unavailable" description={errorMessage(lanes.error)} /><Button onClick={() => { void lanes.refetch() }}>Reload saved lanes</Button></div>
  return <section className="space-y-7">
    <div><h2 className="text-3xl font-semibold">Transport lanes</h2><p className="mt-3 text-muted-foreground">Directed routes between this organization's stores. An absent route or cost is unconfigured. These settings do not establish available vehicles, space or permission to ship goods.</p></div>
    {notice && <p role="status">{notice}</p>}
    {createdLane && <AppLink className="inline-flex text-primary underline" href={`/organizations/${organizationId}/transport-lanes/${createdLane.id}`}>Open saved lane</AppLink>}
    <Button variant="outline" disabled={lanes.isFetching || command.isPending} onClick={() => { command.reset(); void lanes.refetch() }}>Reload saved lanes</Button>
    {command.isError && <CommandError error={command.error} />}
    {lanes.isFetching && <StateMessage state="loading" title="Refreshing transport lanes" description="Checking saved configuration." />}
    <div hidden={lanes.isFetching} className="space-y-7">
      <LaneSetupForm key={createdLane?.id ?? 'new'} organizationId={organizationId} saving={command.isPending} onSubmit={body => { setNotice(''); command.mutate(async signal => { const saved = await createLane(organizationId, body as LaneCreation, signal); setCreatedLane(saved) }) }} />
      {!lanes.data.items.length && <StateMessage state="empty" title="No transport lanes" description="Routes, costs and journey durations are unconfigured." />}
      <div className="space-y-4">{lanes.data.items.map(lane => <article key={lane.id} className={panelClass}><h3 className="break-words text-xl font-semibold"><AppLink className="text-primary underline" href={`/organizations/${organizationId}/transport-lanes/${lane.id}`}>{lane.originStoreName} → {lane.destinationStoreName}</AppLink></h3><LaneStatus lane={lane} /></article>)}</div>
      <div className="flex gap-3"><Button variant="outline" disabled={!offset} onClick={() => { setOffset(value => Math.max(0, value - 25)) }}>Previous lanes</Button><Button variant="outline" disabled={!lanes.data.hasMore} onClick={() => { setOffset(value => value + 25) }}>Next lanes</Button></div>
    </div>
  </section>
}
const missingLabels: Record<Lane['missing'][number], string> = {
  NO_LANE: 'No route is configured.', INACTIVE_LANE: 'This lane is inactive.', INACTIVE_STORE: 'A store is inactive.', MISSING_ZONES: 'Storage zones are missing.', INACTIVE_ZONE: 'A selected zone is inactive.', INCOMPATIBLE_ZONES: 'The selected zones have incompatible temperature classes.', NO_EFFECTIVE_COST: 'No cost revision is effective yet. Cost and duration are unavailable.', COST_ZONE_MISMATCH: 'The current zones differ from the cost evidence. Approve a new cost revision.',
}
function LaneStatus({ lane }: { lane: Lane }) {
  return <div className="space-y-3"><p className="text-sm font-semibold">{lane.status}</p>{lane.missing.map(value => <p key={value} className="text-sm text-muted-foreground">{missingLabels[value]}</p>)}{lane.currentCost ? <p className="break-words">Current cost: <strong>{lane.currentCost.costAmount} {lane.currentCost.currencyCode}</strong> · {lane.currentCost.durationMinutes} minutes · Revision {lane.currentCost.revision}</p> : <p className="text-sm">Current cost and duration: unavailable.</p>}<p className="break-words text-xs text-muted-foreground">Configuration checked at {lane.asOf}. Latest approved revision: {lane.latestRevision}.</p></div>
}
export function LanePage() {
  const { organizationId, laneId } = useParams({ strict: false })
  return <OrganizationPage organizationId={organizationId}>{organizationId && laneId && <LaneDetail key={laneId} organizationId={organizationId} laneId={laneId} />}</OrganizationPage>
}
function LaneDetail({ organizationId, laneId }: { organizationId: string, laneId: string }) {
  const client = useQueryClient()
  const scope = useTenantKey()
  const [offset, setOffset] = useState(0)
  const [notice, setNotice] = useState('')
  const root = [...scope, 'lane', laneId] as const
  const lane = useQuery({ queryKey: [...root, 'detail'], queryFn: ({ signal }) => getLane(organizationId, laneId, signal), refetchInterval: 30000 })
  const history = useQuery({ queryKey: [...root, 'history', offset], queryFn: ({ signal }) => laneHistory(organizationId, laneId, offset, signal) })
  const currencies = useQuery({ queryKey: [...scope, 'lane-currencies'], queryFn: ({ signal }) => supportedCurrencies(organizationId, signal) })
  async function reload() { await Promise.all([client.invalidateQueries({ queryKey: root }), client.invalidateQueries({ queryKey: [...scope, 'lanes'] })]) }
  const command = useTenantCommand(async () => { setNotice('Configuration saved. The approved history remains available below.'); await reload() })
  if (lane.isPending || history.isPending || currencies.isPending) return <StateMessage state="loading" title="Loading lane configuration" description="Checking costs, history and your access." />
  if (lane.isError || history.isError || currencies.isError) return <div className="space-y-4"><StateMessage state="unavailable" title="Lane configuration unavailable" description={errorMessage(lane.error ?? history.error ?? currencies.error)} /><Button onClick={() => { void reload(); void currencies.refetch() }}>Reload saved configuration</Button></div>
  const current = lane.data
  const refreshing = lane.isFetching || history.isFetching
  return <section className="space-y-7">
    <div><AppLink href={`/organizations/${organizationId}/transport-lanes`} className="text-primary underline">All transport lanes</AppLink><h2 className="mt-3 break-words text-3xl font-semibold">{current.originStoreName} → {current.destinationStoreName}</h2></div>
    {notice && <p role="status">{notice}</p>}
    <Button variant="outline" disabled={refreshing || command.isPending} onClick={() => { command.reset(); void reload() }}>Reload saved configuration</Button>
    {command.isError && <CommandError error={command.error} />}
    {refreshing && <StateMessage state="loading" title="Refreshing configuration" description="Checking saved revisions." />}
    <div hidden={refreshing} className="space-y-7">
      <div className={panelClass}><LaneStatus lane={current} /></div>
      <LaneSetupForm key={`setup:${current.version}`} organizationId={organizationId} lane={current} saving={command.isPending} onSubmit={body => { setNotice(''); command.mutate(signal => updateLane(organizationId, laneId, body as LaneUpdate, signal)) }} />
      {current.active && !current.missing.some(value => ['INACTIVE_STORE', 'MISSING_ZONES', 'INACTIVE_ZONE', 'INCOMPATIBLE_ZONES'].includes(value)) && <CostRevisionForm key={`cost:${current.version}`} lane={current} codes={currencies.data.codes} saving={command.isPending} onSubmit={body => { setNotice(''); command.mutate(signal => reviseLane(organizationId, laneId, body, signal)) }} />}
      <section aria-labelledby="cost-history-heading" className="space-y-5"><h3 id="cost-history-heading" className="text-2xl font-semibold">Cost history</h3><p className="text-sm text-muted-foreground">Validity starts at the recorded effective time and ends just before the next revision takes effect. Future approvals do not change today's cost. Zone identifiers preserve each revision's original compatibility evidence.</p>
        {!history.data.items.length && <p>No cost revisions have been approved.</p>}
        {history.data.items.map(revision => <article key={revision.id} className={panelClass}><h4 className="text-lg font-semibold">Revision {revision.revision}</h4><p className="break-words font-medium">{revision.costAmount} {revision.currencyCode} · {revision.durationMinutes} minutes</p><dl className="grid gap-4 text-sm sm:grid-cols-2">{[['Effective from (UTC)', revision.effectiveAt], ['Effective until (exclusive, UTC)', revision.validUntil ?? 'No later revision approved'], ['Approved at (UTC)', revision.recordedAt], ['Approving membership', revision.recordedByMembershipId], ['Origin zone', revision.originZoneId ?? 'Unconfigured legacy evidence'], ['Destination zone', revision.destinationZoneId ?? 'Unconfigured legacy evidence'], ['Declared temperature class', revision.temperatureClass ?? 'Unconfigured legacy evidence']].map(([label, value]) => <div key={label}><dt className="text-muted-foreground">{label}</dt><dd className="mt-1 break-all">{value}</dd></div>)}</dl></article>)}
        <div className="flex gap-3"><Button variant="outline" disabled={!offset} onClick={() => { setOffset(value => Math.max(0, value - 25)) }}>Newer revisions</Button><Button variant="outline" disabled={!history.data.hasMore} onClick={() => { setOffset(value => value + 25) }}>Older revisions</Button></div>
      </section>
    </div>
  </section>
}
