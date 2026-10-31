import type { components, operations } from './schema'
import { api, ApiError, csrf, requestSignal } from './client'

export type Store = components['schemas']['StoreResponse']
export type CreateStore = components['schemas']['CreateStoreRequest']
export type UpdateStore = components['schemas']['UpdateStoreRequest']
export type StoreState = NonNullable<operations['listStores']['parameters']['query']>['state']

export async function listStores(organizationId: string, state: StoreState, q: string, offset: number, signal: AbortSignal) {
  const { data, error, response } = await api.GET('/api/v1/organizations/{organizationId}/stores', { params: { path: { organizationId }, query: { state, q, offset, limit: 25 } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}
export async function getStore(organizationId: string, storeId: string, signal: AbortSignal) {
  const { data, error, response } = await api.GET('/api/v1/organizations/{organizationId}/stores/{storeId}', { params: { path: { organizationId, storeId } }, signal: requestSignal(signal) })
  if (!data) throw new ApiError(response, error)
  return data
}
export async function createStore(organizationId: string, body: CreateStore, signal: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/organizations/{organizationId}/stores', { params: { path: { organizationId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}
export async function updateStore(organizationId: string, storeId: string, body: UpdateStore, signal: AbortSignal) {
  const { data, error, response } = await api.PATCH('/api/v1/organizations/{organizationId}/stores/{storeId}', { params: { path: { organizationId, storeId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}
export async function archiveStore(organizationId: string, store: Store, reason: string, signal: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/organizations/{organizationId}/stores/{storeId}/archive', { params: { path: { organizationId, storeId: store.id }, ...await csrf(signal) }, body: { version: store.version, reason }, signal: requestSignal(signal) })
  signal.throwIfAborted()
  if (!data) throw new ApiError(response, error)
  return data
}
