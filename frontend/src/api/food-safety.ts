import type { components, operations } from './schema'
import { api, ApiError, csrf, requestSignal } from './client'

export type Eligibility = components['schemas']['EligibilityResponse']
export type FoodSafetyPolicyVersion = components['schemas']['FoodSafetyPolicyVersionResponse']
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

export async function listFoodSafetyPolicyVersions(organizationId: string, temperatureClass: FoodSafetyPolicyVersion['temperatureClass'], offset: number, signal: AbortSignal) {
  const { data, error, response } = await api.GET('/api/v1/organizations/{organizationId}/food-safety-profiles/{temperatureClass}/versions', {
    params: { path: { organizationId, temperatureClass }, query: { offset, limit: 25 } }, cache: 'no-store', signal: requestSignal(signal),
  })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function recordFoodSafetyPolicyReview(organizationId: string, temperatureClass: FoodSafetyPolicyVersion['temperatureClass'], body: RecordPolicyReview, signal: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/organizations/{organizationId}/food-safety-profiles/{temperatureClass}/versions', {
    params: { path: { organizationId, temperatureClass }, ...await csrf(signal) }, body, signal: requestSignal(signal),
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
