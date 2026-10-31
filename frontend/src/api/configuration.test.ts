import { afterEach, expect, it, vi } from 'vitest'
import { cancelScheduledLaneRevisions } from './configuration'
import { clearCsrf } from './client'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const problem = (code: string, status: number) => ({
  type: 'about:blank', title: 'Request failed', status, code, detail: 'Review saved lane state.', correlationId: 'lane-test',
})

afterEach(() => { clearCsrf(); vi.unstubAllGlobals() })

it('refreshes session CSRF once after the server rejects a stale token', async () => {
  const responseBody = { lane: { id: 'lane-a', version: 3 }, cancelledRevisions: [] }
  const fetcher = vi.fn()
    .mockResolvedValueOnce(json({ token: 'old-session-token', headerName: 'X-CSRF-TOKEN' }))
    .mockResolvedValueOnce(json(problem('CSRF_INVALID', 403), 403))
    .mockResolvedValueOnce(json({ token: 'new-session-token', headerName: 'X-CSRF-TOKEN' }))
    .mockResolvedValueOnce(json(responseBody))
  vi.stubGlobal('fetch', fetcher)

  const body = { laneVersion: 3, expectedRevision: 2, reason: 'Reviewed cost schedule change' }
  const response = await cancelScheduledLaneRevisions('org-a', 'lane-a', body, new AbortController().signal)

  expect(response).toEqual(responseBody)
  expect(fetcher).toHaveBeenCalledTimes(4)
  const firstCancellation = fetcher.mock.calls[1]?.[0] as Request
  const retryCancellation = fetcher.mock.calls[3]?.[0] as Request
  expect(firstCancellation.headers.get('X-CSRF-TOKEN')).toBe('old-session-token')
  expect(retryCancellation.headers.get('X-CSRF-TOKEN')).toBe('new-session-token')
  expect(await retryCancellation.json()).toEqual(body)
})

it('does not retry a rejected cancellation command after a lane conflict', async () => {
  const fetcher = vi.fn()
    .mockResolvedValueOnce(json({ token: 'session-token', headerName: 'X-CSRF-TOKEN' }))
    .mockResolvedValueOnce(json(problem('SCHEDULED_REVISIONS_PENDING', 409), 409))
  vi.stubGlobal('fetch', fetcher)

  await expect(cancelScheduledLaneRevisions('org-a', 'lane-a', {
    laneVersion: 3, expectedRevision: 2, reason: 'Reviewed cost schedule change',
  }, new AbortController().signal)).rejects.toMatchObject({ code: 'SCHEDULED_REVISIONS_PENDING', status: 409 })
  expect(fetcher).toHaveBeenCalledTimes(2)
})
