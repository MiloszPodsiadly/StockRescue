import { useLayoutEffect, useReducer, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '@/api/client'
import { cancelScheduledLaneRevisions, createLane, getLane, laneHistory, reviseLane, supportedCurrencies, updateLane } from '@/api/configuration'
import type { Lane, LaneCreation, LaneUpdate, Revision } from '@/api/configuration'
import { useTenantKey, useTenantRequests } from '../organizations/tenant-requests'
import { confirmCancellations, findCancelledRevisionIds, loadScheduledRevisions } from './scheduled-cost-history'
import type { ScheduleReview } from './scheduled-cost-history'

type Phase = 'IDLE' | 'LOADING_REVIEW' | 'REVIEWING' | 'SAVING' | 'CANCELLING' | 'RELOADING' | 'RECONCILING' | 'NEEDS_RECONCILIATION'
type PendingCancellation = { draft: LaneUpdate | null, cancellationIds: Array<{ id: string, revisionId: string }>, reviewedRevisionIds: string[], kind: ScheduleReview['kind'] }
type State = {
  phase: Phase
  notice: string
  error: unknown | null
  review: ScheduleReview | null
  preparedDraft: LaneUpdate | null
  reason: string
  confirmed: boolean
  unresolved: PendingCancellation | null
}

type Action =
  | { type: 'begin', phase: Extract<Phase, 'LOADING_REVIEW' | 'SAVING' | 'CANCELLING' | 'RELOADING' | 'RECONCILING'> }
  | { type: 'review', lane: Lane, revisions: Revision[], draft: LaneUpdate | null, kind: ScheduleReview['kind'] }
  | { type: 'close-review' }
  | { type: 'clear-review' }
  | { type: 'reason', value: string }
  | { type: 'confirmed', value: boolean }
  | { type: 'notice', value: string }
  | { type: 'error', value: unknown }
  | { type: 'needs-reconciliation', pending: PendingCancellation }
  | { type: 'reconciled', notice: string, preparedDraft?: LaneUpdate | null }
  | { type: 'settled' }

const initialState: State = {
  phase: 'IDLE', notice: '', error: null, review: null, preparedDraft: null,
  reason: '', confirmed: false, unresolved: null,
}

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case 'begin': return { ...state, phase: action.phase, error: null }
    case 'review':
      if (!action.revisions.length) return {
        ...state, review: null, preparedDraft: action.kind === 'zone-change' ? action.draft : null,
        reason: '', confirmed: false,
        notice: action.kind === 'independent'
          ? 'No active future scheduled costs were found. Lane and cost history were refreshed.'
          : 'The lane and history were reloaded. Review the selected zones and save the lane again.',
      }
      return {
        ...state, phase: 'REVIEWING', review: { lane: action.lane, revisions: action.revisions, draft: action.draft, kind: action.kind },
        preparedDraft: action.kind === 'zone-change' ? action.draft : null, reason: '', confirmed: false,
        notice: '', unresolved: null,
      }
    case 'close-review': return { ...state, phase: 'IDLE', review: null, preparedDraft: null, reason: '', confirmed: false }
    case 'clear-review': return { ...state, review: null, preparedDraft: null, reason: '', confirmed: false }
    case 'reason': return { ...state, reason: action.value }
    case 'confirmed': return { ...state, confirmed: action.value }
    case 'notice': return { ...state, notice: action.value }
    case 'error': return { ...state, error: action.value }
    case 'needs-reconciliation': return { ...state, phase: 'NEEDS_RECONCILIATION', unresolved: action.pending }
    case 'reconciled': return { ...state, review: null, reason: '', confirmed: false, unresolved: null, error: null, notice: action.notice, preparedDraft: action.preparedDraft ?? null }
    case 'settled': return state.phase === 'REVIEWING' || state.phase === 'NEEDS_RECONCILIATION'
      ? state
      : { ...state, phase: 'IDLE' }
  }
}

