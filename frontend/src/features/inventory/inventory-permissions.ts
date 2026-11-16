import type { InventoryAllocation } from '@/api/inventory'
import type { Store } from '@/api/stores'

export type OperationChoice = 'reserve' | 'release' | 'adjust' | 'writeOff' | 'quarantine' | 'hold' | 'releaseHold' | 'sale'

export function inventoryOperationChoices(allowedActions: Store['allowedActions']) {
  const actions: { value: OperationChoice, label: string, permission: Store['allowedActions'][number] }[] = [
    { value: 'reserve', label: 'Reserve stock', permission: 'RESERVE_STOCK' },
    { value: 'release', label: 'Release reservation', permission: 'RELEASE_RESERVATION' },
    { value: 'adjust', label: 'Adjust physical count', permission: 'ADJUST_STOCK' },
    { value: 'writeOff', label: 'Write off stock', permission: 'WRITE_OFF_STOCK' },
    { value: 'quarantine', label: 'Quarantine stock', permission: 'QUARANTINE_STOCK' },
    { value: 'hold', label: 'Place other hold', permission: 'QUARANTINE_STOCK' },
    { value: 'releaseHold', label: 'Release hold', permission: 'QUARANTINE_STOCK' },
    { value: 'sale', label: 'Record manual sale', permission: 'RECORD_SALE' },
  ]
  return actions.filter(action => allowedActions.includes(action.permission))
}

export function inventoryAllocationsForOperation(allocations: InventoryAllocation[], choice: OperationChoice, manager: boolean) {
  return allocations.filter(item => item.remaining > 0 && item.state === 'ACTIVE' &&
    (choice === 'release' ? item.bucket === 'RESERVED' : choice === 'releaseHold' ? item.bucket !== 'RESERVED' : choice === 'writeOff') &&
    (manager || item.ownedByCurrentUser))
}
