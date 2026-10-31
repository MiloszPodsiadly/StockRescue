import { afterEach, expect, it, vi } from 'vitest'
import { archiveZone, cancelScheduledLaneRevisions } from './configuration'
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

it('submits the zone version and fingerprint from the same reviewed impact snapshot', async () => {
  const fetcher = vi.fn()
    .mockResolvedValueOnce(json({ token: 'session-token', headerName: 'X-CSRF-TOKEN' }))
    .mockResolvedValueOnce(json({ id: 'zone-a', active: false, version: 6 }))
  vi.stubGlobal('fetch', fetcher)
  const zone = {
    id: 'zone-a', name: 'Ambient', temperatureClass: 'AMBIENT' as const, active: true, version: 4,
    createdAt: '2026-10-31T12:00:00Z', updatedAt: '2026-10-31T12:00:00Z',
  }
  const impact = {
    activeLaneCount: 1, inactiveLaneCount: 0, futureScheduledRevisionCount: 1,
    zoneVersion: 5, impactFingerprint: 'A'.repeat(43), asOf: '2026-10-31T12:00:00Z',
  }

  await archiveZone('org-a', 'store-a', zone, 'Reviewed the current route impact', impact, new AbortController().signal)

  const request = fetcher.mock.calls[1]?.[0] as Request
  expect(await request.json()).toEqual({ version: 5, reason: 'Reviewed the current route impact', impactFingerprint: impact.impactFingerprint })
})
