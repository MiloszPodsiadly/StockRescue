import type { components } from './schema'
import { api, ApiError, csrf, requestSignal } from './client'

export type Member = components['schemas']['MemberResponse']
export type Invitation = components['schemas']['InvitationResponse']
export type StoreOption = components['schemas']['StoreOptionResponse']
export type InviteRequest = components['schemas']['InviteRequest']
export type StoreRole = components['schemas']['GrantRequest']['role']
type Problem = components['schemas']['ApiProblem']

function data<T>(result: { data?: T, error?: Problem, response: Response }): T {
  if (result.data === undefined) throw new ApiError(result.response, result.error)
  return result.data
}

export async function listMembers(organizationId: string, offset: number, state: Member['state'] | undefined, signal: AbortSignal) {
  return data(await api.GET('/api/v1/organizations/{organizationId}/members', { params: { path: { organizationId }, query: { offset, limit: 25, state } }, signal: requestSignal(signal) }))
}
export async function listInvitations(organizationId: string, offset: number, signal: AbortSignal) {
  return data(await api.GET('/api/v1/organizations/{organizationId}/invitations', { params: { path: { organizationId }, query: { offset, limit: 25 } }, signal: requestSignal(signal) }))
}
export async function listAccessStores(organizationId: string, offset: number, signal: AbortSignal) {
  return data(await api.GET('/api/v1/organizations/{organizationId}/access-stores', { params: { path: { organizationId }, query: { offset, limit: 25 } }, signal: requestSignal(signal) }))
}
export async function inviteStaff(organizationId: string, body: InviteRequest, signal: AbortSignal) {
  const result = await api.POST('/api/v1/organizations/{organizationId}/invitations', { params: { path: { organizationId }, ...await csrf(signal) }, body, signal: requestSignal(signal) })
  signal.throwIfAborted()
  return data(result)
}
export async function acceptInvitation(token: string, signal: AbortSignal) {
  const result = await api.POST('/api/v1/invitations/accept', { params: await csrf(signal), body: { token }, signal: requestSignal(signal) })
  signal.throwIfAborted()
  return data(result)
}
export async function revokeInvitation(organizationId: string, invitation: Invitation, reason: string, signal: AbortSignal) {
  const { error, response } = await api.DELETE('/api/v1/organizations/{organizationId}/invitations/{invitationId}', {
    params: { path: { organizationId, invitationId: invitation.id }, ...await csrf(signal) }, body: { version: invitation.version, reason }, signal: requestSignal(signal),
  })
  signal.throwIfAborted()
  if (!response.ok) throw new ApiError(response, error)
}
export async function approveMembership(organizationId: string, member: Member, verifiedUserId: string, reason: string, signal: AbortSignal) {
  const result = await api.POST('/api/v1/organizations/{organizationId}/members/{membershipId}/approve', {
    params: { path: { organizationId, membershipId: member.id }, ...await csrf(signal) }, body: { version: member.version, verifiedUserId, reason }, signal: requestSignal(signal),
  })
  signal.throwIfAborted()
  return data(result)
}
export async function changeMemberRole(organizationId: string, member: Member, role: Member['role'], reason: string, signal: AbortSignal) {
  const result = await api.PATCH('/api/v1/organizations/{organizationId}/members/{membershipId}/role', {
    params: { path: { organizationId, membershipId: member.id }, ...await csrf(signal) }, body: { version: member.version, role, reason }, signal: requestSignal(signal),
  })
  signal.throwIfAborted()
  return data(result)
}
export async function revokeMembership(organizationId: string, member: Member, reason: string, signal: AbortSignal) {
  const result = await api.DELETE('/api/v1/organizations/{organizationId}/members/{membershipId}', {
    params: { path: { organizationId, membershipId: member.id }, ...await csrf(signal) }, body: { version: member.version, reason }, signal: requestSignal(signal),
  })
  signal.throwIfAborted()
  return data(result)
}
export async function setStoreGrant(organizationId: string, member: Member, storeId: string, role: StoreRole, reason: string, signal: AbortSignal) {
  const existing = member.grants.find(grant => grant.storeId === storeId)
  const result = await api.PUT('/api/v1/organizations/{organizationId}/members/{membershipId}/store-grants/{storeId}', {
    params: { path: { organizationId, membershipId: member.id, storeId }, ...await csrf(signal) },
    body: { memberVersion: member.version, grantVersion: existing?.version, role, reason }, signal: requestSignal(signal),
  })
  signal.throwIfAborted()
  return data(result)
}
export async function revokeStoreGrant(organizationId: string, member: Member, storeId: string, reason: string, signal: AbortSignal) {
  const existing = member.grants.find(grant => grant.storeId === storeId)
  if (!existing) throw new Error('Grant no longer available')
  const result = await api.DELETE('/api/v1/organizations/{organizationId}/members/{membershipId}/store-grants/{storeId}', {
    params: { path: { organizationId, membershipId: member.id, storeId }, ...await csrf(signal) },
    body: { memberVersion: member.version, grantVersion: existing.version, reason }, signal: requestSignal(signal),
  })
  signal.throwIfAborted()
  return data(result)
}
