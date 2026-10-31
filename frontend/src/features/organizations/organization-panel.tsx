import { AppLink } from '@/components/app-link'
import { useQuery } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { StateMessage } from '@/components/state-message'
import { ApiError, errorMessage } from '@/api/client'
import { getEffectivePermissions } from '@/api/organizations'
import { TenantBoundary } from './tenant-boundary'
import { useTenantKey } from './tenant-requests'

export function OrganizationPanel({ actorId, organizationId }: { actorId: string, organizationId: string }) {
  return <TenantBoundary actorId={actorId} organizationId={organizationId}><OrganizationDetails organizationId={organizationId} /></TenantBoundary>
}

function OrganizationDetails({ organizationId }: { organizationId: string }) {
  const key = useTenantKey()
  const permissions = useQuery({
    queryKey: [...key, 'effective-permissions'],
    queryFn: async ({ signal }) => {
      const result = await getEffectivePermissions(signal)
      if (result.organization.id !== organizationId) throw new Error('Organization selection changed. Reload organizations to continue.')
      return result
    },
    refetchOnWindowFocus: true,
    staleTime: 0,
  })
  if (permissions.isPending || permissions.isFetching) return <StateMessage state="loading" title="Checking organization access" description="Loading your current permissions." />
  if (permissions.isError) return <div className="space-y-4"><StateMessage state="unavailable" title="Organization unavailable" description={errorMessage(permissions.error)} />{permissions.error instanceof ApiError && permissions.error.status === 401 ? <AppLink href="/sign-in" className="inline-flex text-primary underline">Sign in again</AppLink> : <Button variant="outline" onClick={() => { void permissions.refetch() }}>Check access again</Button>}</div>
  const { organization, allStoreActions, storeGrants, organizationActions } = permissions.data
  return (
    <section aria-labelledby="organization-heading" className="rounded-2xl border border-border bg-card p-6 sm:p-8">
      <p className="text-xs font-semibold uppercase tracking-[0.18em] text-primary">Active organization</p>
      <h2 id="organization-heading" className="mt-3 break-words text-3xl font-semibold tracking-tight">{organization.name}</h2>
      <dl className="mt-7 grid gap-6 sm:grid-cols-2">
        <div><dt className="text-sm text-muted-foreground">Your organization role</dt><dd className="mt-2 font-medium">{organization.role === 'ORG_ADMIN' ? 'Org Admin' : 'Member'}</dd></div>
        <div><dt className="text-sm text-muted-foreground">Store access</dt><dd className="mt-2 font-medium">{allStoreActions.includes('READ_STORE') ? 'All stores in this organization' : `${storeGrants.length} assigned ${storeGrants.length === 1 ? 'store' : 'stores'}`}</dd></div>
      </dl>
      <p className="mt-7 border-t border-border pt-6 leading-relaxed text-muted-foreground">Organization access and store assignments are independent. Store Managers can edit their assigned stores; Inventory Operators and Read-only Analysts currently have read access.</p>
      <Button variant="outline" className="mt-5" onClick={() => { void permissions.refetch() }}>Check access again</Button>
      <AppLink href={`/organizations/${organizationId}/stores`} className="mt-5 inline-flex rounded-xl bg-primary px-5 py-3 font-medium text-primary-foreground">Open stores</AppLink>
      {organizationActions.includes('MANAGE_TRANSPORT_LANES') && <AppLink href={`/organizations/${organizationId}/transport-lanes`} className="mt-5 inline-flex rounded-xl bg-primary px-5 py-3 font-medium text-primary-foreground">Transport lanes</AppLink>}
      {organizationActions.includes('MANAGE_ACCESS') && <AppLink href={`/organizations/${organizationId}/members`} className="mt-5 inline-flex rounded-xl bg-primary px-5 py-3 font-medium text-primary-foreground">Manage members</AppLink>}
    </section>
  )
}
