import { AppLink } from '@/components/app-link'
import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from '@tanstack/react-router'
import { Button } from '@/components/ui/button'
import { StateMessage } from '@/components/state-message'
import { errorMessage } from '@/api/client'
import { createLane, listLanes } from '@/api/configuration'
import type { Lane, LaneCreation, Revision } from '@/api/configuration'
import { OrganizationPage } from '../organizations/organization-page'
import { useTenantCommand } from '../organizations/use-tenant-command'
import { useTenantKey } from '../organizations/tenant-requests'
import { CostRevisionForm, LaneSetupForm } from './lane-forms'
import { CommandError, panelClass } from './settings-fields'
import { ScheduleCancellationReview } from './scheduled-cost-review'
import { isAfterInstant } from './scheduled-cost-history'
import { useLaneControl } from './use-lane-control'

export function LanesPage() {
  const { organizationId } = useParams({ strict: false })
  return <OrganizationPage organizationId={organizationId}>{organizationId && <Lanes organizationId={organizationId} />}</OrganizationPage>
}

function Lanes({ organizationId }: { organizationId: string }) {
  const client = useQueryClient()
  const scope = useTenantKey()
  const [offset, setOffset] = useState(0)
  const [createdLane, setCreatedLane] = useState<Lane | null>(null)
  const lanes = useQuery({ queryKey: [...scope, 'lanes', offset], queryFn: ({ signal }) => listLanes(organizationId, offset, signal), refetchInterval: 30000 })
  const command = useTenantCommand(async () => { await client.invalidateQueries({ queryKey: [...scope, 'lanes'] }) })
  if (lanes.isPending) return <StateMessage state="loading" title="Loading transport lanes" description="Checking organization configuration and your access." />
  if (lanes.isError) return <div className="space-y-4"><StateMessage state="unavailable" title="Transport lanes unavailable" description={errorMessage(lanes.error)} /><Button onClick={() => { void lanes.refetch() }}>Reload saved lanes</Button></div>
  return <section className="space-y-7">
    <div><h2 className="text-3xl font-semibold">Transport lanes</h2><p className="mt-3 text-muted-foreground">Directed routes between this organization's stores. An absent route or cost is unconfigured. These settings do not establish available vehicles, space or permission to ship goods.</p></div>
    {command.isSuccess && <p role="status">Transport lane saved. Add a cost revision to finish its configuration.</p>}
    {createdLane && <AppLink className="inline-flex text-primary underline" href={`/organizations/${organizationId}/transport-lanes/${createdLane.id}`}>Open saved lane</AppLink>}
    <Button variant="outline" disabled={lanes.isFetching || command.isPending} onClick={() => { command.reset(); void lanes.refetch() }}>Reload saved lanes</Button>
    {command.isError && <CommandError error={command.error} />}
    {lanes.isFetching && <StateMessage state="loading" title="Refreshing transport lanes" description="Checking saved configuration." />}
    <div hidden={lanes.isFetching} className="space-y-7">
      <LaneSetupForm key={createdLane?.id ?? 'new'} organizationId={organizationId} saving={command.isPending} onSubmit={body => { command.mutate(async signal => { const saved = await createLane(organizationId, body as LaneCreation, signal); setCreatedLane(saved) }) }} />
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
  return <div className="space-y-3"><p className="text-sm font-semibold">{lane.status}</p>{lane.missing.map(value => <p key={value} className="text-sm text-muted-foreground">{missingLabels[value]}</p>)}{lane.currentCost ? <p className="break-words">Current compatible effective cost: <strong>{lane.currentCost.costAmount} {lane.currentCost.currencyCode}</strong> · {lane.currentCost.durationMinutes} minutes · Revision {lane.currentCost.revision}</p> : <p className="text-sm">Current cost and duration: unavailable.</p>}<p className="break-words text-xs text-muted-foreground">Configuration checked at {lane.asOf}. Latest approved revision: {lane.latestRevision}.</p></div>
}

export function LanePage() {
  const { organizationId, laneId } = useParams({ strict: false })
  return <OrganizationPage organizationId={organizationId}>{organizationId && laneId && <LaneDetail key={`${organizationId}:${laneId}`} organizationId={organizationId} laneId={laneId} />}</OrganizationPage>
}

