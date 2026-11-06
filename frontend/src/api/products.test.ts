import { afterEach, expect, it, vi } from 'vitest'
import { clearCsrf } from './client'
import { archiveProduct, createProduct } from './products'

afterEach(() => { clearCsrf(); vi.unstubAllGlobals() })
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })

it('keeps the same creation intent on an explicit retry after an uncertain response and preserves GTIN zeroes', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ token: 'csrf' }))
    .mockRejectedValueOnce(new TypeError('Acknowledgement lost'))
    .mockResolvedValueOnce(json({ id: 'product', gtin: '036000291452', version: 0 }))
  vi.stubGlobal('fetch', fetcher)
  const body = { requestId: 'creation-request', sku: 'MILK', name: 'Milk', gtin: '036000291452', baseUnit: 'EA', temperatureClass: 'CHILLED' } as const
  const signal = new AbortController().signal
  await expect(createProduct('organization', body, signal)).rejects.toThrow('Acknowledgement lost')
  expect(fetcher).toHaveBeenCalledTimes(2)
  expect((await createProduct('organization', body, signal)).id).toBe('product')
  const first = fetcher.mock.calls[1][0] as Request
  const retry = fetcher.mock.calls[2][0] as Request
  expect(await first.json()).toEqual(body)
  expect(await retry.json()).toEqual(body)
  expect(retry.headers.get('X-CSRF-TOKEN')).toBe('csrf')
})

it('exposes an archive conflict without another write or a new retry key', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ token: 'csrf' }))
    .mockResolvedValueOnce(json({ code: 'CATALOG_CONFLICT', status: 409 }, 409))
  vi.stubGlobal('fetch', fetcher)
  const body = { requestId: 'archive-request', expectedVersion: 0, reason: 'Discontinued product reviewed' }
  await expect(archiveProduct('organization', 'product', body, new AbortController().signal)).rejects.toMatchObject({ status: 409, code: 'CATALOG_CONFLICT' })
  expect(fetcher).toHaveBeenCalledTimes(2)
  expect(await (fetcher.mock.calls[1][0] as Request).json()).toEqual(body)
})
