import type { components } from './schema'
import { api, ApiError, csrf, requestSignal } from './client'

export type Zone = components['schemas']['ZoneResponse']
export type Temperature = Zone['temperatureClass']
export type Lane = components['schemas']['LaneResponse']
export type Revision = components['schemas']['RevisionResponse']
export type LaneCreation = components['schemas']['CreateLaneRequest']
export type LaneUpdate = components['schemas']['UpdateLaneRequest']
export type CostRevision = components['schemas']['CostRevisionRequest']
type Problem = components['schemas']['ApiProblem']
function data<T>(result: { data?: T, error?: Problem, response: Response }) { if (result.data === undefined) throw new ApiError(result.response, result.error); return result.data }
export async function listZones(organizationId: string, storeId: string, includeInactive: boolean, offset: number, signal: AbortSignal) {
  return data(await api.GET('/api/v1/organizations/{organizationId}/stores/{storeId}/storage-zones', { params: { path: { organizationId, storeId }, query: { includeInactive, offset, limit: 100 } }, signal: requestSignal(signal) }))
}
export async function createZone(organizationId: string, storeId: string, name: string, temperatureClass: Temperature, signal: AbortSignal) {
  const result = await api.POST('/api/v1/organizations/{organizationId}/stores/{storeId}/storage-zones', { params: { path: { organizationId, storeId }, ...await csrf(signal) }, body: { name, temperatureClass }, signal: requestSignal(signal) })
  signal.throwIfAborted(); return data(result)
}
export async function renameZone(organizationId: string, storeId: string, zone: Zone, name: string, reason: string, signal: AbortSignal) {
  const result = await api.PATCH('/api/v1/organizations/{organizationId}/stores/{storeId}/storage-zones/{zoneId}', { params: { path: { organizationId, storeId, zoneId: zone.id }, ...await csrf(signal) }, body: { name, version: zone.version, reason }, signal: requestSignal(signal) })
  signal.throwIfAborted(); return data(result)
}
export async function archiveZone(organizationId: string, storeId: string, zone: Zone, reason: string, signal: AbortSignal) {
  const result = await api.POST('/api/v1/organizations/{organizationId}/stores/{storeId}/storage-zones/{zoneId}/archive', { params: { path: { organizationId, storeId, zoneId: zone.id }, ...await csrf(signal) }, body: { version: zone.version, reason }, signal: requestSignal(signal) })
  signal.throwIfAborted(); return data(result)
}
export async function listLanes(organizationId: string, offset: number, signal: AbortSignal) {
  return data(await api.GET('/api/v1/organizations/{organizationId}/transport-lanes', { params: { path: { organizationId }, query: { offset, limit: 25 } }, signal: requestSignal(signal) }))
}
export async function getLane(organizationId: string, laneId: string, signal: AbortSignal) {
  return data(await api.GET('/api/v1/organizations/{organizationId}/transport-lanes/{laneId}', { params: { path: { organizationId, laneId } }, signal: requestSignal(signal) }))
}
export async function lookupLane(organizationId: string, originStoreId: string, destinationStoreId: string, signal: AbortSignal) {
  return data(await api.GET('/api/v1/organizations/{organizationId}/transport-lanes/lookup', { params: { path: { organizationId }, query: { originStoreId, destinationStoreId } }, signal: requestSignal(signal) }))
}
export async function supportedCurrencies(organizationId: string, signal: AbortSignal) {
  return data(await api.GET('/api/v1/organizations/{organizationId}/transport-lanes/currencies', { params: { path: { organizationId } }, signal: requestSignal(signal) }))
}
export async function createLane(organizationId: string, body: LaneCreation, signal: AbortSignal) {
  const result = await api.POST('/api/v1/organizations/{organizationId}/transport-lanes', { params: { path: { organizationId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted(); return data(result)
}
export async function updateLane(organizationId: string, laneId: string, body: LaneUpdate, signal: AbortSignal) {
  const result = await api.PATCH('/api/v1/organizations/{organizationId}/transport-lanes/{laneId}', { params: { path: { organizationId, laneId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted(); return data(result)
}
export async function reviseLane(organizationId: string, laneId: string, body: CostRevision, signal: AbortSignal) {
  const result = await api.POST('/api/v1/organizations/{organizationId}/transport-lanes/{laneId}/revisions', { params: { path: { organizationId, laneId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted(); return data(result)
}
export async function laneHistory(organizationId: string, laneId: string, offset: number, signal: AbortSignal) {
  return data(await api.GET('/api/v1/organizations/{organizationId}/transport-lanes/{laneId}/revisions', { params: { path: { organizationId, laneId }, query: { offset, limit: 25 } }, signal: requestSignal(signal) }))
}
