import type { ManufacturerLot } from '@/api/lots'
import type { Product } from '@/api/products'
import type { Eligibility } from '@/api/food-safety'
import { platformRulesetDigest, platformRulesetId } from '@/features/catalog/eligibility-ruleset'

export const productFixture: Product = { id: 'product', sku: 'MILK', name: 'Packaged milk', gtin: '036000291452', baseUnit: 'EA', category: 'DAIRY', temperatureClass: 'AMBIENT', active: true,
  version: 0, createdAt: '2026-11-01T12:00:00Z', updatedAt: '2026-11-01T12:00:00Z', archivedAt: null, allowedActions: ['READ_CATALOG', 'MANAGE_CATALOG'] }
export const lotFixture: ManufacturerLot = { id: 'lot', productId: 'product', lotCode: 'MAKER-LOT', identityState: 'CONFIRMED', manufacturerIdentityKey: 'registry:maker',
  manufacturerProvenance: { source: 'Manufacturer registry', reference: 'record-17' }, confirmedAt: '2026-11-01T12:00:00Z', confirmedByMembershipId: 'membership',
  dateMarking: { labeledValue: ' 2027-04 ', type: 'BEST_BEFORE_END', precision: 'MONTH', provenance: { source: 'Label photo', reference: 'photo-17' }, validationStatus: 'VALID', validationReasons: [], normalizedCutoff: null },
  recallState: 'UNKNOWN', damageState: 'UNKNOWN', statusProvenance: null, active: true, version: 1, createdAt: '2026-11-01T12:00:00Z', updatedAt: '2026-11-01T12:00:00Z', archivedAt: null }
export const eligibilityFixture: Eligibility = { decision: 'REQUIRES_REVIEW', reasonCodes: ['PRODUCT_CLASSIFICATION_MISSING'], operation: 'SALE', lotId: 'lot', lotVersion: 1,
  productId: 'product', productVersion: 0, storeId: 'store', storeVersion: 0, zoneId: 'zone', zoneVersion: 0, locationId: null, locationVersion: null,
  productCategory: 'DAIRY', productTemperatureClass: 'AMBIENT',
  operatorClassificationConfirmation: null, operatorClassificationStatus: 'NOT_RECORDED', approvalVerification: null,
  classificationVerification: { attestationId: null, outcome: 'NOT_SUBMITTED', organizationId: 'org', productId: 'product', currentSku: 'MILK', currentCategory: 'DAIRY', currentProductVersion: 0,
    signedCategory: null, signedProductVersion: null, issuerId: null, keyId: null, issuedAt: null, validFrom: null, validUntil: null,
    evidenceReference: null, recordedAt: null, revoked: false, revokedAt: null, revocationReason: null },
  policy: { id: 'policy-version', profileId: 'profile', productCategory: 'DAIRY', version: 2 }, evaluatedAt: '2026-11-06T12:00:00Z', storeLocalDate: '2026-11-06', storeTimeZone: 'Europe/Warsaw',
  evidence: { date: 'COMPLETE_LABEL', recall: 'NOT_REPORTED_IN_REVIEWED_EVIDENCE', damage: 'NOT_REPORTED_IN_REVIEWED_EVIDENCE', storage: 'DECLARED_COMPATIBLE', policy: 'RECORDED_UNVERIFIED', classification: 'MISSING',
    manufacturerSource: { source: 'Manufacturer registry', reference: 'record-17' }, dateLabelSource: { source: 'Package label', reference: 'label-photo' }, safetyStatusSource: { source: 'Supplier notice', reference: 'supplier-notice' } },
  nextAction: 'CONFIRM_PRODUCT_CLASSIFICATION', evaluatorVersion: platformRulesetId,
  assessmentBasis: 'PLATFORM_RULESET', rulesetId: platformRulesetId, rulesetDigest: platformRulesetDigest, assessmentNotice: null }
export const verifiedClassification: NonNullable<Eligibility['classificationVerification']> = { attestationId: 'classification', outcome: 'VERIFIED', organizationId: 'org', productId: 'product',
  currentSku: 'MILK', currentCategory: 'DAIRY', currentProductVersion: 0, signedCategory: 'DAIRY', signedProductVersion: 0, issuerId: 'test-issuer', keyId: 'test-key',
  issuedAt: '2026-11-06T11:00:00Z', validFrom: '2026-11-06T11:00:00Z', validUntil: '2026-11-07T11:00:00Z', evidenceReference: 'classification-evidence',
  recordedAt: '2026-11-06T11:30:00Z', revoked: false, revokedAt: null, revocationReason: null }
export const verifiedApproval: NonNullable<Eligibility['approvalVerification']> = { outcome: 'VERIFIED', policyVersion: 2, policyDigest: '0'.repeat(64), issuerId: 'test-issuer', keyId: 'test-key',
  allowedOperations: ['SALE'], issuedAt: '2026-11-06T11:00:00Z', validFrom: '2026-11-06T11:00:00Z', validUntil: '2026-11-07T11:00:00Z',
  evidenceReference: 'test-evidence', recordedAt: '2026-11-06T11:30:00Z', revoked: false, revokedAt: null, revokedByMembershipId: null, revocationReason: null,
  attestationVersion: 2, signedRulesetId: platformRulesetId, signedRulesetDigest: platformRulesetDigest, activeRulesetId: platformRulesetId, activeRulesetDigest: platformRulesetDigest }
