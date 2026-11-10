import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { listStores } from '@/api/stores'
import type { Store } from '@/api/stores'
import { Field, inputClass } from '@/components/form-field'
import { catalogRefresh, useCatalogScope } from './catalog-query'
import { Pager, QueryNotice } from './catalog-ui'

export function StorePicker({ organizationId, value, onChange, disabled = false, requiredAction }: {
  organizationId: string, value: string, onChange: (id: string) => void, disabled?: boolean,
  requiredAction?: Store['allowedActions'][number],
}) {
  const scope = useCatalogScope()
  const [offset, setOffset] = useState(0)
  const stores = useQuery({ queryKey: [...scope, 'stores', offset], queryFn: ({ signal }) => listStores(organizationId, 'ACTIVE', '', offset, signal), ...catalogRefresh })
  if (!stores.data || stores.isError) return <QueryNotice title={stores.isPending ? 'Loading accessible stores…' : 'Stores unavailable'} error={stores.error} reload={() => { void stores.refetch() }} />
  const items = requiredAction ? stores.data.items.filter(store => store.allowedActions.includes(requiredAction)) : stores.data.items
  return <section className="space-y-4 rounded-2xl border border-border p-5" aria-label="Store selection"><Field label="Store context" help={requiredAction ? 'Choose an active assigned store where this lot metadata was observed.' : 'Choose a current store for source observations, declared locations and a local food-safety assessment.'}>{(id, description) => <select id={id} aria-describedby={description} value={value} disabled={disabled || stores.isFetching} onChange={event => { onChange(event.target.value) }} className={inputClass}>
    <option value="">No store selected</option>{value && !items.some(store => store.id === value) && <option value={value}>Previous selection — check current access</option>}{items.map(store => <option key={store.id} value={store.id}>{store.code} · {store.name}</option>)}
  </select>}</Field>{items.length === 0 && <p className="text-sm">{requiredAction ? 'No active assigned stores on this page permit this action.' : 'No active stores on this page. Store access requires a current assignment.'}</p>}<Pager label="stores" offset={offset} hasMore={stores.data.hasMore} onChange={setOffset} /></section>
}
