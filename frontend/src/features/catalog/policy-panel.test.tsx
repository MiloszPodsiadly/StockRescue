import { cleanup, render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { FoodSafetyPolicyVersion } from '@/api/food-safety'
import type { Organization } from '@/api/organizations'
import { OrganizationContext } from '../organizations/organization-context'
import { TenantScope } from '../organizations/tenant-requests'
import { PolicyPanel } from './policy-panel'

const api = vi.hoisted(() => ({ list: vi.fn() }))
vi.mock('@/api/food-safety', () => ({
  listFoodSafetyPolicyVersions: api.list,
  recordFoodSafetyPolicyReview: vi.fn(),
  recordLotSafetyEvidence: vi.fn(),
}))

const organization: Organization = { id: 'org', name: 'Cooperative', membershipId: 'membership', role: 'ORG_ADMIN', createdAt: '2026-11-01T00:00:00Z' }
const policy: FoodSafetyPolicyVersion = { id: 'policy-v1', profileId: 'profile', productCategory: 'BAKERY', temperatureClass: 'AMBIENT', version: 1,
  partialDateRule: 'NO_NORMALIZATION', reviewStatus: 'RECORDED_UNVERIFIED', reviewProvenance: { source: 'Review', reference: 'policy-doc' }, recordedByMembershipId: 'membership',
  recordedAt: '2026-11-06T10:00:00Z', reason: 'Initial policy review' }

beforeEach(() => {
  vi.resetAllMocks()
  api.list.mockResolvedValue({ current: policy, items: [policy], hasMore: false })
})
afterEach(cleanup)

function panel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><TenantScope value={['tenant', 'actor', 'org']}><OrganizationContext value={organization}>
    <PolicyPanel organizationId="org" category="BAKERY" temperature="AMBIENT" />
  </OrganizationContext></TenantScope></QueryClientProvider>)
}

it('shows policy reviews as source-attributed history that does not approve operations', async () => {
  panel()
  expect(await screen.findByText('recorded unverified')).toBeVisible()
  expect(screen.getByText(/They do not change the built-in rules or approve stock operations/)).toBeVisible()
  expect(screen.getByText(/policy-doc/)).toBeVisible()
})
