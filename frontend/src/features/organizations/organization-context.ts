import { createContext, useContext } from 'react'
import type { Organization } from '@/api/organizations'

export const OrganizationContext = createContext<Organization | null>(null)
export function useOrganization() {
  const organization = useContext(OrganizationContext)
  if (!organization) throw new Error('Organization details require a current workspace')
  return organization
}
