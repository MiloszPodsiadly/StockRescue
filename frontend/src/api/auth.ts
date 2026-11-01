import type { components } from './schema'
import { api, ApiError, clearCsrf, csrf, requestSignal } from './client'
import { announceSessionChange } from './session-events'

export { ApiError, errorMessage } from './client'

export type Account = components['schemas']['UserResponse']
export type Credentials = components['schemas']['Credentials']

export async function signUp(body: Credentials, signal?: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/auth/sign-up', {
    body, params: await csrf(signal, true), signal: requestSignal(signal),
  })
  if (!data) throw new ApiError(response, error)
  return data
}

export async function signIn(body: Credentials, signal?: AbortSignal) {
  const { data, error, response } = await api.POST('/api/v1/auth/sign-in', {
    body, params: await csrf(signal, true), signal: requestSignal(signal),
  })
  if (!data) throw new ApiError(response, error)
  clearCsrf()
  announceSessionChange()
  return data
}

export async function signOut() {
  const { error, response } = await api.POST('/api/v1/auth/sign-out', {
    params: await csrf(), signal: requestSignal(),
  })
  if (!response.ok && response.status !== 401) throw new ApiError(response, error)
  clearCsrf()
  announceSessionChange()
}

export async function getSession(signal: AbortSignal): Promise<Account | null> {
  const { data, error, response } = await api.GET('/api/v1/auth/me', {
    signal: requestSignal(signal),
  })
  if (response.status === 401) return null
  if (!data) throw new ApiError(response, error)
  return data
}
