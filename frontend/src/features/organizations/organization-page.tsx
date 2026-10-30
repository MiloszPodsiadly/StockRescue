import { useLayoutEffect } from 'react'
import type { ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { StateMessage } from '@/components/state-message'
import { WorkspaceFrame } from '@/components/workspace-frame'
import { AppLink } from '@/components/app-link'
import { Button } from '@/components/ui/button'
import { ApiError, errorMessage } from '@/api/client'
import { subscribeAccessChanges } from '@/api/session-events'
import { getOrganization } from '@/api/organizations'
import { Workspace } from '../auth/workspace'
import { TenantBoundary } from './tenant-boundary'
import { useOrganizationSelection } from './use-organization-selection'
import { OrganizationPicker } from './organization-picker'
import { OrganizationContext } from './organization-context'

export function OrganizationPage({ organizationId, children }: { organizationId?: string, children: ReactNode }) {
  return <Workspace>{(account, onSignOut) => organizationId ? <ScopedOrganization key={`${account.id}:${organizationId}`} actorId={account.id} organizationId={organizationId} onSignOut={onSignOut}>{children}</ScopedOrganization> : <WorkspaceFrame onSignOut={onSignOut}><StateMessage state="unavailable" title="Organization unavailable" description="Open your organization from your account." /></WorkspaceFrame>}</Workspace>
}
function ScopedOrganization({ actorId, organizationId, onSignOut, children }: { actorId: string, organizationId: string, onSignOut: () => void, children: ReactNode }) {
  const navigate = useNavigate()
  const client = useQueryClient()
  const selection = useOrganizationSelection(actorId, async selected => { await navigate({ to: '/organizations/$organizationId/stores', params: { organizationId: selected.id } }) })
  const organization = useQuery({ queryKey: ['tenant', actorId, organizationId, 'organization'], queryFn: ({ signal }) => getOrganization(organizationId, signal), refetchOnWindowFocus: true, refetchInterval: 30000, gcTime: 0 })
  useLayoutEffect(() => subscribeAccessChanges(changed => {
    if (changed === organizationId) void client.invalidateQueries({ queryKey: ['tenant', actorId, organizationId, 'organization'] }, { cancelRefetch: false })
  }), [actorId, organizationId, client])
  useLayoutEffect(() => {
    if (organization.error instanceof ApiError && (organization.error.status === 403 || organization.error.status === 404)) {
      void client.cancelQueries({ queryKey: ['identity', actorId] })
      client.removeQueries({ queryKey: ['identity', actorId] })
    }
  }, [organization.error, actorId, client])
  const unavailable = organization.isError || selection.selectionError
  const ready = organization.data && !unavailable && !selection.switching
  return <WorkspaceFrame onSignOut={onSignOut} organizationId={ready ? organizationId : undefined} administrator={Boolean(ready && organization.data?.role === 'ORG_ADMIN')} sidebar={!unavailable && <OrganizationPicker selection={selection} current={organization.data} />}>
    {selection.switching ? <StateMessage state="loading" title="Checking organization selection" description="Your previous workspace has been closed." /> : unavailable ? <div className="space-y-4"><StateMessage state="unavailable" title="Organization unavailable" description={selection.selectionError || errorMessage(organization.error)} />{selection.selectionError ? <AppLink className="text-primary underline" href="/account">Reconcile your organizations</AppLink> : <Button onClick={() => { void organization.refetch() }}>Check access again</Button>}</div> : organization.isPending ? <StateMessage state="loading" title="Checking organization access" description="Resolving your current membership." /> : <div className="space-y-8"><div className="border-b border-border pb-6"><p className="text-xs font-semibold uppercase tracking-[0.18em] text-primary">Organization workspace</p><h1 className="mt-3 break-words text-3xl font-semibold">{organization.data.name}</h1></div><TenantBoundary key={`${actorId}:${organizationId}:${organization.data.membershipId}:${organization.data.role}`} actorId={actorId} organizationId={organizationId}><OrganizationContext value={organization.data}>{children}</OrganizationContext></TenantBoundary></div>}
  </WorkspaceFrame>
}
