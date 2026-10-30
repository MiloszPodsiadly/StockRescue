import { createContext, useContext } from 'react'

export const TenantRequests = createContext<(() => AbortSignal) | null>(null)
export type TenantKey = readonly ['tenant', string, string]
export const TenantScope = createContext<TenantKey | null>(null)
export function useTenantKey() {
  const key = useContext(TenantScope)
  if (!key) throw new Error('Tenant query keys require an authenticated workspace')
  return key
}
export function useTenantRequests() {
  const signal = useContext(TenantRequests)
  if (!signal) throw new Error('Tenant requests require an active workspace')
  return signal
}
