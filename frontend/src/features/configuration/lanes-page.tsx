import { AppLink } from '@/components/app-link'
import { Field, inputClass } from '@/components/form-field'
import { useLayoutEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from '@tanstack/react-router'
import { Button } from '@/components/ui/button'
import { StateMessage } from '@/components/state-message'
import { ApiError, errorMessage } from '@/api/client'
import { cancelScheduledLaneRevisions, createLane, getLane, laneHistory, listLanes, reviseLane, supportedCurrencies, updateLane } from '@/api/configuration'
import type { Lane, LaneCreation, LaneUpdate, Revision } from '@/api/configuration'
import { OrganizationPage } from '../organizations/organization-page'
import { useTenantCommand } from '../organizations/use-tenant-command'
import { useTenantKey, useTenantRequests } from '../organizations/tenant-requests'
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
  return <div className="space-y-3"><p className="text-sm font-semibold">{lane.status}</p>{lane.missing.map(value => <p key={value} className="text-sm text-muted-foreground">{missingLabels[value]}</p>)}{lane.currentCost ? <p className="break-words">Current compatible effective cost: <strong>{lane.currentCost.costAmount} {lane.currentCost.currencyCode}</strong> · {lane.currentCost.durationMinutes} minutes · Revision {lane.currentCost.revision}</p> : <p className="text-sm">Current cost and duration: unavailable.</p>}<p className="break-words text-xs text-muted-foreground">Configuration checked at {lane.asOf}. Latest approved revision: {lane.latestRevision}.</p></div>
}
export function LanePage() {
  const { organizationId, laneId } = useParams({ strict: false })
  return <OrganizationPage organizationId={organizationId}>{organizationId && laneId && <LaneDetail key={laneId} organizationId={organizationId} laneId={laneId} />}</OrganizationPage>
}
function LaneDetail({ organizationId, laneId }: { organizationId: string, laneId: string }) {
  const client = useQueryClient()
  const scope = useTenantKey()
  const tenantSignal = useTenantRequests()
  const [offset, setOffset] = useState(0)
  const [notice, setNotice] = useState('')
  const [commandError, setCommandError] = useState<unknown>(null)
  const [saving, setSaving] = useState(false)
  const [resolution, setResolution] = useState<LaneResolution | null>(null)
  const [preparedDraft, setPreparedDraft] = useState<LaneUpdate | null>(null)
  const [cancelReason, setCancelReason] = useState('')
  const [cancelConfirmed, setCancelConfirmed] = useState(false)
  const [reconciliationRequired, setReconciliationRequired] = useState(false)
  const [unresolved, setUnresolved] = useState<UnresolvedCancellation | null>(null)
  const activeCommand = useRef<AbortController | null>(null)
  const commandLock = useRef(false)
  const root = [...scope, 'lane', laneId] as const
  const detailKey = [...root, 'detail'] as const
  const lane = useQuery({ queryKey: detailKey, queryFn: ({ signal }) => getLane(organizationId, laneId, signal), enabled: !saving, refetchInterval: saving ? false : 30000 })
  const historyKey = [...root, 'history', offset] as const
  const history = useQuery({ queryKey: historyKey, queryFn: ({ signal }) => laneHistory(organizationId, laneId, offset, signal), enabled: !saving })
  const currencies = useQuery({ queryKey: [...scope, 'lane-currencies'], queryFn: ({ signal }) => supportedCurrencies(organizationId, signal) })
  useLayoutEffect(() => () => { activeCommand.current?.abort() }, [])

  async function reloadLaneAndHistory(signal: AbortSignal, cancellationIds: string[] = []) {
    await client.cancelQueries({ queryKey: root })
    signal.throwIfAborted()
    const latest = await getLane(organizationId, laneId, signal)
    signal.throwIfAborted()
    const latestHistory = await laneHistory(organizationId, laneId, 0, signal)
    signal.throwIfAborted()
    if (cancellationIds.length) await confirmCancellations(organizationId, laneId, cancellationIds, signal)
    const scheduled = await loadScheduledRevisions(organizationId, laneId, latest.asOf, signal)
    signal.throwIfAborted()
    client.setQueryData(detailKey, latest)
    client.setQueryData([...root, 'history', 0], latestHistory)
    setOffset(0)
    return { lane: latest, history: latestHistory, scheduled }
  }

  async function runCommand(action: (signal: AbortSignal) => Promise<void>) {
    if (commandLock.current) return
    commandLock.current = true
    const controller = new AbortController()
    activeCommand.current = controller
    const signal = AbortSignal.any([tenantSignal(), controller.signal])
    setSaving(true)
    setCommandError(null)
    try {
      await action(signal)
      signal.throwIfAborted()
    } catch (error) {
      if (!signal.aborted) setCommandError(error)
    } finally {
      commandLock.current = false
      if (activeCommand.current === controller) activeCommand.current = null
      if (!signal.aborted) setSaving(false)
    }
  }

  function offerResolution(currentLane: Lane, scheduled: Revision[], draft: LaneUpdate) {
    setPreparedDraft(draft)
    if (scheduled.length) {
      setResolution({ lane: currentLane, revisions: scheduled, draft })
      setCancelReason('')
      setCancelConfirmed(false)
      setNotice('')
    } else {
      setResolution(null)
      setNotice('The lane and history were reloaded. Review the selected zones and save the lane again.')
    }
  }

  async function saveLane(body: LaneCreation | LaneUpdate) {
    if (!('version' in body)) {
      await runCommand(async signal => {
        await createLane(organizationId, body, signal)
        await client.invalidateQueries({ queryKey: [...scope, 'lanes'] })
        setNotice('Transport lane saved. Add a cost revision to finish its configuration.')
      })
      return
    }

    const update = body as LaneUpdate
    const visibleLane = lane.data
    if (!visibleLane) return
    const changesZones = update.active && ((update.originZoneId ?? null) !== visibleLane.originZoneId
      || (update.destinationZoneId ?? null) !== visibleLane.destinationZoneId)
    await runCommand(async signal => {
      let latest = visibleLane
      if (changesZones) {
        let snapshot: Awaited<ReturnType<typeof reloadLaneAndHistory>>
        try {
          snapshot = await reloadLaneAndHistory(signal)
        } catch (error) {
          if (!signal.aborted) {
            setReconciliationRequired(true)
            setUnresolved({ draft: update, cancellationIds: [] })
          }
          throw error
        }
        if (snapshot.lane.version !== visibleLane.version
          || snapshot.lane.originZoneId !== visibleLane.originZoneId
          || snapshot.lane.destinationZoneId !== visibleLane.destinationZoneId) {
          setPreparedDraft(null)
          setNotice('The lane configuration changed elsewhere. Review the reloaded configuration before editing again.')
          return
        }
        latest = snapshot.lane
        if (snapshot.scheduled.length) {
          offerResolution(snapshot.lane, snapshot.scheduled, update)
          return
        }
      }

      try {
        await updateLane(organizationId, laneId, { ...update, version: latest.version }, signal)
        await reloadLaneAndHistory(signal)
        setPreparedDraft(null)
        setResolution(null)
        setNotice('Lane configuration saved. The lane and cost history were reloaded.')
        await client.invalidateQueries({ queryKey: [...scope, 'lanes'] })
      } catch (error) {
        if (error instanceof ApiError && error.code === 'SCHEDULED_REVISIONS_PENDING' && changesZones) {
          try {
            const snapshot = await reloadLaneAndHistory(signal)
            offerResolution(snapshot.lane, snapshot.scheduled, update)
            return
          } catch (readError) {
            if (!signal.aborted) {
              setReconciliationRequired(true)
              setUnresolved({ draft: update, cancellationIds: [] })
            }
            throw readError
          }
        }
        try {
          await reloadLaneAndHistory(signal)
        } catch {
          if (!signal.aborted) {
            setReconciliationRequired(true)
            setUnresolved({ draft: update, cancellationIds: [] })
          }
        }
        throw error
      }
    })
  }

  async function approveCost(body: Parameters<typeof reviseLane>[2]) {
    await runCommand(async signal => {
      try {
        await reviseLane(organizationId, laneId, body, signal)
        await reloadLaneAndHistory(signal)
        setNotice('Cost revision saved. The lane and cost history were reloaded.')
      } catch (error) {
        try {
          await reloadLaneAndHistory(signal)
        } catch {
          if (!signal.aborted) {
            setReconciliationRequired(true)
            setUnresolved({ draft: null, cancellationIds: [] })
          }
        }
        throw error
      }
    })
  }

  async function cancelSchedule() {
    if (!resolution || cancelReason.trim().length < 10 || !cancelConfirmed) return
    const review = resolution
    await runCommand(async signal => {
      let result: Awaited<ReturnType<typeof cancelScheduledLaneRevisions>>
      try {
        result = await cancelScheduledLaneRevisions(organizationId, laneId, {
          laneVersion: review.lane.version,
          expectedRevision: review.lane.latestRevision,
          reason: cancelReason.trim(),
        }, signal)
      } catch (error) {
        try {
          const snapshot = await reloadLaneAndHistory(signal)
          if (error instanceof ApiError && error.code === 'NO_FUTURE_REVISIONS' && !snapshot.scheduled.length) {
            setResolution(null)
            setPreparedDraft(review.draft)
            setNotice('No future scheduled costs remain. Lane and history were reloaded; review the zone choices and save again.')
            return
          }
          offerResolution(snapshot.lane, snapshot.scheduled, review.draft)
        } catch {
          if (!signal.aborted) {
            setReconciliationRequired(true)
            setUnresolved({ draft: review.draft, cancellationIds: [] })
          }
        }
        throw error
      }

      try {
        const snapshot = await reloadLaneAndHistory(signal, result.cancelledRevisions.map(item => item.id))
        setResolution(null)
        if (snapshot.scheduled.length) {
          offerResolution(snapshot.lane, snapshot.scheduled, review.draft)
          setNotice('The confirmed cancellation is saved. A newer scheduled cost appeared; review it before changing zones.')
        } else {
          setPreparedDraft(review.draft)
          setNotice(`Cancelled ${result.cancelledRevisions.length} scheduled revision${result.cancelledRevisions.length === 1 ? '' : 's'}. Lane and history were reloaded; review the selected zones and save again.`)
        }
        await client.invalidateQueries({ queryKey: [...scope, 'lanes'] })
      } catch (error) {
        if (!signal.aborted) {
          setReconciliationRequired(true)
          setUnresolved({ draft: review.draft, cancellationIds: result.cancelledRevisions.map(item => item.id) })
        }
        throw error
      }
    })
  }

  async function reconcileUncertainResult() {
    const pending = unresolved
    if (!pending) return
    await runCommand(async signal => {
      const snapshot = await reloadLaneAndHistory(signal, pending.cancellationIds)
      setReconciliationRequired(false)
      setUnresolved(null)
      if (pending.draft) offerResolution(snapshot.lane, snapshot.scheduled, pending.draft)
      else setNotice('The lane and history now show the latest saved state.')
    })
  }

  if (lane.isPending || history.isPending || currencies.isPending) return <StateMessage state="loading" title="Loading lane configuration" description="Checking costs, history and your access." />
  if (reconciliationRequired) return <div className="space-y-4"><StateMessage state="unavailable" title="Lane state needs reconciliation" description="A write may have completed, but the latest lane and history could not be confirmed. No further lane changes are available until they are reloaded." />{commandError !== null && <CommandError error={commandError} />}<Button disabled={saving} onClick={() => { void reconcileUncertainResult() }}>{saving ? 'Reloading lane and history…' : 'Reload lane and history before continuing'}</Button></div>
  if (lane.isError || history.isError || currencies.isError) return <div className="space-y-4"><StateMessage state="unavailable" title="Lane configuration unavailable" description={errorMessage(lane.error ?? history.error ?? currencies.error)} /><Button disabled={saving} onClick={() => { void runCommand(async signal => { await reloadLaneAndHistory(signal); await currencies.refetch() }) }}>Reload saved configuration</Button></div>
  const current = lane.data
  const refreshing = lane.isFetching || history.isFetching
  return <section className="space-y-7">
    <div><AppLink href={`/organizations/${organizationId}/transport-lanes`} className="text-primary underline">All transport lanes</AppLink><h2 className="mt-3 break-words text-3xl font-semibold">{current.originStoreName} → {current.destinationStoreName}</h2></div>
    {notice && <p role="status">{notice}</p>}
    <Button variant="outline" disabled={refreshing || saving} onClick={() => { setNotice(''); void runCommand(async signal => { await reloadLaneAndHistory(signal) }) }}>Reload saved configuration</Button>
    {commandError !== null && <CommandError error={commandError} />}
    {saving && <StateMessage state="loading" title="Checking saved configuration" description="Waiting for the saved lane and revision history." />}
    {refreshing && <StateMessage state="loading" title="Refreshing configuration" description="Checking saved revisions." />}
    <div hidden={refreshing} className="space-y-7">
      <div className={panelClass}><LaneStatus lane={current} /></div>
      <LaneSetupForm key={`setup:${current.version}:${preparedDraft ? 'prepared' : 'live'}`} organizationId={organizationId} lane={current} draft={preparedDraft ?? undefined} saving={saving || Boolean(resolution)} onSubmit={body => { setNotice(''); void saveLane(body) }} />
      {resolution && <section role="region" aria-labelledby="schedule-resolution-heading" className={`${panelClass} border-primary/40`}>
        <h3 id="schedule-resolution-heading" className="text-xl font-semibold">Review future costs before changing lane zones</h3>
        <p className="text-sm">Saving the new zone pair is blocked while these scheduled costs remain. Confirming below cancels every future schedule currently attached to this lane. The zone change will remain a separate action after the lane and history are reloaded.</p>
        <ul className="space-y-3" aria-label="Scheduled cost revisions to cancel">{resolution.revisions.map(revision => <li key={revision.id} className="rounded-xl border border-border p-4">
          <p className="font-semibold">Revision {revision.revision} · {revision.id}</p>
          <p className="break-words">{revision.costAmount} {revision.currencyCode} · {revision.durationMinutes} minutes</p>
          <p className="break-words text-sm text-muted-foreground">Effective time (UTC): {revision.effectiveAt}</p>
        </li>)}</ul>
        <Field label="Reason for cancelling scheduled costs" help="Enter at least 10 characters describing why every listed future cost should be cancelled.">{(id, description) => <textarea id={id} aria-describedby={description} required minLength={10} maxLength={500} value={cancelReason} disabled={saving} onChange={event => { setCancelReason(event.target.value) }} className={inputClass} />}</Field>
        <label className="flex gap-3 text-sm"><input type="checkbox" checked={cancelConfirmed} disabled={saving} onChange={event => { setCancelConfirmed(event.target.checked) }} />I reviewed the listed revisions and confirm cancelling all of them.</label>
        <div className="flex flex-wrap gap-3"><Button type="button" disabled={saving || cancelReason.trim().length < 10 || !cancelConfirmed} onClick={() => { void cancelSchedule() }}>{saving ? 'Reconciling scheduled costs…' : `Cancel all ${resolution.revisions.length} scheduled revision${resolution.revisions.length === 1 ? '' : 's'}`}</Button><Button type="button" variant="outline" disabled={saving} onClick={() => { setResolution(null); setPreparedDraft(null); setCancelReason(''); setCancelConfirmed(false) }}>Keep schedule and review lane</Button></div>
      </section>}
      {current.active && !current.missing.some(value => ['INACTIVE_STORE', 'MISSING_ZONES', 'INACTIVE_ZONE', 'INCOMPATIBLE_ZONES'].includes(value)) && <CostRevisionForm key={`cost:${current.version}`} lane={current} codes={currencies.data.codes} saving={saving || Boolean(resolution)} onSubmit={body => { setNotice(''); void approveCost(body) }} />}
      <section aria-labelledby="cost-history-heading" className="space-y-5"><h3 id="cost-history-heading" className="text-2xl font-semibold">Cost history</h3><p className="text-sm text-muted-foreground">Validity starts at the recorded effective time and ends just before the next revision takes effect. Future approvals do not change today's cost. Zone identifiers preserve each revision's original compatibility evidence.</p>
        {!history.data.items.length && <p>No cost revisions have been approved.</p>}
        {history.data.items.map(revision => <RevisionEntry key={revision.id} revision={revision} lane={current} />)}
        <div className="flex gap-3"><Button variant="outline" disabled={!offset} onClick={() => { setOffset(value => Math.max(0, value - 25)) }}>Newer revisions</Button><Button variant="outline" disabled={!history.data.hasMore} onClick={() => { setOffset(value => value + 25) }}>Older revisions</Button></div>
      </section>
    </div>
  </section>
}

