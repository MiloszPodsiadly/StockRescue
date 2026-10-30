import { useLayoutEffect, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { errorMessage } from '@/api/client'
import { getActiveOrganization, listOrganizations, selectOrganization } from '@/api/organizations'
import type { Organization } from '@/api/organizations'

export function useOrganizationSelection(actorId: string, onSelected?: (organization: Organization) => Promise<void>) {
  const client = useQueryClient()
  const [offset, setOffset] = useState(0)
  const [switching, setSwitching] = useState(false)
  const [selectionError, setSelectionError] = useState('')
  const changing = useRef(false)
  const request = useRef<AbortController | null>(null)
  useLayoutEffect(() => () => { request.current?.abort() }, [])
  const activeKey = ['identity', actorId, 'active-organization'] as const
  const listKey = ['identity', actorId, 'organizations'] as const
  const active = useQuery({ queryKey: activeKey, queryFn: ({ signal }) => getActiveOrganization(signal), retry: false, staleTime: 0, gcTime: 0, refetchOnWindowFocus: true })
  const memberships = useQuery({ queryKey: [...listKey, offset], queryFn: ({ signal }) => listOrganizations(offset, signal), retry: false, staleTime: 0, gcTime: 0, refetchOnWindowFocus: true })
  async function change(organizationId: string) {
    if (changing.current) return
    changing.current = true
    const controller = new AbortController(); request.current?.abort(); request.current = controller
    // Close the previous feature boundary, including its requests, before awaiting
    // the server. A lost response must not restore a guessed previous workspace.
    flushSync(() => { setSwitching(true); setSelectionError('') })
    try {
      await client.cancelQueries({ queryKey: activeKey })
      const selected = await selectOrganization(organizationId, controller.signal)
      controller.signal.throwIfAborted()
      const organizationKey = ['tenant', actorId, selected.id, 'organization'] as const
      await client.cancelQueries({ queryKey: organizationKey, exact: true })
      controller.signal.throwIfAborted()
      client.setQueriesData({ queryKey: organizationKey, exact: true }, selected)
      client.setQueryData(activeKey, selected)
      void client.invalidateQueries({ queryKey: listKey })
      await onSelected?.(selected)
    } catch (error) {
      if (controller.signal.aborted) return
      client.setQueryData(activeKey, null)
      setSelectionError(errorMessage(error))
    } finally {
      if (!controller.signal.aborted) { changing.current = false; setSwitching(false) }
    }
  }
  function reload() {
    setSelectionError('')
    void client.invalidateQueries({ queryKey: activeKey })
    void client.invalidateQueries({ queryKey: listKey })
  }
  return { active, memberships, offset, setOffset, switching, selectionError, change, reload }
}
