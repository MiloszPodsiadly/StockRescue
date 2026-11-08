import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { eligibilityFixture, verifiedApproval, verifiedClassification } from '@/test/catalog-fixtures'
import { EligibilityResult } from './eligibility-panel'

afterEach(cleanup)
it('keeps missing classification evidence visible while the platform ruleset remains the assessment basis', () => {
  render(<EligibilityResult value={eligibilityFixture} />)
  expect(screen.getByRole('heading', { name: 'Requires review' })).toBeVisible()
  expect(screen.getByText('No current classification evidence is recorded for this product revision.')).toBeVisible()
  expect(screen.getByText('Review the product source and record a version-bound operator classification confirmation.')).toBeVisible()
  expect(screen.getByText('NOT_SUBMITTED')).toBeVisible()
  expect(screen.getByText('Recorded, unverified')).toBeVisible()
  expect(screen.getAllByText('not reported in reviewed evidence')).toHaveLength(2)
  expect(screen.getAllByText(/supplier-notice \(operator-supplied\)/)).toHaveLength(2)
  expect(screen.getByText('platform ruleset')).toBeVisible()
  expect(screen.getByText('2 · policy-version')).toBeVisible()
  expect(screen.queryByRole('heading', { name: 'Allowed' })).not.toBeInTheDocument()
})
it('explains hard denial before incomplete evidence and shows the store local date', () => {
  render(<EligibilityResult value={{ ...eligibilityFixture, decision: 'DENIED', reasonCodes: ['LOT_RECALLED', 'DATE_LABEL_MISSING'], nextAction: 'DO_NOT_EXECUTE' }} />)
  expect(screen.getByRole('heading', { name: 'Denied' })).toBeVisible()
  expect(screen.getAllByRole('listitem').map(item => item.textContent)).toEqual(['A recall is recorded for this lot.', 'The original date label is missing.'])
  expect(screen.getByText('Europe/Warsaw')).toBeVisible()
  expect(screen.getByText('2026-11-06')).toBeVisible()
})
it('refuses to present an unexpected approval as verified permission', () => {
  render(<EligibilityResult value={{ ...eligibilityFixture, decision: 'ALLOWED' }} />)
  expect(screen.getByRole('alert')).toHaveTextContent('does not satisfy every approval and evidence check')
  expect(screen.getByRole('heading', { name: 'Requires review' })).toBeVisible()
})
it('shows the limited platform result with its exact scope warning and evidence versions', () => {
  render(<EligibilityResult value={{ ...eligibilityFixture, decision: 'ALLOWED', reasonCodes: [], nextAction: 'NO_ADDITIONAL_ACTION',
    productCategory: 'BAKERY', productTemperatureClass: 'AMBIENT',
    evidence: { ...eligibilityFixture.evidence, policy: 'INDEPENDENTLY_VERIFIED', classification: 'INDEPENDENTLY_VERIFIED' },
    assessmentNotice: 'Passed the limited StockRescue checks on the available evidence at this time; not approval to sell, donate, transfer or perform stock movements.',
    approvalVerification: verifiedApproval, classificationVerification: { ...verifiedClassification, currentCategory: 'BAKERY', signedCategory: 'BAKERY' } }} />)
  expect(screen.getByRole('heading', { name: 'Allowed' })).toBeVisible()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(screen.getByText('Cryptographically verified')).toBeVisible()
  expect(screen.getByText('Passed the limited StockRescue checks on the available evidence at this time; not approval to sell, donate, transfer or perform stock movements.')).toBeVisible()
})
it('allows a platform assessment to stand without an external approval when operator evidence is recorded', () => {
  render(<EligibilityResult value={{ ...eligibilityFixture, decision: 'ALLOWED', reasonCodes: [], nextAction: 'NO_ADDITIONAL_ACTION', approvalVerification: null,
    productCategory: 'BAKERY', productTemperatureClass: 'AMBIENT',
    classificationVerification: { ...eligibilityFixture.classificationVerification!, currentCategory: 'BAKERY' },
    operatorClassificationStatus: 'CURRENT_OPERATOR_CONFIRMED',
    operatorClassificationConfirmation: { id: 'operator-confirmation', organizationId: 'org', productId: 'product', sku: 'MILK', expectedProductVersion: 0, productVersion: 0,
      originalCategory: 'DAIRY', confirmedCategory: 'BAKERY', sourceType: 'ORIGINAL_PACKAGE', evidenceReference: 'package:category-label', actorUserId: 'owner',
      recordedByMembershipId: 'membership', requestId: 'request', reason: 'Reviewed label', recordedAt: '2026-11-06T11:30:00Z' },
    evidence: { ...eligibilityFixture.evidence, policy: 'PLATFORM_RULESET', classification: 'OPERATOR_CONFIRMED' },
    assessmentNotice: 'Passed the limited StockRescue checks on the available evidence at this time; not approval to sell, donate, transfer or perform stock movements.' }} />)
  expect(screen.getByRole('heading', { name: 'Allowed' })).toBeVisible()
  expect(screen.getByText('NOT_SUBMITTED')).toBeVisible()
  expect(screen.getByText('platform ruleset')).toBeVisible()
  expect(screen.getByText(/not independent verification/)).toBeVisible()
})
it('does not display an allowed result when operator evidence is stale', () => {
  render(<EligibilityResult value={{ ...eligibilityFixture, decision: 'ALLOWED', reasonCodes: [], nextAction: 'NO_ADDITIONAL_ACTION',
    operatorClassificationStatus: 'STALE', evidence: { ...eligibilityFixture.evidence, classification: 'OPERATOR_CONFIRMED' },
    assessmentNotice: 'Passed the limited StockRescue checks on the available evidence at this time; not approval to sell, donate, transfer or perform stock movements.' }} />)
  expect(screen.getByRole('alert')).toHaveTextContent('does not satisfy every approval and evidence check')
  expect(screen.getByRole('heading', { name: 'Requires review' })).toBeVisible()
})
it('keeps a currently confirmed but unsupported category under review', () => {
  render(<EligibilityResult value={{ ...eligibilityFixture, decision: 'ALLOWED', reasonCodes: [], nextAction: 'NO_ADDITIONAL_ACTION',
    operatorClassificationStatus: 'CURRENT_OPERATOR_CONFIRMED',
    operatorClassificationConfirmation: { id: 'operator-confirmation', organizationId: 'org', productId: 'product', sku: 'MILK', expectedProductVersion: 0, productVersion: 0,
      originalCategory: 'DAIRY', confirmedCategory: 'DAIRY', sourceType: 'ORIGINAL_PACKAGE', evidenceReference: 'package:category-label', actorUserId: 'owner',
      recordedByMembershipId: 'membership', requestId: 'request', reason: 'Reviewed label', recordedAt: '2026-11-06T11:30:00Z' },
    evidence: { ...eligibilityFixture.evidence, classification: 'OPERATOR_CONFIRMED' },
    assessmentNotice: 'Passed the limited StockRescue checks on the available evidence at this time; not approval to sell, donate, transfer or perform stock movements.' }} />)
  expect(screen.getByRole('alert')).toHaveTextContent('does not satisfy every approval and evidence check')
  expect(screen.getByRole('heading', { name: 'Requires review' })).toBeVisible()
})
it('fails closed when a verified signature does not authorize the requested operation', () => {
  render(<EligibilityResult value={{ ...eligibilityFixture, decision: 'ALLOWED', reasonCodes: [],
    evidence: { ...eligibilityFixture.evidence, policy: 'INDEPENDENTLY_VERIFIED', classification: 'INDEPENDENTLY_VERIFIED' },
    approvalVerification: { ...verifiedApproval, allowedOperations: ['TRANSFER'] }, classificationVerification: verifiedClassification }} />)
  expect(screen.getByRole('alert')).toHaveTextContent('does not satisfy every approval and evidence check')
  expect(screen.getByRole('heading', { name: 'Requires review' })).toBeVisible()
})
it('fails closed when a classification signature names a different category than the evaluated product', () => {
  render(<EligibilityResult value={{ ...eligibilityFixture, decision: 'ALLOWED', reasonCodes: [], nextAction: 'NO_ADDITIONAL_ACTION',
    productCategory: 'BAKERY', productTemperatureClass: 'AMBIENT',
    evidence: { ...eligibilityFixture.evidence, policy: 'PLATFORM_RULESET', classification: 'INDEPENDENTLY_VERIFIED' },
    classificationVerification: { ...verifiedClassification, currentCategory: 'BAKERY' },
    assessmentNotice: 'Passed the limited StockRescue checks on the available evidence at this time; not approval to sell, donate, transfer or perform stock movements.' }} />)
  expect(screen.getByRole('alert')).toHaveTextContent('does not satisfy every approval and evidence check')
  expect(screen.getByRole('heading', { name: 'Requires review' })).toBeVisible()
})
it('fails closed when the assessment names an unknown platform ruleset digest', () => {
  render(<EligibilityResult value={{ ...eligibilityFixture, decision: 'ALLOWED', reasonCodes: [], nextAction: 'NO_ADDITIONAL_ACTION',
    productCategory: 'BAKERY', productTemperatureClass: 'AMBIENT',
    operatorClassificationStatus: 'CURRENT_OPERATOR_CONFIRMED',
    operatorClassificationConfirmation: { id: 'operator-confirmation', organizationId: 'org', productId: 'product', sku: 'MILK', expectedProductVersion: 0, productVersion: 0,
      originalCategory: 'DAIRY', confirmedCategory: 'BAKERY', sourceType: 'ORIGINAL_PACKAGE', evidenceReference: 'package:category-label', actorUserId: 'owner',
      recordedByMembershipId: 'membership', requestId: 'request', reason: 'Reviewed label', recordedAt: '2026-11-06T11:30:00Z' },
    classificationVerification: { ...eligibilityFixture.classificationVerification!, currentCategory: 'BAKERY' },
    evidence: { ...eligibilityFixture.evidence, policy: 'PLATFORM_RULESET', classification: 'OPERATOR_CONFIRMED' },
    rulesetDigest: '0'.repeat(64),
    assessmentNotice: 'Passed the limited StockRescue checks on the available evidence at this time; not approval to sell, donate, transfer or perform stock movements.' }} />)
  expect(screen.getByRole('alert')).toHaveTextContent('does not satisfy every approval and evidence check')
  expect(screen.getByRole('heading', { name: 'Requires review' })).toBeVisible()
})
it('fails closed when an allowed response lacks verified product classification evidence', () => {
  render(<EligibilityResult value={{ ...eligibilityFixture, decision: 'ALLOWED', reasonCodes: [],
    evidence: { ...eligibilityFixture.evidence, policy: 'INDEPENDENTLY_VERIFIED', classification: 'MISSING' },
    approvalVerification: verifiedApproval, classificationVerification: { ...verifiedClassification, outcome: 'NOT_SUBMITTED' } }} />)
  expect(screen.getByRole('alert')).toHaveTextContent('does not satisfy every approval and evidence check')
  expect(screen.getByRole('heading', { name: 'Requires review' })).toBeVisible()
})
it('shows a ruleset mismatch as blocking evidence', () => {
  render(<EligibilityResult value={{ ...eligibilityFixture, approvalVerification: { ...verifiedApproval, outcome: 'RULESET_MISMATCH' } }} />)
  expect(screen.getByRole('alert')).toHaveTextContent('different food-safety ruleset')
  expect(screen.getByRole('heading', { name: 'Requires review' })).toBeVisible()
})