export function useLaneControl(organizationId: string, laneId: string) {
  const client = useQueryClient()
  const scope = useTenantKey()
  const tenantSignal = useTenantRequests()
  const [state, dispatch] = useReducer(reducer, initialState)
  const [historyOffset, setHistoryOffset] = useState(0)
  const activeCommand = useRef<AbortController | null>(null)
  const commandLock = useRef(false)
  const root = [...scope, 'lane', laneId] as const
  const detailKey = [...root, 'detail'] as const
  const lane = useQuery({ queryKey: detailKey, queryFn: ({ signal }) => getLane(organizationId, laneId, signal), enabled: state.phase === 'IDLE' || state.phase === 'REVIEWING', refetchInterval: state.phase === 'IDLE' ? 30000 : false })
  const historyKey = [...root, 'history', historyOffset] as const
  const history = useQuery({ queryKey: historyKey, queryFn: ({ signal }) => laneHistory(organizationId, laneId, historyOffset, signal), enabled: state.phase === 'IDLE' || state.phase === 'REVIEWING' })
  const currencies = useQuery({ queryKey: [...scope, 'lane-currencies'], queryFn: ({ signal }) => supportedCurrencies(organizationId, signal) })
  useLayoutEffect(() => () => { activeCommand.current?.abort() }, [])

  async function reloadLaneAndHistory(signal: AbortSignal, cancellations: Array<{ id: string, revisionId: string }> = []) {
    await client.cancelQueries({ queryKey: root })
    signal.throwIfAborted()
    const latest = await getLane(organizationId, laneId, signal)
    signal.throwIfAborted()
    const latestHistory = await laneHistory(organizationId, laneId, 0, signal)
    signal.throwIfAborted()
    if (cancellations.length) await confirmCancellations(organizationId, laneId, cancellations, signal)
    const scheduled = await loadScheduledRevisions(organizationId, laneId, latest.asOf, signal)
    signal.throwIfAborted()
    client.setQueryData(detailKey, latest)
    client.setQueryData([...root, 'history', 0], latestHistory)
    setHistoryOffset(0)
    return { lane: latest, history: latestHistory, scheduled }
  }

  async function runCommand(phase: Extract<Phase, 'LOADING_REVIEW' | 'SAVING' | 'CANCELLING' | 'RELOADING' | 'RECONCILING'>, action: (signal: AbortSignal) => Promise<void>) {
    if (commandLock.current) return
    commandLock.current = true
    const controller = new AbortController()
    activeCommand.current = controller
    const signal = AbortSignal.any([tenantSignal(), controller.signal])
    dispatch({ type: 'begin', phase })
    try {
      await action(signal)
      signal.throwIfAborted()
    } catch (error) {
      if (!signal.aborted) dispatch({ type: 'error', value: error })
    } finally {
      commandLock.current = false
      if (activeCommand.current === controller) activeCommand.current = null
      if (!signal.aborted) dispatch({ type: 'settled' })
    }
  }

  async function reviewScheduledCosts() {
    await runCommand('LOADING_REVIEW', async signal => {
      const snapshot = await reloadLaneAndHistory(signal)
      dispatch({ type: 'review', lane: snapshot.lane, revisions: snapshot.scheduled, draft: null, kind: 'independent' })
    })
  }

  async function saveLane(body: LaneCreation | LaneUpdate) {
    if (!('version' in body)) {
      await runCommand('SAVING', async signal => {
        await createLane(organizationId, body, signal)
        await client.invalidateQueries({ queryKey: [...scope, 'lanes'] })
        dispatch({ type: 'notice', value: 'Transport lane saved. Add a cost revision to finish its configuration.' })
      })
      return
    }

    const update = body as LaneUpdate
    const visibleLane = lane.data
    if (!visibleLane) return
    const changesZones = update.active && ((update.originZoneId ?? null) !== visibleLane.originZoneId
      || (update.destinationZoneId ?? null) !== visibleLane.destinationZoneId)
    await runCommand('SAVING', async signal => {
      let latest = visibleLane
      if (changesZones) {
        let snapshot: Awaited<ReturnType<typeof reloadLaneAndHistory>>
        try {
          snapshot = await reloadLaneAndHistory(signal)
        } catch (error) {
          if (!signal.aborted) dispatch({ type: 'needs-reconciliation', pending: { draft: update, cancellationIds: [], reviewedRevisionIds: [], kind: 'zone-change' } })
          throw error
        }
        if (snapshot.lane.version !== visibleLane.version
          || snapshot.lane.originZoneId !== visibleLane.originZoneId
          || snapshot.lane.destinationZoneId !== visibleLane.destinationZoneId) {
          dispatch({ type: 'notice', value: 'The lane configuration changed elsewhere. Review the reloaded configuration before editing again.' })
          return
        }
        latest = snapshot.lane
        if (snapshot.scheduled.length) {
          dispatch({ type: 'review', lane: snapshot.lane, revisions: snapshot.scheduled, draft: update, kind: 'zone-change' })
          return
        }
      }

      try {
        await updateLane(organizationId, laneId, { ...update, version: latest.version }, signal)
        await reloadLaneAndHistory(signal)
        dispatch({ type: 'clear-review' })
        dispatch({ type: 'notice', value: 'Lane configuration saved. The lane and cost history were reloaded.' })
        await client.invalidateQueries({ queryKey: [...scope, 'lanes'] })
      } catch (error) {
        if (error instanceof ApiError && error.code === 'SCHEDULED_REVISIONS_PENDING' && changesZones) {
          try {
            const snapshot = await reloadLaneAndHistory(signal)
            dispatch({ type: 'review', lane: snapshot.lane, revisions: snapshot.scheduled, draft: update, kind: 'zone-change' })
            return
          } catch (readError) {
            if (!signal.aborted) dispatch({ type: 'needs-reconciliation', pending: { draft: update, cancellationIds: [], reviewedRevisionIds: [], kind: 'zone-change' } })
            throw readError
          }
        }
        try {
          await reloadLaneAndHistory(signal)
        } catch {
          if (!signal.aborted) dispatch({ type: 'needs-reconciliation', pending: { draft: update, cancellationIds: [], reviewedRevisionIds: [], kind: 'zone-change' } })
        }
        throw error
      }
    })
  }

  async function approveCost(body: Parameters<typeof reviseLane>[2]) {
    await runCommand('SAVING', async signal => {
      try {
        await reviseLane(organizationId, laneId, body, signal)
        await reloadLaneAndHistory(signal)
        dispatch({ type: 'notice', value: 'Cost revision saved. The lane and cost history were reloaded.' })
      } catch (error) {
        try {
          await reloadLaneAndHistory(signal)
        } catch {
          if (!signal.aborted) dispatch({ type: 'needs-reconciliation', pending: { draft: null, cancellationIds: [], reviewedRevisionIds: [], kind: 'zone-change' } })
        }
        throw error
      }
    })
  }

  async function cancelSchedule() {
    const review = state.review
    if (!review || state.phase !== 'REVIEWING' || state.reason.trim().length < 10 || !state.confirmed) return
    await runCommand('CANCELLING', async signal => {
      let result: Awaited<ReturnType<typeof cancelScheduledLaneRevisions>>
      try {
        result = await cancelScheduledLaneRevisions(organizationId, laneId, {
          laneVersion: review.lane.version,
          expectedRevision: review.lane.latestRevision,
          reason: state.reason.trim(),
        }, signal)
      } catch (error) {
        try {
          const snapshot = await reloadLaneAndHistory(signal)
          const cancelled = await findCancelledRevisionIds(organizationId, laneId, review.revisions.map(item => item.id), signal)
          if (cancelled.size === review.revisions.length) {
            await finishCancellation(review, snapshot, cancelled.size)
            return
          }
          if (error instanceof ApiError && error.code === 'NO_FUTURE_REVISIONS' && !snapshot.scheduled.length) {
            dispatch({ type: 'reconciled', notice: 'The reviewed schedules are no longer future plans. Lane and history were reloaded; no new cancellation request was sent.', preparedDraft: review.draft })
            return
          }
          dispatch({ type: 'review', lane: snapshot.lane, revisions: snapshot.scheduled, draft: review.draft, kind: review.kind })
        } catch {
          if (!signal.aborted) dispatch({ type: 'needs-reconciliation', pending: { draft: review.draft, cancellationIds: [], reviewedRevisionIds: review.revisions.map(item => item.id), kind: review.kind } })
        }
        throw error
      }

      try {
        const snapshot = await reloadLaneAndHistory(signal, result.cancelledRevisions.map(item => ({ id: item.id, revisionId: item.revisionId })))
        await finishCancellation(review, snapshot, result.cancelledRevisions.length)
      } catch (error) {
        if (!signal.aborted) dispatch({ type: 'needs-reconciliation', pending: { draft: review.draft, cancellationIds: result.cancelledRevisions.map(item => ({ id: item.id, revisionId: item.revisionId })), reviewedRevisionIds: review.revisions.map(item => item.id), kind: review.kind } })
        throw error
      }
    })
  }

  async function finishCancellation(review: ScheduleReview, snapshot: Awaited<ReturnType<typeof reloadLaneAndHistory>>, cancelledCount: number) {
    if (snapshot.scheduled.length) {
      dispatch({ type: 'review', lane: snapshot.lane, revisions: snapshot.scheduled, draft: review.draft, kind: review.kind })
      dispatch({ type: 'notice', value: review.kind === 'independent'
        ? 'Saved history confirms the reviewed costs are cancelled. Newer future costs appeared; review them before continuing.'
        : 'The confirmed cancellation is saved. A newer scheduled cost appeared; review it before changing zones.' })
    } else {
      const notice = review.kind === 'independent'
        ? `Saved history confirms ${cancelledCount} reviewed scheduled cost${cancelledCount === 1 ? '' : 's'} cancelled. Lane and history are current; you can approve a fresh current cost now.`
        : `Cancelled ${cancelledCount} scheduled revision${cancelledCount === 1 ? '' : 's'}. Lane and history were reloaded; review the selected zones and save again.`
      dispatch({ type: 'reconciled', notice, preparedDraft: review.draft })
    }
    await client.invalidateQueries({ queryKey: [...scope, 'lanes'] })
  }

  async function reload() {
    dispatch({ type: 'notice', value: '' })
    await runCommand('RELOADING', async signal => {
      await reloadLaneAndHistory(signal)
      await currencies.refetch()
    })
  }

  async function reconcileUncertainResult() {
    const pending = state.unresolved
    if (!pending || state.phase !== 'NEEDS_RECONCILIATION') return
    await runCommand('RECONCILING', async signal => {
      const snapshot = await reloadLaneAndHistory(signal, pending.cancellationIds)
      if (pending.reviewedRevisionIds.length) {
        const cancelled = await findCancelledRevisionIds(organizationId, laneId, pending.reviewedRevisionIds, signal)
        if (cancelled.size === pending.reviewedRevisionIds.length) {
          await finishCancellation({ lane: snapshot.lane, revisions: [], draft: pending.draft, kind: pending.kind }, snapshot, cancelled.size)
        } else if (snapshot.scheduled.length) dispatch({ type: 'review', lane: snapshot.lane, revisions: snapshot.scheduled, draft: pending.draft, kind: pending.kind })
        else dispatch({ type: 'reconciled', notice: 'Lane and history were reloaded. No future schedules remain, but the reviewed cancellation evidence could not be confirmed.' })
      } else if (pending.draft) dispatch({ type: 'review', lane: snapshot.lane, revisions: snapshot.scheduled, draft: pending.draft, kind: pending.kind })
      else dispatch({ type: 'reconciled', notice: 'The lane and history now show the latest saved state.' })
    })
  }

  function closeReview() { dispatch({ type: 'close-review' }) }
  function setReason(value: string) { dispatch({ type: 'reason', value }) }
  function setConfirmed(value: boolean) { dispatch({ type: 'confirmed', value }) }

  return {
    state, lane, history, currencies, historyOffset,
    busy: state.phase !== 'IDLE' && state.phase !== 'REVIEWING' && state.phase !== 'NEEDS_RECONCILIATION',
    refreshing: lane.isFetching || history.isFetching,
    setHistoryOffset, setReason, setConfirmed, saveLane, approveCost, reviewScheduledCosts, cancelSchedule, closeReview, reload, reconcileUncertainResult,
  }
}
