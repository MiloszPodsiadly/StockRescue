export type StoreCursorState =
  | { kind: 'NOT_STARTED' }
  | { kind: 'NEXT', cursor: string }
  | { kind: 'EXHAUSTED' }

export type StoreCursorStates = Record<string, StoreCursorState>

export type StoreCursorPage<T> = { items: T[], nextCursor: string | null, hasMore: boolean }

type ScheduledRequest = {
  signal: AbortSignal
  operation: () => Promise<unknown>
  resolve: (value: unknown) => void
  reject: (reason?: unknown) => void
  state: 'QUEUED' | 'RUNNING'
  settled: boolean
  abort: () => void
}

function requestAbortError() {
  const error = new Error('Transfer request was aborted')
  error.name = 'AbortError'
  return error
}

/** A per-workspace FIFO limit shared by queue, transit and evidence requests. */
export class TransferRequestScheduler {
  private readonly queue: ScheduledRequest[] = []
  private active = 0

  constructor(private readonly maximumConcurrent = 8) {
    if (!Number.isInteger(maximumConcurrent) || maximumConcurrent < 1) {
      throw new RangeError('Transfer request concurrency must be a positive integer')
    }
  }

  run<T>(signal: AbortSignal, operation: () => Promise<T>): Promise<T> {
    if (signal.aborted) return Promise.reject(requestAbortError())
    return new Promise<T>((resolve, reject) => {
      const request: ScheduledRequest = {
        signal,
        operation,
        resolve: value => resolve(value as T),
        reject,
        state: 'QUEUED',
        settled: false,
        abort: () => undefined,
      }
      request.abort = () => {
        if (request.settled) return
        if (request.state === 'QUEUED') {
          const index = this.queue.indexOf(request)
          if (index >= 0) this.queue.splice(index, 1)
        }
        request.settled = true
        reject(requestAbortError())
      }
      signal.addEventListener('abort', request.abort, { once: true })
      this.queue.push(request)
      this.drain()
    })
  }

  private drain() {
    while (this.active < this.maximumConcurrent && this.queue.length > 0) {
      const request = this.queue.shift()!
      if (request.signal.aborted) {
        request.abort()
        continue
      }
      request.state = 'RUNNING'
      this.active++
      void Promise.resolve()
        .then(() => request.signal.aborted ? Promise.reject(requestAbortError()) : request.operation())
        .then(value => {
          if (!request.settled) {
            request.settled = true
            request.resolve(value)
          }
        }, error => {
          if (!request.settled) {
            request.settled = true
            request.reject(error)
          }
        })
        .finally(() => {
          request.signal.removeEventListener('abort', request.abort)
          this.active--
          this.drain()
        })
    }
  }
}

export async function loadStoreCursorPages<T>(storeIds: string[], states: StoreCursorStates,
  load: (storeId: string, cursor: string | null, signal?: AbortSignal) => Promise<StoreCursorPage<T>>,
  scheduler?: TransferRequestScheduler, signal?: AbortSignal) {
  const batch = new AbortController()
  const abortBatch = () => { batch.abort() }
  signal?.addEventListener('abort', abortBatch, { once: true })
  if (signal?.aborted) batch.abort()
  try {
    const pages = await Promise.all(storeIds.flatMap(storeId => {
      const state = states[storeId] ?? { kind: 'NOT_STARTED' as const }
      if (state.kind === 'EXHAUSTED') return []
      const cursor = state.kind === 'NEXT' ? state.cursor : null
      const request = () => (scheduler || signal ? load(storeId, cursor, batch.signal) : load(storeId, cursor))
        .then(page => ({ storeId, page }))
      return [scheduler ? scheduler.run(batch.signal, request) : request()]
    }))
    const nextCursors = { ...states }
    for (const { storeId, page } of pages) {
      if (page.hasMore) {
        if (!page.nextCursor?.trim()) throw new Error(`Transfer page for store ${storeId} advertises another page without a cursor`)
        nextCursors[storeId] = { kind: 'NEXT', cursor: page.nextCursor }
      } else {
        nextCursors[storeId] = { kind: 'EXHAUSTED' }
      }
    }
    return {
      items: pages.flatMap(({ page }) => page.items),
      nextCursors,
      hasMore: Object.values(nextCursors).some(state => state.kind === 'NEXT'),
    }
  } catch (error) {
    batch.abort()
    throw error
  } finally {
    signal?.removeEventListener('abort', abortBatch)
  }
}
