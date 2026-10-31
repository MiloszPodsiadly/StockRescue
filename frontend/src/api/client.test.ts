import { afterEach, expect, it, vi } from 'vitest'
import { ApiError, clearCsrf, csrf } from './client'
import { signIn, signOut } from './auth'

const json = (body: unknown) => new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })
afterEach(() => { clearCsrf(); vi.unstubAllGlobals() })

it('shares one in-memory CSRF bootstrap across current-session writes and clears it at authentication boundaries', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ token: 'before-login' }))
    .mockResolvedValueOnce(json({ token: 'login-token' }))
    .mockResolvedValueOnce(json({ id: 'user', email: 'operator@example.test', createdAt: '2026-10-30T00:00:00Z' }))
    .mockResolvedValueOnce(json({ token: 'authenticated-token' }))
    .mockResolvedValueOnce(new Response(null, { status: 204 }))
    .mockResolvedValueOnce(json({ token: 'after-logout' }))
  vi.stubGlobal('fetch', fetcher)
  const [one, two] = await Promise.all([csrf(), csrf()])
  expect(one).toEqual(two)
  expect(fetcher).toHaveBeenCalledTimes(1)
  await signIn({ email: 'operator@example.test', password: 'preserved-passphrase' })
  expect((await csrf()).header['X-CSRF-TOKEN']).toBe('authenticated-token')
  expect((await csrf()).header['X-CSRF-TOKEN']).toBe('authenticated-token')
  expect(fetcher).toHaveBeenCalledTimes(4)
  await signOut()
  expect((fetcher.mock.calls[4]?.[0] as Request).headers.get('X-CSRF-TOKEN')).toBe('authenticated-token')
  expect((await csrf()).header['X-CSRF-TOKEN']).toBe('after-logout')
  expect(localStorage.length).toBe(0)
})

it('discards a rejected CSRF value without retrying a write and never sends one after its caller aborts', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ token: 'old-token' })).mockResolvedValueOnce(json({ token: 'fresh-token' }))
  vi.stubGlobal('fetch', fetcher)
  await csrf()
  new ApiError(new Response(null, { status: 403 }), { type: 'about:blank', title: 'Forbidden', status: 403, code: 'CSRF_INVALID', detail: 'Safe detail', correlationId: 'test-csrf' })
  expect((await csrf()).header['X-CSRF-TOKEN']).toBe('fresh-token')
  const controller = new AbortController()
  controller.abort()
  await expect(csrf(controller.signal)).rejects.toThrow()
  expect(fetcher).toHaveBeenCalledTimes(2)
})
