import { useLayoutEffect, useReducer, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '@/api/client'
import { cancelScheduledLaneRevisions, createLane, getLane, laneHistory, resolveScheduledLaneCancellation, reviseLane, supportedCurrencies, updateLane } from '@/api/configuration'
import type { Lane, LaneCreation, LaneUpdate, Revision, ScheduledRevisionCancellationResolutionResponse } from '@/api/configuration'
import { useTenantKey, useTenantRequests } from '../organizations/tenant-requests'
import { confirmCancellations, loadScheduledRevisions } from './scheduled-cost-history'
import type { ScheduleReview } from './scheduled-cost-history'

const CANCELLATION_RESOLUTION_BATCH_SIZE = 384
type Phase = 'IDLE' | 'LOADING_REVIEW' | 'REVIEWING' | 'SAVING' | 'CANCELLING' | 'RELOADING' | 'RECONCILING' | 'NEEDS_RECONCILIATION'
type PendingCancellation = { draft: LaneUpdate | null, cancellationIds: Array<{ id: string, revisionId: string }>, reviewedRevisionIds: string[], reviewedLane: Lane | null, kind: ScheduleReview['kind'] }
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

function isAmbiguousCommandFailure(error: unknown) {
  return !(error instanceof ApiError) || error.status === 408 || error.status === 425 || error.status >= 500
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
    case 'close-review':
      if (state.unresolved !== null) return { ...state, phase: 'NEEDS_RECONCILIATION' }
      return { ...state, phase: 'IDLE', review: null, preparedDraft: null, reason: '', confirmed: false }
    case 'clear-review': return { ...state, review: null, preparedDraft: null, reason: '', confirmed: false }
    case 'reason': return { ...state, reason: action.value }
    case 'confirmed': return { ...state, confirmed: action.value }
    case 'notice': return { ...state, notice: action.value }
    case 'error': return { ...state, error: action.value }
    case 'needs-reconciliation': return { ...state, phase: 'NEEDS_RECONCILIATION', unresolved: action.pending }
    case 'reconciled': return { ...state, phase: 'IDLE', review: null, reason: '', confirmed: false, unresolved: null, error: null, notice: action.notice, preparedDraft: action.preparedDraft ?? null }
    case 'settled':
      if (state.unresolved !== null) return { ...state, phase: 'NEEDS_RECONCILIATION' }
      return state.phase === 'REVIEWING' || state.phase === 'NEEDS_RECONCILIATION'
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
          if (!signal.aborted) dispatch({ type: 'needs-reconciliation', pending: { draft: update, cancellationIds: [], reviewedRevisionIds: [], reviewedLane: visibleLane, kind: 'zone-change' } })
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
            if (!signal.aborted) dispatch({ type: 'needs-reconciliation', pending: { draft: update, cancellationIds: [], reviewedRevisionIds: [], reviewedLane: latest, kind: 'zone-change' } })
            throw readError
          }
        }
        try {
          await reloadLaneAndHistory(signal)
        } catch {
          if (!signal.aborted) dispatch({ type: 'needs-reconciliation', pending: { draft: update, cancellationIds: [], reviewedRevisionIds: [], reviewedLane: latest, kind: 'zone-change' } })
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
          if (!signal.aborted) dispatch({ type: 'needs-reconciliation', pending: { draft: null, cancellationIds: [], reviewedRevisionIds: [], reviewedLane: lane.data ?? null, kind: 'zone-change' } })
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
        const pending: PendingCancellation = { draft: review.draft, cancellationIds: [], reviewedRevisionIds: review.revisions.map(item => item.id), reviewedLane: review.lane, kind: review.kind }
        if (isAmbiguousCommandFailure(error) || (error instanceof ApiError && error.code === 'NO_FUTURE_REVISIONS')) {
          dispatch({ type: 'needs-reconciliation', pending })
          try {
            await reconcileReviewedCancellation(pending, signal)
            return
          } catch (resolutionError) {
            if (!signal.aborted) dispatch({ type: 'needs-reconciliation', pending })
            throw resolutionError
          }
        }
        try {
          const snapshot = await reloadLaneAndHistory(signal)
          const laneUnchanged = snapshot.lane.version === review.lane.version
            && snapshot.lane.originZoneId === review.lane.originZoneId
            && snapshot.lane.destinationZoneId === review.lane.destinationZoneId
            && snapshot.lane.active === review.lane.active
          dispatch({ type: 'review', lane: snapshot.lane, revisions: snapshot.scheduled, draft: laneUnchanged ? review.draft : null, kind: laneUnchanged ? review.kind : 'independent' })
          if (review.draft && !laneUnchanged) dispatch({ type: 'notice', value: 'The lane configuration changed while cancellation was being reviewed. The earlier zone draft was discarded.' })
        } catch {
          if (!signal.aborted) dispatch({ type: 'needs-reconciliation', pending })
        }
        throw error
      }

      try {
        const snapshot = await reloadLaneAndHistory(signal, result.cancelledRevisions.map(item => ({ id: item.id, revisionId: item.revisionId })))
        await finishCancellation(review, snapshot, result.cancelledRevisions.length)
      } catch (error) {
        if (!signal.aborted) dispatch({ type: 'needs-reconciliation', pending: { draft: review.draft, cancellationIds: result.cancelledRevisions.map(item => ({ id: item.id, revisionId: item.revisionId })), reviewedRevisionIds: review.revisions.map(item => item.id), reviewedLane: review.lane, kind: review.kind } })
        throw error
      }
    })
  }

  async function finishCancellation(review: ScheduleReview, snapshot: Awaited<ReturnType<typeof reloadLaneAndHistory>>, cancelledCount: number) {
    const expectedVersion = review.lane.version + (cancelledCount > 0 ? 1 : 0)
    const keepDraft = review.draft !== null
      && laneConfigurationMatches(review.lane, snapshot.lane, expectedVersion)
    const draft = keepDraft ? review.draft : null
    const kind = draft ? review.kind : 'independent'
    const staleDraft = review.draft !== null && !keepDraft
    if (snapshot.scheduled.length) {
      dispatch({ type: 'review', lane: snapshot.lane, revisions: snapshot.scheduled, draft, kind })
      dispatch({ type: 'notice', value: staleDraft
        ? 'Cancellation is confirmed. The lane changed while it was being refreshed, so the earlier zone draft was discarded; review the current schedules.'
        : kind === 'independent'
        ? 'Saved history confirms the reviewed costs are cancelled. Newer future costs appeared; review them before continuing.'
        : 'The confirmed cancellation is saved. A newer scheduled cost appeared; review it before changing zones.' })
    } else {
      const notice = kind === 'independent'
        ? `Saved history confirms ${cancelledCount} reviewed scheduled cost${cancelledCount === 1 ? '' : 's'} cancelled. Lane and history are current; you can approve a fresh current cost now.`
        : `Cancelled ${cancelledCount} scheduled revision${cancelledCount === 1 ? '' : 's'}. Lane and history were reloaded; review the selected zones and save again.`
      dispatch({ type: 'reconciled', notice: staleDraft ? `${notice} The earlier zone draft was discarded because the lane changed.` : notice, preparedDraft: draft })
    }
    await client.invalidateQueries({ queryKey: [...scope, 'lanes'] })
  }

  async function resolveReviewedRevisions(pending: PendingCancellation, signal: AbortSignal) {
    const reviewedRevisions: ScheduledRevisionCancellationResolutionResponse['reviewedRevisions'] = []
    let last: ScheduledRevisionCancellationResolutionResponse | null = null
    for (let offset = 0; offset < pending.reviewedRevisionIds.length; offset += CANCELLATION_RESOLUTION_BATCH_SIZE) {
      const ids = pending.reviewedRevisionIds.slice(offset, offset + CANCELLATION_RESOLUTION_BATCH_SIZE)
      last = await resolveScheduledLaneCancellation(organizationId, laneId, {
        laneVersion: pending.reviewedLane?.version ?? 0,
        reviewedRevisionIds: ids,
      }, signal)
      reviewedRevisions.push(...last.reviewedRevisions)
      signal.throwIfAborted()
    }
    if (last === null) throw new Error('The reviewed scheduled costs could not be resolved.')
    return { ...last, reviewedRevisions }
  }

  function safeZoneDraft(pending: PendingCancellation, resolution: ScheduledRevisionCancellationResolutionResponse, currentLane: Lane) {
    if (!pending.draft || !pending.reviewedLane) return null
    const cancellationRecorded = resolution.reviewedRevisions.some(item => item.status === 'CANCELLED')
    const expectedVersion = pending.reviewedLane.version + (cancellationRecorded ? 1 : 0)
    return laneConfigurationMatches(pending.reviewedLane, resolution.lane, expectedVersion)
      && laneConfigurationMatches(pending.reviewedLane, currentLane, expectedVersion) ? pending.draft : null
  }

  function laneConfigurationMatches(reviewedLane: Lane, currentLane: Lane, expectedVersion: number) {
    return currentLane.version === expectedVersion
      && currentLane.originZoneId === reviewedLane.originZoneId
      && currentLane.destinationZoneId === reviewedLane.destinationZoneId
      && currentLane.active === reviewedLane.active
  }

  async function reconcileReviewedCancellation(pending: PendingCancellation, signal: AbortSignal) {
    let resolution = await resolveReviewedRevisions(pending, signal)
    let snapshot = await reloadLaneAndHistory(signal, pending.cancellationIds)
    if (snapshot.scheduled.length) {
      showFreshScheduleReview(pending, resolution, snapshot)
      return
    }
    if (resolution.decision === 'STILL_FUTURE_OR_CHANGED' || resolution.hasFutureRevisions) {
      resolution = await resolveReviewedRevisions(pending, signal)
      snapshot = await reloadLaneAndHistory(signal, pending.cancellationIds)
      if (snapshot.scheduled.length) {
        showFreshScheduleReview(pending, resolution, snapshot)
        return
      }
      if (resolution.decision === 'STILL_FUTURE_OR_CHANGED' || resolution.hasFutureRevisions) {
        throw new Error('The lane still has an unresolved future schedule. Its state remains blocked until a fresh review is available.')
      }
    }

    const draft = safeZoneDraft(pending, resolution, snapshot.lane)
    const staleDraft = pending.draft !== null && draft === null
    const cancelledCount = resolution.reviewedRevisions.filter(item => item.status === 'CANCELLED').length
    if (resolution.decision === 'CANCELLED_CONFIRMED' && cancelledCount === pending.reviewedRevisionIds.length) {
      await finishCancellation({ lane: pending.reviewedLane ?? snapshot.lane, revisions: [], draft, kind: draft ? pending.kind : 'independent' }, snapshot, cancelledCount)
      if (staleDraft) dispatch({ type: 'notice', value: 'Cancellation records are confirmed and lane history is current. The lane changed during recovery, so the earlier zone draft was discarded.' })
      return
    }

    const expiredCount = resolution.reviewedRevisions.filter(item => item.status === 'EXPIRED_WITHOUT_CANCELLATION').length
    const notice = cancelledCount && expiredCount
      ? `${cancelledCount} reviewed schedule${cancelledCount === 1 ? '' : 's'} have cancellation records; ${expiredCount} became effective without cancellation. Lane and history were reloaded.`
      : expiredCount
        ? `${expiredCount} reviewed scheduled cost${expiredCount === 1 ? '' : 's'} became effective without a cancellation record. Lane and history were reloaded.`
        : 'The reviewed schedule state is terminal. Lane and history were reloaded.'
    dispatch({ type: 'reconciled', notice: staleDraft ? `${notice} The earlier zone draft was discarded because the lane changed.` : notice, preparedDraft: draft })
    await client.invalidateQueries({ queryKey: [...scope, 'lanes'] })
  }

  function showFreshScheduleReview(pending: PendingCancellation, resolution: ScheduledRevisionCancellationResolutionResponse, snapshot: Awaited<ReturnType<typeof reloadLaneAndHistory>>) {
    const draft = safeZoneDraft(pending, resolution, snapshot.lane)
    dispatch({ type: 'review', lane: snapshot.lane, revisions: snapshot.scheduled, draft, kind: draft ? pending.kind : 'independent' })
    if (pending.draft && !draft) dispatch({ type: 'notice', value: 'The lane changed while cancellation was being recovered. The earlier zone draft was discarded; review the current scheduled costs.' })
    else dispatch({ type: 'notice', value: 'Future scheduled costs remain. Review the refreshed list before making another change.' })
  }

  async function reload() {
    if (commandLock.current || state.unresolved !== null || state.phase === 'NEEDS_RECONCILIATION') return
    dispatch({ type: 'close-review' })
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
      if (pending.reviewedRevisionIds.length) {
        await reconcileReviewedCancellation(pending, signal)
        return
      }
      const snapshot = await reloadLaneAndHistory(signal, pending.cancellationIds)
      if (pending.draft && pending.reviewedLane && snapshot.lane.version !== pending.reviewedLane.version) {
        if (snapshot.scheduled.length) {
          dispatch({ type: 'review', lane: snapshot.lane, revisions: snapshot.scheduled, draft: null, kind: 'independent' })
        } else {
          dispatch({ type: 'reconciled', notice: 'The lane configuration changed while its state was being recovered. Review the reloaded configuration before editing again.' })
        }
        dispatch({ type: 'notice', value: 'The lane configuration changed while its state was being recovered. The earlier zone draft was discarded; review the current configuration before editing again.' })
      } else if (pending.draft && snapshot.scheduled.length) {
        dispatch({ type: 'review', lane: snapshot.lane, revisions: snapshot.scheduled, draft: pending.draft, kind: pending.kind })
      } else if (pending.draft) {
        dispatch({
          type: 'reconciled',
          notice: 'Lane and history were reloaded. No future scheduled costs remain; review and save the selected zones separately.',
          preparedDraft: pending.draft,
        })
      } else dispatch({ type: 'reconciled', notice: 'The lane and history now show the latest saved state.' })
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
