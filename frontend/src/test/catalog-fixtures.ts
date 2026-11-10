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
  productId: 'product', productSku: 'MILK', productVersion: 0, storeId: 'store', storeVersion: 0, zoneId: 'zone', zoneVersion: 0, locationId: null, locationVersion: null,
  productCategory: 'DAIRY', productTemperatureClass: 'AMBIENT',
  operatorClassificationConfirmation: null, operatorClassificationStatus: 'NOT_RECORDED',
  policy: { id: 'policy-version', profileId: 'profile', productCategory: 'DAIRY', version: 2 }, evaluatedAt: '2026-11-06T12:00:00Z', storeLocalDate: '2026-11-06', storeTimeZone: 'Europe/Warsaw',
  evidence: { date: 'COMPLETE_LABEL', recall: 'NOT_REPORTED_IN_REVIEWED_EVIDENCE', damage: 'NOT_REPORTED_IN_REVIEWED_EVIDENCE', storage: 'DECLARED_COMPATIBLE', policy: 'RECORDED_UNVERIFIED', classification: 'MISSING',
    manufacturerSource: { source: 'Manufacturer registry', reference: 'record-17' }, dateLabelSource: { source: 'Package label', reference: 'label-photo' }, safetyStatusSource: { source: 'Supplier notice', reference: 'supplier-notice' } },
  nextAction: 'CONFIRM_PRODUCT_CLASSIFICATION', evaluatorVersion: platformRulesetId,
  assessmentBasis: 'PLATFORM_RULESET', rulesetId: platformRulesetId, rulesetDigest: platformRulesetDigest, assessmentNotice: null }
