import type { components, operations } from './schema'
import { api, ApiError, csrf, requestSignal } from './client'

export type Eligibility = components['schemas']['EligibilityResponse']
export type FoodSafetyPolicyVersion = components['schemas']['FoodSafetyPolicyVersionResponse']
export type PolicyApprovalEvidence = components['schemas']['PolicyApprovalEvidenceResponse']
export type SubmitPolicyApprovalEvidence = components['schemas']['SubmitPolicyApprovalEvidenceRequest']
export type RevokePolicyApproval = components['schemas']['RevokePolicyApprovalRequest']
export type RecordPolicyReview = components['schemas']['RecordFoodSafetyPolicyReviewRequest']
export type RecordSafetyEvidence = components['schemas']['RecordLotSafetyEvidenceRequest']
export type EligibilityContext = Omit<operations['evaluateLotEligibility']['parameters']['query'], 'operation'>

export async function evaluateLotEligibility(organizationId: string, lotId: string, context: EligibilityContext, operation: Eligibility['operation'], signal: AbortSignal) {
  const { data, error, response } = await api.GET('/api/v1/organizations/{organizationId}/lots/{lotId}/eligibility', {
    params: { path: { organizationId, lotId }, query: { ...context, operation } }, cache: 'no-store', signal: requestSignal(signal),
  })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function listFoodSafetyPolicyVersions(organizationId: string, productCategory: FoodSafetyPolicyVersion['productCategory'], temperatureClass: FoodSafetyPolicyVersion['temperatureClass'], offset: number, signal: AbortSignal) {
  const { data, error, response } = await api.GET('/api/v1/organizations/{organizationId}/food-safety-profiles/{productCategory}/{temperatureClass}/versions', {
    params: { path: { organizationId, productCategory, temperatureClass }, query: { offset, limit: 25 } }, cache: 'no-store', signal: requestSignal(signal),
  })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function getFoodSafetyPolicyApprovalEvidence(organizationId: string, productCategory: FoodSafetyPolicyVersion['productCategory'], temperatureClass: FoodSafetyPolicyVersion['temperatureClass'], policyVersionId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET('/api/v1/organizations/{organizationId}/food-safety-profiles/{productCategory}/{temperatureClass}/versions/{policyVersionId}/approval-evidence', {
    params: { path: { organizationId, productCategory, temperatureClass, policyVersionId } }, cache: 'no-store', signal: requestSignal(signal),
  })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function submitFoodSafetyPolicyApprovalEvidence(organizationId: string, productCategory: FoodSafetyPolicyVersion['productCategory'], temperatureClass: FoodSafetyPolicyVersion['temperatureClass'], policyVersionId: string, body: SubmitPolicyApprovalEvidence, signal: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/organizations/{organizationId}/food-safety-profiles/{productCategory}/{temperatureClass}/versions/{policyVersionId}/approval-evidence', {
    params: { path: { organizationId, productCategory, temperatureClass, policyVersionId }, ...await csrf(signal) }, body, signal: requestSignal(signal),
  })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}

export async function revokeFoodSafetyPolicyApproval(organizationId: string, approvalId: string, body: RevokePolicyApproval, signal: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/organizations/{organizationId}/food-safety-policy-approvals/{approvalId}/revocation', {
    params: { path: { organizationId, approvalId }, ...await csrf(signal) }, body, signal: requestSignal(signal),
  })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}

export async function recordFoodSafetyPolicyReview(organizationId: string, productCategory: FoodSafetyPolicyVersion['productCategory'], temperatureClass: FoodSafetyPolicyVersion['temperatureClass'], body: RecordPolicyReview, signal: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/organizations/{organizationId}/food-safety-profiles/{productCategory}/{temperatureClass}/versions', {
    params: { path: { organizationId, productCategory, temperatureClass }, ...await csrf(signal) }, body, signal: requestSignal(signal),
  })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}

export async function recordLotSafetyEvidence(organizationId: string, lotId: string, body: RecordSafetyEvidence, signal: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/organizations/{organizationId}/lots/{lotId}/safety-status-evidence', {
    params: { path: { organizationId, lotId }, ...await csrf(signal) }, body, signal: requestSignal(signal),
  })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}
