import { useParams } from '@tanstack/react-router'
import { OrganizationPage } from '../organizations/organization-page'
import { MembersPanel } from './members-panel'

export function MembersPage() {
  const { organizationId } = useParams({ strict: false })
  return <OrganizationPage organizationId={organizationId}>{organizationId && <MembersPanel organizationId={organizationId} />}</OrganizationPage>
}
