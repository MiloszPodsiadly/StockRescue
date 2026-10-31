import { StateMessage } from '@/components/state-message'
import { Button } from '@/components/ui/button'
import { errorMessage } from '@/api/client'
import { OnboardingForm } from './onboarding-form'
import { OrganizationPanel } from './organization-panel'
import { OrganizationPicker } from './organization-picker'
import { useOrganizationSelection } from './use-organization-selection'

export function OrganizationWorkspace({ actorId }: { actorId: string }) {
  const selection = useOrganizationSelection(actorId)
  const { memberships, active, switching, selectionError } = selection
  if (memberships.isPending || active.isPending) return <StateMessage state="loading" title="Loading your organizations" description="Checking your current memberships." />
  if (memberships.isError || active.isError) return <div className="space-y-4"><StateMessage state="error" title="Unable to load organizations" description={errorMessage(memberships.error ?? active.error)} /><Button onClick={selection.reload}>Try again</Button></div>
  const options = memberships.data.items
  return <div className="mt-10 space-y-7" aria-busy={switching}>
    <div className="rounded-2xl border border-border bg-card p-5 sm:p-7"><OrganizationPicker selection={selection} /></div>
    {selectionError && <p role="alert" className="rounded-xl border border-destructive/30 p-4 text-sm text-destructive">{selectionError}</p>}
    {switching || active.isFetching ? <StateMessage state="loading" title="Checking organization selection" description="Your previous workspace has been closed." /> : active.data ? <OrganizationPanel key={active.data.id} actorId={actorId} organizationId={active.data.id} /> : <StateMessage state="empty" title={options.length ? 'Select an organization' : 'No active organization memberships'} description={options.length ? 'Choose a workspace to see your current access.' : 'Create your organization below, or ask an administrator for an invitation.'} />}
    {selection.offset === 0 && !options.length && !memberships.isFetching && !switching && <OnboardingForm actorId={actorId} onCreated={async organization => { await selection.change(organization.id) }} onReload={selection.reload} />}
  </div>
}
