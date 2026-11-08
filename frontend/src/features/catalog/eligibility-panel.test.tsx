import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import type { Eligibility } from '@/api/food-safety'
import { eligibilityFixture, verifiedApproval, verifiedClassification } from '@/test/catalog-fixtures'
import { EligibilityResult } from './eligibility-panel'
import { allowedAssessmentNotice, platformRulesetDigest, platformRulesetId } from './eligibility-ruleset'

afterEach(cleanup)

const currentOperatorConfirmation: NonNullable<Eligibility['operatorClassificationConfirmation']> = {
  id: 'operator-confirmation', organizationId: 'org', productId: 'product', sku: 'MILK',
  expectedProductVersion: 0, productVersion: 0, originalCategory: 'DAIRY', confirmedCategory: 'BAKERY',
  originalTemperatureClass: 'AMBIENT', confirmedTemperatureClass: 'AMBIENT',
  productProfile: 'PACKAGED_SHELF_STABLE_DRY_BAKERY', sourceType: 'ORIGINAL_PACKAGE',
  evidenceReference: 'package:category-and-storage-label', actorUserId: 'owner',
  recordedByMembershipId: 'membership', requestId: 'request', reason: 'Reviewed original package',
  recordedAt: '2026-11-06T11:30:00Z',
}

function readyAssessment(overrides: Partial<Eligibility> = {}): Eligibility {
  return {
    ...eligibilityFixture,
    decision: 'ALLOWED', reasonCodes: [], nextAction: 'NO_ADDITIONAL_ACTION',
    evaluatorVersion: platformRulesetId, rulesetId: platformRulesetId, rulesetDigest: platformRulesetDigest,
    assessmentNotice: allowedAssessmentNotice,
    productCategory: 'BAKERY', productTemperatureClass: 'AMBIENT',
    operatorClassificationStatus: 'CURRENT_OPERATOR_CONFIRMED',
    operatorClassificationConfirmation: currentOperatorConfirmation,
    classificationVerification: { ...eligibilityFixture.classificationVerification!, currentCategory: 'BAKERY' },
    evidence: { ...eligibilityFixture.evidence, policy: 'PLATFORM_RULESET', classification: 'OPERATOR_CONFIRMED' },
    ...overrides,
  }
}

it('keeps missing source-backed attributes visible while the platform ruleset remains the assessment basis', () => {
  render(<EligibilityResult value={eligibilityFixture} />)
  expect(screen.getByRole('heading', { name: 'Requires review' })).toBeVisible()
  expect(screen.getByText('No current source-backed confirmation of category, declared temperature and product profile is recorded for this product revision.')).toBeVisible()
  expect(screen.getByText('Review the cited source and confirm the current category, declared temperature and product profile for this product version.')).toBeVisible()
  expect(screen.getByText('NOT_SUBMITTED')).toBeVisible()
  expect(screen.getByText('platform ruleset')).toBeVisible()
  expect(screen.queryByRole('heading', { name: 'Allowed' })).not.toBeInTheDocument()
})

it('explains hard denial before incomplete evidence and shows the store local date', () => {
  render(<EligibilityResult value={{ ...eligibilityFixture, decision: 'DENIED', reasonCodes: ['LOT_RECALLED', 'DATE_LABEL_MISSING'], nextAction: 'DO_NOT_EXECUTE' }} />)
  expect(screen.getByRole('heading', { name: 'Denied' })).toBeVisible()
  expect(screen.getAllByRole('listitem').map(item => item.textContent)).toEqual(['A recall is recorded for this lot.', 'The original date label is missing.'])
  expect(screen.getByText('Europe/Warsaw')).toBeVisible()
  expect(screen.getByText('2026-11-06')).toBeVisible()
})

it('shows the limited result only when all source-backed attributes and lot evidence match the active ruleset', () => {
  render(<EligibilityResult value={readyAssessment()} />)
  expect(screen.getByRole('heading', { name: 'Passed limited checks' })).toBeVisible()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(screen.getByText(allowedAssessmentNotice)).toBeVisible()
  expect(screen.getByText('packaged shelf stable dry bakery')).toBeVisible()
  expect(screen.getByText(/not independent verification/)).toBeVisible()
})

it('does not present an outdated temperature confirmation as a passing assessment', () => {
  render(<EligibilityResult value={readyAssessment({
    operatorClassificationConfirmation: { ...currentOperatorConfirmation, confirmedTemperatureClass: 'CHILLED' },
  })} />)
  expect(screen.getByRole('alert')).toHaveTextContent('does not satisfy every approval and evidence check')
  expect(screen.getByRole('heading', { name: 'Requires review' })).toBeVisible()
})

for (const productProfile of ['REFRIGERATION_REQUIRED', 'CREAM_FILLED_OR_PERISHABLE', 'NOT_SHELF_STABLE',
  'STORAGE_INSTRUCTION_MISSING_OR_AMBIGUOUS', 'CONTRADICTORY_STORAGE_EVIDENCE', 'OUTSIDE_CURRENT_PILOT'] as const) {
  it(`keeps ${productProfile} outside the limited positive scope even when the backend says allowed`, () => {
    render(<EligibilityResult value={readyAssessment({
      operatorClassificationConfirmation: { ...currentOperatorConfirmation, productProfile },
    })} />)
    expect(screen.getByRole('alert')).toHaveTextContent('does not satisfy every approval and evidence check')
    expect(screen.getByRole('heading', { name: 'Requires review' })).toBeVisible()
  })
}

it('does not let external approval or a signed category replace current operator source evidence', () => {
  render(<EligibilityResult value={readyAssessment({
    assessmentBasis: 'SIGNED_EXTERNAL_POLICY', approvalVerification: verifiedApproval,
    classificationVerification: { ...verifiedClassification, currentCategory: 'BAKERY', signedCategory: 'BAKERY' },
    operatorClassificationStatus: 'NOT_RECORDED', operatorClassificationConfirmation: null,
    evidence: { ...eligibilityFixture.evidence, policy: 'INDEPENDENTLY_VERIFIED', classification: 'INDEPENDENTLY_VERIFIED' },
  })} />)
  expect(screen.getByRole('alert')).toHaveTextContent('does not satisfy every approval and evidence check')
  expect(screen.getByRole('heading', { name: 'Requires review' })).toBeVisible()
})

it('fails closed when the response names an unknown ruleset digest or operation', () => {
  const unknownDigest = readyAssessment({ rulesetDigest: '0'.repeat(64) })
  const wrongOperation = readyAssessment({ approvalVerification: { ...verifiedApproval, allowedOperations: ['TRANSFER'] },
    assessmentBasis: 'SIGNED_EXTERNAL_POLICY', evidence: { ...eligibilityFixture.evidence, policy: 'INDEPENDENTLY_VERIFIED', classification: 'INDEPENDENTLY_VERIFIED' },
    classificationVerification: { ...verifiedClassification, currentCategory: 'BAKERY', signedCategory: 'BAKERY' } })
  for (const value of [unknownDigest, wrongOperation]) {
    const { unmount } = render(<EligibilityResult value={value} />)
    expect(screen.getByRole('alert')).toHaveTextContent('does not satisfy every approval and evidence check')
    expect(screen.getByRole('heading', { name: 'Requires review' })).toBeVisible()
    unmount()
  }
})
