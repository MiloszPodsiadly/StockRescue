import type { components } from './schema'
import { api, ApiError, csrf, requestSignal } from './client'

export type ManufacturerLot = components['schemas']['LotResponse']
export type RegisterLot = components['schemas']['RegisterLotRequest']
export type SourceEvidence = components['schemas']['SourceEvidenceResponse']
export type RegisterSourceEvidence = components['schemas']['RegisterSourceEvidenceRequest']
export type CorrectLotMetadata = components['schemas']['CorrectLotMetadataRequest']
export type ArchiveLot = components['schemas']['ArchiveLotRequest']

export async function archiveLot(organizationId: string, lotId: string, body: ArchiveLot, signal: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/organizations/{organizationId}/lots/{lotId}/archive', { params: { path: { organizationId, lotId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}

export async function correctLotMetadata(organizationId: string, lotId: string, body: CorrectLotMetadata, signal: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/organizations/{organizationId}/lots/{lotId}/corrections', { params: { path: { organizationId, lotId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}

export async function listLotCorrections(organizationId: string, lotId: string, offset: number, signal: AbortSignal) {
  const { data, error, response } = await api.GET('/api/v1/organizations/{organizationId}/lots/{lotId}/corrections', { params: { path: { organizationId, lotId }, query: { offset, limit: 25 } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function registerLot(organizationId: string, body: RegisterLot, signal: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/organizations/{organizationId}/lots', { params: { path: { organizationId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}

export async function listLots(organizationId: string, productId: string | undefined, includeArchived: boolean, offset: number, signal: AbortSignal) {
  const { data, error, response } = await api.GET('/api/v1/organizations/{organizationId}/lots', { params: { path: { organizationId }, query: { productId, includeArchived, offset, limit: 25 } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function getLot(organizationId: string, lotId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET('/api/v1/organizations/{organizationId}/lots/{lotId}', { params: { path: { organizationId, lotId } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function registerSourceEvidence(organizationId: string, lotId: string, body: RegisterSourceEvidence, signal: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/organizations/{organizationId}/lots/{lotId}/source-evidence', { params: { path: { organizationId, lotId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}

export async function traceLotSources(organizationId: string, lotId: string, offset: number, signal: AbortSignal) {
  const { data, error, response } = await api.GET('/api/v1/organizations/{organizationId}/lots/{lotId}/source-evidence', { params: { path: { organizationId, lotId }, query: { offset, limit: 25 } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function getSourceEvidence(organizationId: string, lotId: string, evidenceId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET('/api/v1/organizations/{organizationId}/lots/{lotId}/source-evidence/{evidenceId}', { params: { path: { organizationId, lotId, evidenceId } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}
