import { afterEach, expect, it, vi } from 'vitest'
import { clearCsrf } from './client'
import { archiveLot, correctLotMetadata, registerLot, registerSourceEvidence } from './lots'

afterEach(() => { clearCsrf(); vi.unstubAllGlobals() })
const json = (body: unknown) => new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })

it('preserves raw partial date evidence and the request key on explicit recovery after a lost response', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ token: 'csrf' })).mockRejectedValueOnce(new TypeError('Lost response'))
    .mockResolvedValueOnce(json({ id: 'lot', dateMarking: { labeledValue: ' 2027-04 ', precision: 'MONTH', normalizedCutoff: null } }))
  vi.stubGlobal('fetch', fetcher)
  const body = { requestId: 'request', productId: 'product', lotCode: 'LOT', identityState: 'CANDIDATE', manufacturerVerified: false,
    manufacturerProvenance: { source: 'Supplier statement' }, dateMarking: { labeledValue: ' 2027-04 ', type: 'BEST_BEFORE_END', precision: 'MONTH' } } as const
  const signal = new AbortController().signal
  await expect(registerLot('organization', body, signal)).rejects.toThrow('Lost response')
  expect(fetcher).toHaveBeenCalledTimes(2)
  expect((await registerLot('organization', body, signal)).dateMarking.normalizedCutoff).toBeNull()
  expect(await (fetcher.mock.calls[1][0] as Request).json()).toEqual(body)
  expect(await (fetcher.mock.calls[2][0] as Request).json()).toEqual(body)
})

it('keeps independent source registrations even when the supplier reference is shared', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(json({ token: 'csrf' })).mockResolvedValueOnce(json({ id: 'one' })).mockResolvedValueOnce(json({ id: 'two' }))
  vi.stubGlobal('fetch', fetcher)
  const common = { storeId: 'store', supplierReference: 'shared-reference', provenance: { source: 'Supplier document', reference: 'document' } }
  const signal = new AbortController().signal
  const one = await registerSourceEvidence('organization', 'lot', { ...common, requestId: 'request-one' }, signal)
  const two = await registerSourceEvidence('organization', 'lot', { ...common, requestId: 'request-two' }, signal)
  expect(one.id).not.toBe(two.id)
  expect(await (fetcher.mock.calls[1][0] as Request).json()).toEqual({ ...common, requestId: 'request-one' })
  expect(await (fetcher.mock.calls[2][0] as Request).json()).toEqual({ ...common, requestId: 'request-two' })
})

it('recovers a lost correction response with the same reviewed evidence and distinguishes current state from the recorded result', async () => {
  const recovered = { lot: { id: 'lot', version: 2 }, correction: { id: 'correction', resultVersion: 1 } }
  const fetcher = vi.fn().mockResolvedValueOnce(json({ token: 'csrf' })).mockRejectedValueOnce(new TypeError('Lost response'))
    .mockResolvedValueOnce(json(recovered))
  vi.stubGlobal('fetch', fetcher)
  const body = { requestId: 'request', expectedVersion: 0, reason: 'Reviewed original label', evidenceReviewed: true, identityState: 'CANDIDATE',
    manufacturerVerified: false, manufacturerProvenance: { source: 'Registry', reference: 'proof' },
    dateMarking: { labeledValue: ' 2027-05 ', type: 'BEST_BEFORE_END', precision: 'MONTH' } } as const
  const signal = new AbortController().signal
  await expect(correctLotMetadata('organization', 'lot', body, signal)).rejects.toThrow('Lost response')
  expect(fetcher).toHaveBeenCalledTimes(2)
  expect(await correctLotMetadata('organization', 'lot', body, signal)).toEqual(recovered)
  expect(await (fetcher.mock.calls[1][0] as Request).json()).toEqual(body)
  expect(await (fetcher.mock.calls[2][0] as Request).json()).toEqual(body)
})

it('recovers a lost archival acknowledgement with the same request, version and reason', async () => {
  const archived = { id: 'lot', active: false, version: 3 }
  const fetcher = vi.fn().mockResolvedValueOnce(json({ token: 'csrf' })).mockRejectedValueOnce(new TypeError('Lost response'))
    .mockResolvedValueOnce(json(archived))
  vi.stubGlobal('fetch', fetcher)
  const body = { requestId: 'request', expectedVersion: 2, reason: 'Supplier discontinued this lot identity' } as const
  const signal = new AbortController().signal
  await expect(archiveLot('organization', 'lot', body, signal)).rejects.toThrow('Lost response')
  expect(fetcher).toHaveBeenCalledTimes(2)
  expect(await archiveLot('organization', 'lot', body, signal)).toEqual(archived)
  expect((fetcher.mock.calls[1][0] as Request).url).toContain('/lots/lot/archive')
  expect(await (fetcher.mock.calls[1][0] as Request).json()).toEqual(body)
  expect(await (fetcher.mock.calls[2][0] as Request).json()).toEqual(body)
})
