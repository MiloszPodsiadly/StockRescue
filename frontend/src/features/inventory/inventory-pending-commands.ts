import { useCallback, useSyncExternalStore } from 'react'
import type { TenantKey } from '../organizations/tenant-requests'

export type PendingCommand<T> = {
  payload: T
  phase: 'in-flight' | 'unknown' | 'rejected'
  message?: string
}

type Entry = { tenant: TenantKey, command: PendingCommand<unknown> }
const entries = new Map<string, Entry>()
const listeners = new Map<string, Set<() => void>>()
let activeTenant: TenantKey | null | undefined

function keyFor(tenant: TenantKey, storeId: string, subject: string) {
  return JSON.stringify([tenant, storeId, subject])
}

function notify(key: string) {
  listeners.get(key)?.forEach(listener => listener())
}

export function usePendingInventoryCommand<T>(tenant: TenantKey, storeId: string, subject: string) {
  const key = keyFor(tenant, storeId, subject)
  const subscribe = useCallback((listener: () => void) => {
    const current = listeners.get(key) ?? new Set<() => void>()
    current.add(listener)
    listeners.set(key, current)
    return () => {
      current.delete(listener)
      if (current.size === 0) listeners.delete(key)
    }
  }, [key])
  const snapshot = useCallback(() => entries.get(key)?.command as PendingCommand<T> | undefined, [key])
  return useSyncExternalStore(subscribe, snapshot, () => undefined)
}

export function savePendingInventoryCommand<T>(tenant: TenantKey, storeId: string, subject: string, command: PendingCommand<T>) {
  if (activeTenant === null || (activeTenant && JSON.stringify(activeTenant) !== JSON.stringify(tenant))) return
  const key = keyFor(tenant, storeId, subject)
  entries.set(key, { tenant, command: command as PendingCommand<unknown> })
  notify(key)
}

export function clearPendingInventoryCommand(tenant: TenantKey, storeId: string, subject: string) {
  const key = keyFor(tenant, storeId, subject)
  entries.delete(key)
  notify(key)
}

export function clearInventoryCommandsForTenant(tenant?: TenantKey) {
  const tenantKey = tenant && JSON.stringify(tenant)
  for (const [key, entry] of entries) {
    if (!tenantKey || JSON.stringify(entry.tenant) === tenantKey) {
      entries.delete(key)
      notify(key)
    }
  }
  if (!tenant || (activeTenant && JSON.stringify(activeTenant) === tenantKey)) activeTenant = null
}

export function activateInventoryTenant(tenant: TenantKey) {
  if (activeTenant && JSON.stringify(activeTenant) !== JSON.stringify(tenant)) clearInventoryCommandsForTenant(activeTenant)
  activeTenant = tenant
}
