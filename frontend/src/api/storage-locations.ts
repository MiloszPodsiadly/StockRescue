import type { components } from './schema'
import { api, ApiError, csrf, requestSignal } from './client'

export type StorageLocation = components['schemas']['StorageLocationResponse']
export type CreateStorageLocation = components['schemas']['CreateStorageLocationRequest']
export type RenameStorageLocation = components['schemas']['RenameStorageLocationRequest']
export type ArchiveStorageLocation = components['schemas']['ArchiveStorageLocationRequest']

export async function listStorageLocations(organizationId: string, storeId: string, includeArchived: boolean, q: string, offset: number, signal: AbortSignal) {
  const { data, error, response } = await api.GET('/api/v1/organizations/{organizationId}/stores/{storeId}/storage-locations', {
    params: { path: { organizationId, storeId }, query: { includeArchived, q, offset, limit: 25 } }, signal: requestSignal(signal),
  })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function getStorageLocation(organizationId: string, storeId: string, locationId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET('/api/v1/organizations/{organizationId}/stores/{storeId}/storage-locations/{locationId}', {
    params: { path: { organizationId, storeId, locationId } }, signal: requestSignal(signal),
  })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function createStorageLocation(organizationId: string, storeId: string, body: CreateStorageLocation, signal: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/organizations/{organizationId}/stores/{storeId}/storage-locations', {
    params: { path: { organizationId, storeId }, ...await csrf(signal) }, body, signal: requestSignal(signal),
  })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}

export async function renameStorageLocation(organizationId: string, storeId: string, locationId: string, body: RenameStorageLocation, signal: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/organizations/{organizationId}/stores/{storeId}/storage-locations/{locationId}/rename', {
    params: { path: { organizationId, storeId, locationId }, ...await csrf(signal) }, body, signal: requestSignal(signal),
  })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}

export async function archiveStorageLocation(organizationId: string, storeId: string, locationId: string, body: ArchiveStorageLocation, signal: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/organizations/{organizationId}/stores/{storeId}/storage-locations/{locationId}/archive', {
    params: { path: { organizationId, storeId, locationId }, ...await csrf(signal) }, body, signal: requestSignal(signal),
  })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}
