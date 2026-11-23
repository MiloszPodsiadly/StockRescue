export type StoreCursorState =
  | { kind: 'NOT_STARTED' }
  | { kind: 'NEXT', cursor: string }
  | { kind: 'EXHAUSTED' }

export type StoreCursorStates = Record<string, StoreCursorState>

export type StoreCursorPage<T> = { items: T[], nextCursor: string | null, hasMore: boolean }

export async function loadStoreCursorPages<T>(storeIds: string[], states: StoreCursorStates,
  load: (storeId: string, cursor: string | null) => Promise<StoreCursorPage<T>>) {
  const pages = await Promise.all(storeIds.flatMap(storeId => {
    const state = states[storeId] ?? { kind: 'NOT_STARTED' as const }
    if (state.kind === 'EXHAUSTED') return []
    return [load(storeId, state.kind === 'NEXT' ? state.cursor : null).then(page => ({ storeId, page }))]
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
}
