import { afterEach, expect, it, vi } from 'vitest'
import { clearCsrf } from './client'
import { createStorageLocation } from './storage-locations'
import { evaluateLotEligibility } from './food-safety'

afterEach(() => { clearCsrf(); vi.unstubAllGlobals() })
const json = (body: unknown) => new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })

it('preserves a location creation request during explicit recovery after a lost acknowledgement', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ token: 'csrf' })).mockRejectedValueOnce(new TypeError('Lost response'))
    .mockResolvedValueOnce(json({ id: 'location', code: 'A-01', usable: true }))
  vi.stubGlobal('fetch', fetcher)
  const body = { requestId: 'request', code: 'A-01', name: 'Picking shelf', zoneId: 'zone' }
  const signal = new AbortController().signal
  await expect(createStorageLocation('org', 'store', body, signal)).rejects.toThrow('Lost response')
  expect(fetcher).toHaveBeenCalledTimes(2)
  expect((await createStorageLocation('org', 'store', body, signal)).id).toBe('location')
  expect(await (fetcher.mock.calls[1][0] as Request).json()).toEqual(body)
  expect(await (fetcher.mock.calls[2][0] as Request).json()).toEqual(body)
})

it('sends the selected location as evidence context and leaves zone resolution to the server', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ decision: 'DENIED', reasonCodes: ['LOCATION_ARCHIVED'], locationId: 'location', locationVersion: 1 }))
  vi.stubGlobal('fetch', fetcher)
  const result = await evaluateLotEligibility('org', 'lot', { storeId: 'store', locationId: 'location' }, 'SALE', new AbortController().signal)
  expect(result.reasonCodes).toEqual(['LOCATION_ARCHIVED'])
  const query = new URL((fetcher.mock.calls[0][0] as Request).url).searchParams
  expect(query.get('locationId')).toBe('location')
  expect(query.has('zoneId')).toBe(false)
})
