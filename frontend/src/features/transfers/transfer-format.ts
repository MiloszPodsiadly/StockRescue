import type { TransferStatus } from '@/api/transfers'

export const transferStatusOptions: { value: TransferStatus | '', label: string }[] = [
  { value: '', label: 'All statuses' },
  { value: 'REQUESTED', label: 'Requested' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'PICKING', label: 'Picking' },
  { value: 'PICKED', label: 'Picked, not dispatched' },
  { value: 'IN_TRANSIT', label: 'In transit' },
  { value: 'PARTIALLY_RECEIVED', label: 'Partially received' },
  { value: 'RECONCILIATION_REQUIRED', label: 'Reconciliation required' },
  { value: 'RECEIVED', label: 'Received' },
  { value: 'CLOSED_WITH_DISCREPANCY', label: 'Closed with discrepancy' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'CANCELLED', label: 'Cancelled' },
]

export function transferStatusLabel(status: TransferStatus) {
  return transferStatusOptions.find(option => option.value === status)?.label ?? status.replaceAll('_', ' ').toLowerCase()
}

export function formatTransferAge(seconds: number) {
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor(seconds % 86400 / 3600)
  const minutes = Math.floor(seconds % 3600 / 60)
  if (days) return `${days}d ${hours}h`
  if (hours) return `${hours}h ${minutes}m`
  return `${Math.max(0, minutes)}m`
}
