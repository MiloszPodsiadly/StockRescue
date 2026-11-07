import { useEffect, useRef } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { subscribeAccessChanges } from '@/api/session-events'
import { useTenantKey } from '../organizations/tenant-requests'
import { ApiError } from '@/api/client'

export const catalogRefresh = { refetchInterval: 30000, refetchOnWindowFocus: true, staleTime: 0 } as const
export function accessFailed(error: unknown) { return error instanceof ApiError && [401, 403, 404].includes(error.status) }
export function useCatalogScope() {
  const scope = useTenantKey()
  const client = useQueryClient()
  const [kind, actor, org] = scope
  const refreshing = useRef(false)
  useEffect(() => subscribeAccessChanges(changed => {
    if (changed !== org || refreshing.current) return
    refreshing.current = true
    void client.invalidateQueries({ queryKey: [kind, actor, org, 'catalog'] }, { cancelRefetch: false }).finally(() => { refreshing.current = false })
  }), [kind, actor, org, client])
  return [...scope, 'catalog'] as const
}
