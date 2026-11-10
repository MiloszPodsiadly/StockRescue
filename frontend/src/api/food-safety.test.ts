import { afterEach, expect, it, vi } from 'vitest'
import { clearCsrf } from './client'
import { evaluateLotEligibility, recordLotSafetyEvidence } from './food-safety'

afterEach(() => { clearCsrf(); vi.unstubAllGlobals() })
const json = (body: unknown) => new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })

it('requests a fresh decision for the explicit store context and never reuses a prior policy result', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ decision: 'REQUIRES_REVIEW', policy: { version: 1 } }))
    .mockResolvedValueOnce(json({ decision: 'DENIED', policy: { version: 2 } }))
  vi.stubGlobal('fetch', fetcher)
  const signal = new AbortController().signal
  expect((await evaluateLotEligibility('organization', 'lot', { storeId: 'store' }, 'SALE', signal)).policy?.version).toBe(1)
  expect((await evaluateLotEligibility('organization', 'lot', { storeId: 'store', zoneId: 'zone' }, 'TRANSFER', signal)).decision).toBe('DENIED')
  const requests = fetcher.mock.calls.map(call => call[0] as Request)
  expect(requests).toHaveLength(2)
  expect(requests.every(request => request.method === 'GET' && request.cache === 'no-store')).toBe(true)
  expect(new URL(requests[0].url).searchParams.has('zoneId')).toBe(false)
  expect(new URL(requests[1].url).searchParams.get('zoneId')).toBe('zone')
  expect(new URL(requests[1].url).searchParams.get('operation')).toBe('TRANSFER')
})

it('recovers a lost block-evidence response with the original request id and expected version', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ token: 'csrf' })).mockRejectedValueOnce(new TypeError('Lost response'))
    .mockResolvedValueOnce(json({ lot: { version: 2, recallState: 'RECALLED' }, correction: { kind: 'SAFETY_STATUS' } }))
  vi.stubGlobal('fetch', fetcher)
  const body = { requestId: 'request', expectedVersion: 1, reason: 'Reviewed recall notice', recallState: 'RECALLED', damageState: 'UNKNOWN', provenance: { source: 'Manufacturer', reference: 'notice' } } as const
  const signal = new AbortController().signal
  await expect(recordLotSafetyEvidence('organization', 'lot', body, signal)).rejects.toThrow('Lost response')
  expect(fetcher).toHaveBeenCalledTimes(2)
  expect((await recordLotSafetyEvidence('organization', 'lot', body, signal)).lot.recallState).toBe('RECALLED')
  expect(await (fetcher.mock.calls[1][0] as Request).json()).toEqual(body)
  expect(await (fetcher.mock.calls[2][0] as Request).json()).toEqual(body)
})
