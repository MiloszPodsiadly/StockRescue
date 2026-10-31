import { laneHistory } from '@/api/configuration'
import type { Lane, LaneUpdate, Revision } from '@/api/configuration'

export type ScheduleReview = {
  lane: Lane
  revisions: Revision[]
  draft: LaneUpdate | null
  kind: 'zone-change' | 'independent'
}

export async function loadScheduledRevisions(organizationId: string, laneId: string, asOf: string, signal: AbortSignal) {
  const scheduled: Revision[] = []
  let offset = 0
  while (true) {
    const page = await laneHistory(organizationId, laneId, offset, signal, 100)
    signal.throwIfAborted()
    for (const revision of page.items) {
      if (revision.cancellation || !isAfterInstant(revision.effectiveAt, asOf)) continue
      scheduled.push(revision)
    }
    if (!page.hasMore) return scheduled
    if (!page.items.length || offset >= 10000) throw new Error('The complete scheduled cost list could not be loaded.')
    offset += page.items.length
  }
}

export async function findCancelledRevisionIds(organizationId: string, laneId: string, revisionIds: string[], signal: AbortSignal) {
  const remaining = new Set(revisionIds)
  const cancelled = new Set<string>()
  let offset = 0
  while (remaining.size) {
    const page = await laneHistory(organizationId, laneId, offset, signal, 100)
    signal.throwIfAborted()
    for (const revision of page.items) {
      if (revision.cancellation && remaining.delete(revision.id)) cancelled.add(revision.id)
    }
    if (!remaining.size || !page.hasMore || !page.items.length || offset >= 10000) return cancelled
    offset += page.items.length
  }
  return cancelled
}

export async function confirmCancellations(organizationId: string, laneId: string, cancellations: Array<{ id: string, revisionId: string }>, signal: AbortSignal) {
  const expected = new Map(cancellations.map(item => [item.revisionId, item.id]))
  const found = new Map<string, string>()
  let offset = 0
  while (found.size < expected.size) {
    const page = await laneHistory(organizationId, laneId, offset, signal, 100)
    signal.throwIfAborted()
    for (const revision of page.items) {
      const expectedCancellationId = expected.get(revision.id)
      if (expectedCancellationId && revision.cancellation?.id === expectedCancellationId) found.set(revision.id, expectedCancellationId)
    }
    if (found.size === expected.size) return
    if (!page.hasMore || !page.items.length || offset >= 10000) break
    offset += page.items.length
  }
  throw new Error('The saved cancellation evidence could not be confirmed in lane history.')
}

export function isAfterInstant(value: string, boundary: string) {
  return instantNanoseconds(value) > instantNanoseconds(boundary)
}

function instantNanoseconds(value: string) {
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$/.exec(value)
  if (!match) throw new Error('A saved scheduling timestamp is invalid.')
  const wholeSecond = Date.parse(`${match[1]}${match[3]}`)
  if (!Number.isFinite(wholeSecond)) throw new Error('A saved scheduling timestamp is invalid.')
  return BigInt(wholeSecond) * 1_000_000n + BigInt((match[2] ?? '').padEnd(9, '0'))
}
