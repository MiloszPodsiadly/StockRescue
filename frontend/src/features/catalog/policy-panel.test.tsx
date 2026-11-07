import { cleanup, render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { FoodSafetyPolicyVersion, PolicyApprovalEvidence } from '@/api/food-safety'
import type { Organization } from '@/api/organizations'
import { OrganizationContext } from '../organizations/organization-context'
import { TenantScope } from '../organizations/tenant-requests'
import { PolicyPanel } from './policy-panel'

const api = vi.hoisted(() => ({ list: vi.fn(), approval: vi.fn() }))
vi.mock('@/api/food-safety', () => ({
  getFoodSafetyPolicyApprovalEvidence: api.approval,
  listFoodSafetyPolicyVersions: api.list,
  recordFoodSafetyPolicyReview: vi.fn(),
  recordLotSafetyEvidence: vi.fn(),
}))

const organization: Organization = { id: 'org', name: 'Cooperative', membershipId: 'membership', role: 'ORG_ADMIN', createdAt: '2026-11-01T00:00:00Z' }
const policy: FoodSafetyPolicyVersion = { id: 'policy-v1', profileId: 'profile', productCategory: 'BAKERY', temperatureClass: 'AMBIENT', version: 1,
  partialDateRule: 'NO_NORMALIZATION', approvalStatus: 'UNVERIFIED', reviewProvenance: { source: 'Review', reference: 'policy-doc' }, recordedByMembershipId: 'membership',
  recordedAt: '2026-11-06T10:00:00Z', reason: 'Initial policy review' }
const notSubmitted: PolicyApprovalEvidence = { approvalId: null, verification: { outcome: 'NOT_SUBMITTED', policyVersion: 1, policyDigest: '0'.repeat(64), issuerId: null, keyId: null,
  allowedOperations: [], issuedAt: null, validFrom: null, validUntil: null, evidenceReference: null, recordedAt: null, revoked: false, revokedAt: null, revokedByMembershipId: null, revocationReason: null } }

beforeEach(() => {
  vi.resetAllMocks()
  api.list.mockResolvedValue({ current: policy, items: [policy], hasMore: false })
  api.approval.mockResolvedValue(notSubmitted)
})
afterEach(cleanup)

function panel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><TenantScope value={['tenant', 'actor', 'org']}><OrganizationContext value={organization}>
    <PolicyPanel organizationId="org" category="BAKERY" temperature="AMBIENT" />
  </OrganizationContext></TenantScope></QueryClientProvider>)
}

it('labels evidence as independently verified only when the backend verification outcome is verified', async () => {
  panel()
  expect(await screen.findByText('No signed approval evidence is recorded for this policy version.')).toBeVisible()
  expect(screen.queryByText(/Cryptographic approval evidence is verified/)).not.toBeInTheDocument()

  cleanup()
  api.approval.mockResolvedValueOnce({ approvalId: 'approval', verification: { ...notSubmitted.verification, outcome: 'VERIFIED', issuerId: 'test-issuer', keyId: 'test-key',
    allowedOperations: ['SALE'], issuedAt: '2026-11-06T10:00:00Z', validFrom: '2026-11-06T10:00:00Z', validUntil: '2026-11-07T10:00:00Z', evidenceReference: 'external-case:1', recordedAt: '2026-11-06T10:01:00Z' } })
  panel()
  expect(await screen.findByText(/Cryptographic approval evidence is verified for this exact policy version/)).toBeVisible()
  expect(screen.getByText('test-issuer')).toBeVisible()
})
