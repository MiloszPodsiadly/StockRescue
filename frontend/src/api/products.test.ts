import { afterEach, expect, it, vi } from 'vitest'
import { clearCsrf } from './client'
import { archiveProduct, confirmOperatorClassification, createProduct, getOperatorClassificationConfirmation, isValidGtin, reviewProductGtin } from './products'

afterEach(() => { clearCsrf(); vi.unstubAllGlobals() })
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })

it('keeps the same creation intent on an explicit retry after an uncertain response and preserves GTIN zeroes', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ token: 'csrf' }))
    .mockRejectedValueOnce(new TypeError('Acknowledgement lost'))
    .mockResolvedValueOnce(json({ id: 'product', gtin: '036000291452', version: 0 }))
  vi.stubGlobal('fetch', fetcher)
  const body = { requestId: 'creation-request', sku: 'MILK', name: 'Milk', gtin: '036000291452', baseUnit: 'EA', category: 'DAIRY', temperatureClass: 'CHILLED' } as const
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

it('sends a source-bound safety-attribute command with the expected product version', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ token: 'csrf' }))
    .mockResolvedValueOnce(json({ product: { id: 'product', category: 'BAKERY', version: 1 }, confirmation: { evidenceReference: 'package:category-label' }, status: 'CURRENT_OPERATOR_CONFIRMED' }, 201))
  vi.stubGlobal('fetch', fetcher)
  const body = { requestId: 'classification-confirmation', expectedProductVersion: 0, category: 'BAKERY', temperatureClass: 'AMBIENT', productProfile: 'PACKAGED_SHELF_STABLE_DRY_BAKERY', sourceType: 'ORIGINAL_PACKAGE', evidenceReference: 'package:category-label', reason: 'Package label reviewed' } as const
  const result = await confirmOperatorClassification('organization', 'product', body, new AbortController().signal)
  expect(result.product.category).toBe('BAKERY')
  expect(result.status).toBe('CURRENT_OPERATOR_CONFIRMED')
  expect((fetcher.mock.calls[1][0] as Request).url).toContain('/products/product/classification-evidence/operator-confirmations')
  expect(await (fetcher.mock.calls[1][0] as Request).json()).toEqual(body)
})

it('reads the latest operator confirmation and preserves the not-recorded status', async () => {
  const fetcher = vi.fn().mockResolvedValue(json({ product: { id: 'product', category: 'BAKERY', version: 1 }, confirmation: null, status: 'NOT_RECORDED' }))
  vi.stubGlobal('fetch', fetcher)
  const result = await getOperatorClassificationConfirmation('organization', 'product', new AbortController().signal)
  expect(result.status).toBe('NOT_RECORDED')
  const request = fetcher.mock.calls[0][0] as Request
  expect(request.url).toContain('/products/product/classification-evidence/operator-confirmations')
  expect(request.method).toBe('GET')
  expect(fetcher).toHaveBeenCalledTimes(1)
})

it('reviews exact GTINs as text and preserves leading zeroes in the scoped request', async () => {
  const fetcher = vi.fn().mockResolvedValue(json({ gtin: '036000291452', matches: [], offset: 0, limit: 25, hasMore: false, matchSetRevision: null }))
  vi.stubGlobal('fetch', fetcher)
  const result = await reviewProductGtin('organization', '036000291452', 0, new AbortController().signal)
  expect(result.gtin).toBe('036000291452')
  const request = fetcher.mock.calls[0][0] as Request
  const url = new URL(request.url)
  expect(url.pathname).toContain('/organizations/organization/products/gtin-review')
  expect(url.searchParams.get('gtin')).toBe('036000291452')
  expect(url.searchParams.get('offset')).toBe('0')
  expect(url.searchParams.get('limit')).toBe('25')
})

it('validates GTIN length and check digit without converting away leading zeroes', () => {
  expect(isValidGtin('036000291452')).toBe(true)
  expect(isValidGtin('000000000000')).toBe(true)
  expect(isValidGtin('036000291453')).toBe(false)
  expect(isValidGtin('36000291452')).toBe(false)
})
