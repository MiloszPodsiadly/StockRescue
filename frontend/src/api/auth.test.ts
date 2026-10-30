import { afterEach, expect, it, vi } from 'vitest'
import { errorMessage, getSession, signIn, signOut } from './auth'
import { clearCsrf } from './client'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

afterEach(() => { clearCsrf(); vi.unstubAllGlobals() })

it('obtains fresh CSRF before sending credentials with same-origin cookies', async () => {
  const fetcher = vi.fn()
    .mockResolvedValueOnce(json({ token: 'session-bound-value', headerName: 'X-CSRF-TOKEN' }))
    .mockResolvedValueOnce(json({ id: 'user', email: 'operator@example.test', createdAt: '2026-10-30T00:00:00Z' }))
  vi.stubGlobal('fetch', fetcher)
  await signIn({ email: 'operator@example.test', password: '  preserved passphrase  ' })
  const csrf = fetcher.mock.calls[0]?.[0] as Request
  const login = fetcher.mock.calls[1]?.[0] as Request
  expect(new URL(csrf.url).pathname).toBe('/api/v1/auth/csrf')
  expect(login.headers.get('X-CSRF-TOKEN')).toBe('session-bound-value')
  expect(login.credentials).toBe('same-origin')
  expect(await login.json()).toEqual({ email: 'operator@example.test', password: '  preserved passphrase  ' })
  expect(localStorage.length).toBe(0)
})

it('does not send a mutation when CSRF bootstrap fails or retry credentials', async () => {
  const fetcher = vi.fn().mockResolvedValue(json({ code: 'INTERNAL_ERROR', detail: 'private diagnostic' }, 503))
  vi.stubGlobal('fetch', fetcher)
  await expect(signIn({ email: 'operator@example.test', password: 'not-sent-passphrase' })).rejects.toThrow()
  expect(fetcher).toHaveBeenCalledTimes(1)
})

it('represents an expired session as anonymous and logs out only with CSRF', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ code: 'AUTH_REQUIRED' }, 401))
    .mockResolvedValueOnce(json({ token: 'fresh-value', headerName: 'X-CSRF-TOKEN' }))
    .mockResolvedValueOnce(new Response(null, { status: 204 }))
  vi.stubGlobal('fetch', fetcher)
  expect(await getSession(new AbortController().signal)).toBeNull()
  await signOut()
  expect((fetcher.mock.calls[2]?.[0] as Request).headers.get('X-CSRF-TOKEN')).toBe('fresh-value')
  expect(errorMessage(new Error('private diagnostic'))).not.toContain('private diagnostic')
})