function LaneDetail({ organizationId, laneId }: { organizationId: string, laneId: string }) {
  const control = useLaneControl(organizationId, laneId)
  const { state, lane, history, currencies, busy, refreshing } = control
  if (lane.isPending || history.isPending || currencies.isPending) return <StateMessage state="loading" title="Loading lane configuration" description="Checking costs, history and your access." />
  if (state.phase === 'NEEDS_RECONCILIATION') return <div className="space-y-4"><StateMessage state="unavailable" title="Lane state needs reconciliation" description="A write may have completed, but the latest lane and history could not be confirmed. No further lane changes are available until they are reloaded." />{state.error !== null && <CommandError error={state.error} />}<Button disabled={busy} onClick={() => { void control.reconcileUncertainResult() }}>{busy ? 'Reloading lane and history…' : 'Reload lane and history before continuing'}</Button></div>
  if (lane.isError || history.isError || currencies.isError) return <div className="space-y-4"><StateMessage state="unavailable" title="Lane configuration unavailable" description={errorMessage(lane.error ?? history.error ?? currencies.error)} /><Button disabled={busy} onClick={() => { void control.reload() }}>Reload saved configuration</Button></div>
  const current = lane.data
  const review = state.review
  return <section className="space-y-7">
    <div><AppLink href={`/organizations/${organizationId}/transport-lanes`} className="text-primary underline">All transport lanes</AppLink><h2 className="mt-3 break-words text-3xl font-semibold">{current.originStoreName} → {current.destinationStoreName}</h2></div>
    {state.notice && <p role="status">{state.notice}</p>}
    <Button variant="outline" disabled={refreshing || busy} onClick={() => { void control.reload() }}>Reload saved configuration</Button>
    {state.error !== null && <CommandError error={state.error} />}
    {busy && <StateMessage state="loading" title="Checking saved configuration" description="Waiting for the saved lane and revision history." />}
    {refreshing && <StateMessage state="loading" title="Refreshing configuration" description="Checking saved revisions." />}
    <div hidden={refreshing} className="space-y-7">
      <div className={panelClass}><LaneStatus lane={current} /></div>
      {current.missing.includes('INACTIVE_STORE')
        ? <section className={panelClass}><h3 className="text-xl font-semibold">Lane recovery unavailable</h3><p className="text-sm">An endpoint store is archived. Future costs can still be cancelled for audit, but transport cannot be re-enabled while that store remains archived.</p></section>
        : <LaneSetupForm key={`setup:${current.version}:${state.preparedDraft ? 'prepared' : 'live'}`} organizationId={organizationId} lane={current} draft={state.preparedDraft ?? undefined} saving={busy || Boolean(review)} onSubmit={body => { void control.saveLane(body) }} />}
      {!current.active && !current.missing.includes('INACTIVE_STORE') && <p className="text-sm text-muted-foreground">To recover this lane, select compatible active zones, enable the lane and save. Approving a new cost is a separate action after the lane is enabled.</p>}
      <Button variant="outline" disabled={refreshing || busy || Boolean(review)} onClick={() => { void control.reviewScheduledCosts() }}>Review and cancel scheduled costs</Button>
      {review && <ScheduleCancellationReview review={review} reason={state.reason} confirmed={state.confirmed} saving={busy} onReasonChange={control.setReason} onConfirmedChange={control.setConfirmed} onCancel={() => { void control.cancelSchedule() }} onClose={control.closeReview} />}
      {current.active && !current.missing.some(value => ['INACTIVE_STORE', 'MISSING_ZONES', 'INACTIVE_ZONE', 'INCOMPATIBLE_ZONES'].includes(value)) && <CostRevisionForm key={`cost:${current.version}`} lane={current} codes={currencies.data.codes} saving={busy || Boolean(review)} onSubmit={body => { void control.approveCost(body) }} />}
      <section aria-labelledby="cost-history-heading" className="space-y-5"><h3 id="cost-history-heading" className="text-2xl font-semibold">Cost history</h3><p className="text-sm text-muted-foreground">Validity starts at the recorded effective time and ends just before the next revision takes effect. Future approvals do not change today's cost. Zone identifiers preserve each revision's original compatibility evidence.</p>
        {!history.data.items.length && <p>No cost revisions have been approved.</p>}
        {history.data.items.map(revision => <RevisionEntry key={revision.id} revision={revision} lane={current} />)}
        <div className="flex gap-3"><Button variant="outline" disabled={!control.historyOffset} onClick={() => { control.setHistoryOffset(Math.max(0, control.historyOffset - 25)) }}>Newer revisions</Button><Button variant="outline" disabled={!history.data.hasMore} onClick={() => { control.setHistoryOffset(control.historyOffset + 25) }}>Older revisions</Button></div>
      </section>
    </div>
  </section>
}

function RevisionEntry({ revision, lane }: { revision: Revision, lane: Lane }) {
  const cancelled = revision.cancellation !== null
  const scheduled = !cancelled && isAfterInstant(revision.effectiveAt, lane.asOf)
  const current = lane.currentCost?.id === revision.id
  const incompatibleEvidence = revision.originZoneId !== lane.originZoneId || revision.destinationZoneId !== lane.destinationZoneId
  const state = cancelled ? 'Cancelled scheduled revision' : scheduled ? 'Scheduled future revision' : current ? 'Current compatible effective cost' : 'Effective historical revision'
  const details: Array<[string, string]> = [
    ['Revision identity', revision.id],
    ['Effective from (UTC)', revision.effectiveAt],
    ['Effective until (exclusive, UTC)', cancelled ? 'No active validity interval; this revision was cancelled.' : revision.validUntil ?? 'No later active revision approved'],
    ['Approved at (UTC)', revision.recordedAt],
    ['Approving membership', revision.recordedByMembershipId],
    ['Origin zone', revision.originZoneId ?? 'Unconfigured legacy evidence'],
    ['Destination zone', revision.destinationZoneId ?? 'Unconfigured legacy evidence'],
    ['Declared temperature class', revision.temperatureClass ?? 'Unconfigured legacy evidence'],
  ]
  if (revision.cancellation) details.push(
    ['Cancelled by membership', revision.cancellation.cancelledByMembershipId],
    ['Cancellation reason', revision.cancellation.reason],
    ['Cancelled at (UTC)', revision.cancellation.cancelledAt],
  )
  return <article className={panelClass}>
    <h4 className="text-lg font-semibold">Revision {revision.revision}</h4>
    <p className="text-sm font-semibold">{state}</p>
    {incompatibleEvidence && <p className="text-sm text-muted-foreground">Historical zone evidence differs from the lane's current zone pair.</p>}
    <p className="break-words font-medium">{revision.costAmount} {revision.currencyCode} · {revision.durationMinutes} minutes</p>
    <dl className="grid gap-4 text-sm sm:grid-cols-2">{details.map(([label, value]) => <div key={label}><dt className="text-muted-foreground">{label}</dt><dd className="mt-1 break-all">{value}</dd></div>)}</dl>
  </article>
}
