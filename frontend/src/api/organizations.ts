import type { components } from './schema'
import { api, ApiError, csrf, requestSignal } from './client'

export type Organization = components['schemas']['OrganizationResponse']
export type OnboardingRequest = components['schemas']['OnboardingRequest']

export async function getOrganization(organizationId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET('/api/v1/organizations/{organizationId}', { params: { path: { organizationId } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function listOrganizations(offset: number, signal: AbortSignal) {
  const { data, error, response } = await api.GET('/api/v1/organizations', {
    params: { query: { offset, limit: 25 } }, signal: requestSignal(signal),
  })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function getActiveOrganization(signal: AbortSignal): Promise<Organization | null> {
  const { data, error, response } = await api.GET('/api/v1/auth/active-organization', { signal: requestSignal(signal) })
  if (response.status === 204) return null
  if (!data) throw new ApiError(response, error)
  return data
}

export async function onboardOrganization(body: OnboardingRequest, signal: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/organizations', {
    body, params: await csrf(signal), signal: requestSignal(signal),
  })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function selectOrganization(organizationId: string, signal: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/auth/active-organization', {
    body: { organizationId }, params: await csrf(signal), signal: requestSignal(signal),
  })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function getEffectivePermissions(signal: AbortSignal) {
  const { data, error, response } = await api.GET('/api/v1/auth/me/effective-permissions', { signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}
