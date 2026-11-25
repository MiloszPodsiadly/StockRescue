import type { TransferQueueItem } from '@/api/transfers'

export function reconcileTransferSummaries(values: TransferQueueItem[]): TransferQueueItem[] {
  const latest = new Map<string, TransferQueueItem>()
  for (const candidate of values) {
    const current = latest.get(candidate.id)
    if (!current || compareTransferSummaries(candidate, current) > 0) latest.set(candidate.id, candidate)
  }
  return [...latest.values()].sort((left, right) =>
    compareInstants(right.updatedAt, left.updatedAt) || compareText(right.id, left.id))
}

function compareTransferSummaries(left: TransferQueueItem, right: TransferQueueItem): number {
  if (left.version !== right.version) return left.version > right.version ? 1 : -1
  const updateTimeOrder = compareInstants(left.updatedAt, right.updatedAt)
  if (updateTimeOrder !== 0) return updateTimeOrder

  // Equal version and update time should be impossible for divergent snapshots. Resolve a true conflict
  // from a fixed field tuple so store/page arrival order never decides what the operator sees.
  const leftTieBreak = [left.status, left.createdAt, left.sourceStoreId, left.destinationStoreId,
    String(left.lineCount), String(left.outstandingQuantity)].join('\u0000')
  const rightTieBreak = [right.status, right.createdAt, right.sourceStoreId, right.destinationStoreId,
    String(right.lineCount), String(right.outstandingQuantity)].join('\u0000')
  return compareText(leftTieBreak, rightTieBreak)
}

function compareInstants(left: string, right: string): number {
  const leftMillis = Date.parse(left)
  const rightMillis = Date.parse(right)
  if (!Number.isFinite(leftMillis) || !Number.isFinite(rightMillis)) return compareText(left, right)
  if (leftMillis !== rightMillis) return leftMillis > rightMillis ? 1 : -1
  const leftMicrosAfterMillis = subMillisecondMicros(left)
  const rightMicrosAfterMillis = subMillisecondMicros(right)
  return leftMicrosAfterMillis === rightMicrosAfterMillis ? 0
    : leftMicrosAfterMillis > rightMicrosAfterMillis ? 1 : -1
}

function subMillisecondMicros(value: string): number {
  const fraction = /\.(\d+)(?:Z|[+-]\d{2}:\d{2})$/.exec(value)?.[1] ?? ''
  return Number(fraction.slice(3, 6).padEnd(3, '0') || '0')
}

function compareText(left: string, right: string): number {
  return left === right ? 0 : left > right ? 1 : -1
}
