import { useEffect, useLayoutEffect, useRef } from 'react'
import { flushSync } from 'react-dom'
import { Outlet, useRouterState } from '@tanstack/react-router'
import { useQueryClient } from '@tanstack/react-query'
import { clearCsrf } from './api/client'
import { subscribeSessionChanges } from './api/session-events'
import { sessionKey } from './features/auth/session'

export function RootLayout() {
  const client = useQueryClient()
  const pathname = useRouterState({ select: state => state.location.pathname })
  const previous = useRef(pathname)
  useLayoutEffect(() => {
    let generation = 0
    const unsubscribe = subscribeSessionChanges(change => {
      const current = ++generation
      clearCsrf()
      flushSync(() => { client.setQueryData(sessionKey, null) })
      void client.cancelQueries().then(() => {
        if (current !== generation) return
        client.removeQueries({ predicate: query => (query.queryKey[0] === 'identity' && query.queryKey[1] !== 'session') || query.queryKey[0] === 'tenant' })
        if (change === 'changed') void client.refetchQueries({ queryKey: sessionKey })
      })
    })
    return () => { generation++; unsubscribe() }
  }, [client])
  useEffect(() => {
    const title = pathname.endsWith('/storage-zones') ? 'Storage zones' : pathname.includes('/transport-lanes/') ? 'Cost history' : pathname.endsWith('/transport-lanes') ? 'Transport lanes' : pathname.endsWith('/members') ? 'Members and access' : pathname.endsWith('/settings') ? 'Settings' : pathname.includes('/stores/') ? 'Store details' : pathname.endsWith('/stores') ? 'Stores' : pathname === '/account' ? 'Your organizations' : pathname === '/sign-up' ? 'Create account' : pathname === '/sign-in' ? 'Sign in' : pathname === '/' ? 'Service status' : 'Workspace'
    document.title = `${title} · StockRescue`
    if (previous.current !== pathname) {
      previous.current = pathname
      const frame = requestAnimationFrame(() => { document.querySelector<HTMLElement>('main')?.focus() })
      return () => { cancelAnimationFrame(frame) }
    }
  }, [pathname])
  return <Outlet />
}
