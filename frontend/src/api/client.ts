import createClient from 'openapi-fetch'
import type { components, paths } from './schema'
import { accessChanged, sessionExpired } from './session-events'

type Problem = components['schemas']['ApiProblem']

let csrfToken: string | undefined
let csrfRequest: Promise<string> | undefined
let csrfGeneration = 0

export function clearCsrf() {
  csrfToken = undefined
  csrfRequest = undefined
  csrfGeneration++
}

export const api = createClient<paths>({
  baseUrl: window.location.origin,
  credentials: 'same-origin',
  fetch: async request => {
    const response = await fetch(request)
    const pathname = new URL(request.url).pathname
    if (!request.signal.aborted && response.status === 401 && !pathname.startsWith('/api/v1/auth/')) sessionExpired()
    const resource = pathname.match(/^\/api\/v1\/organizations\/([^/]+)\/.+/)
    if (!request.signal.aborted && resource && (response.status === 403 || response.status === 404)) accessChanged(resource[1])
    return response
  },
})

export class ApiError extends Error {
  readonly status: number
  readonly code: Problem['code'] | 'REQUEST_FAILED'
  readonly correlationId?: string

  constructor(response: Response, problem?: Problem) {
    super('The request could not be completed.')
    this.status = response.status
    this.code = problem?.code ?? 'REQUEST_FAILED'
    this.correlationId = problem?.correlationId
    if (response.status === 401 || this.code === 'CSRF_INVALID') clearCsrf()
  }
}

export function errorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return 'We could not confirm the request. Check your connection and try again.'
  return `${problemMessage(error)} Code: ${error.code}.${error.correlationId ? ` Support reference: ${error.correlationId}.` : ''}`
}

function problemMessage(error: ApiError): string {
  switch (error.code) {
    case 'INVALID_CREDENTIALS': return 'Sign-in could not be completed. Check your email and password, or try again later.'
    case 'INVALID_REQUEST': return 'Review the submitted fields and try again.'
    case 'CSRF_INVALID': return 'Your page session changed. Please try again.'
    case 'AUTH_RATE_LIMITED': return 'Too many attempts. Wait a moment before trying again.'
    case 'AUTH_REQUIRED':
    case 'SESSION_EXPIRED': return 'Your session ended. Sign in again to continue.'
    case 'ACCESS_DENIED': return 'You do not have permission to perform this action.'
    case 'ACCESS_CONFLICT': return 'Access changed or this operation is unavailable. Reload and review. The last active Org Admin must remain.'
    case 'STORE_CONFLICT': return 'This store code is reserved, the retry changed, or a newer version was saved. Reload and review.'
    case 'STORE_INACTIVE': return 'This store is inactive. New operations are unavailable.'
    case 'CONFIGURATION_CONFLICT': return 'A newer configuration was saved or this route already exists. Reload the saved values and review before trying again.'
    case 'CONFIGURATION_INACTIVE': return 'A store, zone or lane is inactive. Choose active configuration before continuing.'
    case 'INCOMPATIBLE_ZONES': return 'Both zones must use the same temperature class. Select compatible active zones.'
    case 'ACCESS_RATE_LIMITED': return 'Too many invitation attempts. Try again later.'
    case 'INVITATION_UNAVAILABLE': return 'This invitation is unavailable for this account. It may have expired, been revoked, or already been claimed.'
    case 'RESOURCE_NOT_FOUND': return 'This organization is unavailable or your access has changed.'
    case 'ORGANIZATION_REQUIRED': return 'Select an organization to continue.'
    case 'ONBOARDING_CONFLICT': return 'Organization setup is already completed, or this retry changed. Refresh your organizations to continue.'
    default: return 'We could not complete this request. Please try again later.'
  }
}

export function requestSignal(signal?: AbortSignal): AbortSignal {
  return signal ? AbortSignal.any([signal, AbortSignal.timeout(8000)]) : AbortSignal.timeout(8000)
}

export async function csrf(signal?: AbortSignal, refresh = false) {
  signal?.throwIfAborted()
  if (refresh) clearCsrf()
  const generation = csrfGeneration
  if (!csrfToken && !csrfRequest) {
    const pending = api.GET('/api/v1/auth/csrf', { signal: requestSignal() }).then(({ data, error, response }) => {
      if (!data) throw new ApiError(response, error)
      if (generation === csrfGeneration) csrfToken = data.token
      return data.token
    })
    csrfRequest = pending
    void pending.finally(() => { if (csrfRequest === pending) csrfRequest = undefined }).catch(() => {})
  }
  // The token lives only in memory and stays bound to the server session. Reuse
  // avoids throttling legitimate signed-in writes at the anonymous bootstrap.
  const token = csrfToken ?? await csrfRequest!
  signal?.throwIfAborted()
  if (generation !== csrfGeneration) throw new DOMException('Session changed', 'AbortError')
  return { header: { 'X-CSRF-TOKEN': token } }
}
