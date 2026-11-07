import type { Eligibility } from '@/api/food-safety'

export const reasonMessages: Record<Eligibility['reasonCodes'][number], string> = {
  LOT_RECALLED: 'A recall is recorded for this lot.', LOT_DAMAGED: 'Damage is recorded for this lot.',
  LOT_ARCHIVED: 'The lot is archived.', PRODUCT_ARCHIVED: 'The product is archived.', STORE_ARCHIVED: 'The store is archived.',
  ZONE_ARCHIVED: 'The selected storage zone is inactive.', LOCATION_ARCHIVED: 'The selected storage location is archived.',
  TEMPERATURE_CLASS_MISMATCH: 'The product and storage zone declare different temperature classes.',
  USE_BY_EXPIRED: 'The use-by label day has passed in this store’s time zone.', BEST_BEFORE_EXPIRED: 'The best-before date has passed; this pilot denies sale and transfer.',
  LOT_IDENTITY_UNVERIFIED: 'Manufacturer identity is still unverified.', DATE_LABEL_MISSING: 'The original date label is missing.',
  DATE_TYPE_UNKNOWN: 'The type of date marking is unknown.', DATE_PRECISION_UNKNOWN: 'The date precision is unknown.', DATE_LABEL_INVALID: 'The label is malformed, ambiguous or not a real calendar date.',
  USE_BY_DAY_REQUIRED: 'A use-by marking requires an explicit day.', DATE_TYPE_PRECISION_MISMATCH: 'A best-before-end label uses month or year precision; the recorded day is incomplete.',
  DATE_PROVENANCE_MISSING: 'Date-label source evidence is missing.', DATE_REFERENCE_MISSING: 'The date-label source has no document reference.',
  PARTIAL_DATE_RULE_UNVERIFIED: 'A partial month/year marking requires a verified normalization rule; no day has been inferred.',
  RECALL_STATUS_UNKNOWN: 'Recall status is unknown.', DAMAGE_STATUS_UNKNOWN: 'Damage status is unknown.', STATUS_REFERENCE_MISSING: 'Safety-status evidence has no document reference.',
  STORAGE_CONTEXT_MISSING: 'No declared storage context was supplied.', COLD_CHAIN_EVIDENCE_UNVERIFIED: 'Declared cold storage does not verify continuous cold-chain conditions.',
  PRODUCT_CATEGORY_UNCLASSIFIED: 'The product category is unclassified, so no scoped policy can authorize this operation.',
  POLICY_MISSING: 'No policy review is recorded for this product category and temperature class.', POLICY_SCOPE_MISMATCH: 'The policy scope does not match this product category.', POLICY_CLASS_MISMATCH: 'The policy does not match this product’s temperature class.',
  POLICY_APPROVAL_UNVERIFIED: 'Independent approval for this category and temperature scope has not been verified.',
}
export const nextActions: Record<Eligibility['nextAction'], string> = {
  DO_NOT_EXECUTE: 'Do not sell or transfer this lot using this assessment.', REVIEW_LOT_EVIDENCE: 'Review the original label, manufacturer identity and source documents.',
  RECORD_STORAGE_CONTEXT: 'Select or record the declared storage configuration.', OBTAIN_INDEPENDENT_POLICY_APPROVAL: 'Obtain independent policy approval for this category and temperature scope and resolve the listed evidence gaps.',
  NO_ADDITIONAL_ACTION: 'The listed evidence gates are satisfied for this operation at the assessment time.',
}