type LaneResolution = { lane: Lane, revisions: Revision[], draft: LaneUpdate }
type UnresolvedCancellation = { draft: LaneUpdate | null, cancellationIds: string[] }

async function loadScheduledRevisions(organizationId: string, laneId: string, asOf: string, signal: AbortSignal) {
  const scheduled: Revision[] = []
  let offset = 0
  while (true) {
    const page = await laneHistory(organizationId, laneId, offset, signal, 100)
    signal.throwIfAborted()
    for (const revision of page.items) {
      if (revision.cancellation) continue
      if (Date.parse(revision.effectiveAt) <= Date.parse(asOf)) return scheduled
      scheduled.push(revision)
    }
    if (!page.hasMore) return scheduled
    if (!page.items.length || offset >= 10000) throw new Error('The complete scheduled cost list could not be loaded.')
    offset += page.items.length
  }
}

async function confirmCancellations(organizationId: string, laneId: string, cancellationIds: string[], signal: AbortSignal) {
  const remaining = new Set(cancellationIds)
  let offset = 0
  while (remaining.size) {
    const page = await laneHistory(organizationId, laneId, offset, signal, 100)
    signal.throwIfAborted()
    for (const revision of page.items) {
      if (revision.cancellation) remaining.delete(revision.cancellation.id)
    }
    if (!remaining.size) return
    if (!page.hasMore || !page.items.length || offset >= 10000) break
    offset += page.items.length
  }
  if (remaining.size) throw new Error('The saved cancellation evidence could not be confirmed in lane history.')
}

function RevisionEntry({ revision, lane }: { revision: Revision, lane: Lane }) {
  const cancelled = revision.cancellation !== null
  const scheduled = !cancelled && Date.parse(revision.effectiveAt) > Date.parse(lane.asOf)
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
