import { useCallback, useLayoutEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { TenantRequests, TenantScope } from './tenant-requests'

export function TenantBoundary({ actorId, organizationId, children }: { actorId: string, organizationId: string, children: ReactNode }) {
  const [client] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } }))
  const requests = useRef(new AbortController())
  const signal = useCallback(() => requests.current.signal, [])
  useLayoutEffect(() => {
    if (requests.current.signal.aborted) requests.current = new AbortController()
    return () => {
      requests.current.abort()
      void client.cancelQueries()
      client.clear()
    }
  }, [client])
  return <QueryClientProvider client={client}><TenantScope value={['tenant', actorId, organizationId]}><TenantRequests value={signal}>{children}</TenantRequests></TenantScope></QueryClientProvider>
}
