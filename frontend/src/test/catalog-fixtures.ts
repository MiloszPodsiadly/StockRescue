import type { ManufacturerLot } from '@/api/lots'
import type { Product } from '@/api/products'
import type { Eligibility } from '@/api/food-safety'

export const productFixture: Product = { id: 'product', sku: 'MILK', name: 'Packaged milk', gtin: '036000291452', baseUnit: 'EA', category: 'DAIRY', temperatureClass: 'AMBIENT', active: true,
  version: 0, createdAt: '2026-11-01T12:00:00Z', updatedAt: '2026-11-01T12:00:00Z', archivedAt: null, allowedActions: ['READ_CATALOG', 'MANAGE_CATALOG'] }
export const lotFixture: ManufacturerLot = { id: 'lot', productId: 'product', lotCode: 'MAKER-LOT', identityState: 'CONFIRMED', manufacturerIdentityKey: 'registry:maker',
  manufacturerProvenance: { source: 'Manufacturer registry', reference: 'record-17' }, confirmedAt: '2026-11-01T12:00:00Z', confirmedByMembershipId: 'membership',
  dateMarking: { labeledValue: ' 2027-04 ', type: 'BEST_BEFORE_END', precision: 'MONTH', provenance: { source: 'Label photo', reference: 'photo-17' }, validationStatus: 'VALID', validationReasons: [], normalizedCutoff: null },
  recallState: 'UNKNOWN', damageState: 'UNKNOWN', statusProvenance: null, active: true, version: 1, createdAt: '2026-11-01T12:00:00Z', updatedAt: '2026-11-01T12:00:00Z', archivedAt: null }
export const eligibilityFixture: Eligibility = { decision: 'REQUIRES_REVIEW', reasonCodes: ['POLICY_APPROVAL_UNVERIFIED'], operation: 'SALE', lotId: 'lot', lotVersion: 1,
  productId: 'product', productVersion: 0, storeId: 'store', storeVersion: 0, zoneId: 'zone', zoneVersion: 0, locationId: null, locationVersion: null, approvalVerification: null,
  policy: { id: 'policy-version', profileId: 'profile', productCategory: 'DAIRY', version: 2 }, evaluatedAt: '2026-11-06T12:00:00Z', storeLocalDate: '2026-11-06', storeTimeZone: 'Europe/Warsaw',
  evidence: { date: 'COMPLETE_LABEL', recall: 'REPORTED', damage: 'REPORTED', storage: 'DECLARED_COMPATIBLE', policy: 'RECORDED_UNVERIFIED' }, nextAction: 'OBTAIN_INDEPENDENT_POLICY_APPROVAL', evaluatorVersion: 'conservative-pilot-5' }
export const verifiedApproval: NonNullable<Eligibility['approvalVerification']> = { outcome: 'VERIFIED', policyVersion: 2, policyDigest: '0'.repeat(64), issuerId: 'test-issuer', keyId: 'test-key',
  allowedOperations: ['SALE'], issuedAt: '2026-11-06T11:00:00Z', validFrom: '2026-11-06T11:00:00Z', validUntil: '2026-11-07T11:00:00Z',
  evidenceReference: 'test-evidence', recordedAt: '2026-11-06T11:30:00Z', revoked: false, revokedAt: null, revokedByMembershipId: null, revocationReason: null }
