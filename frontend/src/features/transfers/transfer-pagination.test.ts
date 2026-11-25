import { describe, expect, it, vi } from 'vitest'
import { loadStoreCursorPages, TransferRequestScheduler } from './transfer-pagination'

describe('per-store transfer pagination', () => {
  it('finishes stores with different page counts without requesting an exhausted store again', async () => {
    const calls: [string, string | null][] = []
    const load = vi.fn(async (storeId: string, cursor: string | null) => {
      calls.push([storeId, cursor])
      if (storeId === 'A' && cursor === null) return { items: Array.from({ length: 50 }, (_, i) => `a${i}`), nextCursor: 'a-page-2', hasMore: true }
      if (storeId === 'A' && cursor === 'a-page-2') return { items: Array.from({ length: 25 }, (_, i) => `a${i + 50}`), nextCursor: null, hasMore: false }
      if (storeId === 'B' && cursor === null) return { items: Array.from({ length: 50 }, (_, i) => `b${i}`), nextCursor: 'b-page-2', hasMore: true }
      if (storeId === 'B' && cursor === 'b-page-2') return { items: Array.from({ length: 50 }, (_, i) => `b${i + 50}`), nextCursor: 'b-page-3', hasMore: true }
      if (storeId === 'B' && cursor === 'b-page-3') return { items: Array.from({ length: 25 }, (_, i) => `b${i + 100}`), nextCursor: null, hasMore: false }
      throw new Error(`Unexpected page ${storeId}:${cursor}`)
    })

    let page = await loadStoreCursorPages(['A', 'B'], {}, load)
    const items = [...page.items]
    expect(page.hasMore).toBe(true)
    page = await loadStoreCursorPages(['A', 'B'], page.nextCursors, load)
    items.push(...page.items)
    expect(page.hasMore).toBe(true)
    page = await loadStoreCursorPages(['A', 'B'], page.nextCursors, load)
    items.push(...page.items)

    expect(page.hasMore).toBe(false)
    expect(items).toHaveLength(200)
    expect(load).toHaveBeenCalledTimes(5)
    expect(calls).toEqual([
      ['A', null], ['B', null], ['A', 'a-page-2'], ['B', 'b-page-2'], ['B', 'b-page-3'],
    ])
  })

  it('queries an empty store only for its first page while another store continues', async () => {
    const load = vi.fn(async (storeId: string, cursor: string | null) => storeId === 'empty'
      ? { items: [], nextCursor: null, hasMore: false }
      : cursor === null
        ? { items: ['first'], nextCursor: 'next', hasMore: true }
        : { items: ['second'], nextCursor: null, hasMore: false })
    const first = await loadStoreCursorPages(['empty', 'active'], {}, load)
    expect(first.hasMore).toBe(true)
    const second = await loadStoreCursorPages(['empty', 'active'], first.nextCursors, load)
    expect(second.hasMore).toBe(false)
    expect(load.mock.calls).toEqual([['empty', null], ['active', null], ['active', 'next']])
  })

  it('stops pagination when every store is exhausted', async () => {
    const load = vi.fn(async () => ({ items: ['item'], nextCursor: null, hasMore: false }))
    const page = await loadStoreCursorPages(['A', 'B'], {}, load)
    expect(page.hasMore).toBe(false)
    const later = await loadStoreCursorPages(['A', 'B'], page.nextCursors, load)
    expect(later.items).toEqual([])
    expect(later.hasMore).toBe(false)
    expect(load).toHaveBeenCalledTimes(2)
  })

  it('fails clearly when a server advertises another page without a usable cursor', async () => {
    const load = vi.fn(async () => ({ items: ['item'], nextCursor: '   ', hasMore: true }))
    await expect(loadStoreCursorPages(['A'], {}, load)).rejects.toThrow('advertises another page without a cursor')
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('releases a failed request slot and aborts the rest of that page batch', async () => {
    const scheduler = new TransferRequestScheduler(1)
    const controller = new AbortController()
    const load = vi.fn(async (storeId: string, _cursor: string | null, signal?: AbortSignal) => {
      if (storeId === 'fails') throw new Error('request failed')
      return new Promise<{ items: string[], nextCursor: string | null, hasMore: boolean }>((_resolve, reject) => {
        signal?.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })), { once: true })
      })
    })

    await expect(loadStoreCursorPages(['fails', 'queued'], {}, load, scheduler, controller.signal))
      .rejects.toThrow('request failed')
    expect(load).toHaveBeenCalledTimes(1)
    await expect(scheduler.run(new AbortController().signal, async () => 'available again')).resolves.toBe('available again')
  })

  it('removes a context-aborted queued request and drains the next workspace request', async () => {
    const scheduler = new TransferRequestScheduler(1)
    const activeController = new AbortController()
    const queuedController = new AbortController()
    const active = scheduler.run(activeController.signal, () => new Promise<string>((_resolve, reject) => {
      activeController.signal.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })), { once: true })
    }))
    const activeResult = expect(active).rejects.toMatchObject({ name: 'AbortError' })
    const queuedOperation = vi.fn(async () => 'stale context')
    const queued = scheduler.run(queuedController.signal, queuedOperation)
    const queuedResult = expect(queued).rejects.toMatchObject({ name: 'AbortError' })
    const current = scheduler.run(new AbortController().signal, async () => 'current context')

    queuedController.abort()
    await queuedResult
    activeController.abort()
    await activeResult
    await expect(current).resolves.toBe('current context')
    expect(queuedOperation).not.toHaveBeenCalled()
  })
})
