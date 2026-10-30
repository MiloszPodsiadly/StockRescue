import { useLayoutEffect, useRef } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useTenantKey, useTenantRequests } from './tenant-requests'

export function useTenantCommand(onSaved: () => Promise<void>) {
  const tenant = useTenantRequests()
  const key = useTenantKey()
  const local = useRef<AbortController | null>(null)
  useLayoutEffect(() => () => { local.current?.abort() }, [])
  return useMutation({
    mutationKey: [...key, 'configuration-command'], retry: false, gcTime: 0,
    mutationFn: async (command: (signal: AbortSignal) => Promise<unknown>) => {
      const controller = new AbortController()
      local.current = controller
      const signal = AbortSignal.any([tenant(), controller.signal])
      await command(signal)
      signal.throwIfAborted()
      await onSaved()
    },
  })
}
