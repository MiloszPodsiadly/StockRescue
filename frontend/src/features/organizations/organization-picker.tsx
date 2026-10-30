import { Button } from '@/components/ui/button'
import { useId } from 'react'
import type { Organization } from '@/api/organizations'
import { errorMessage } from '@/api/client'
import type { useOrganizationSelection } from './use-organization-selection'

export function OrganizationPicker({ selection, current }: { selection: ReturnType<typeof useOrganizationSelection>, current?: Organization | null }) {
  const id = useId()
  const { memberships, offset, setOffset, switching, change } = selection
  const options = memberships.data?.items ?? []
  const selected = selection.selectionError || switching ? null : current ?? selection.active.data
  return <section aria-label="Organization selection" className="space-y-3">
    <label htmlFor={id} className="text-sm font-medium">Organization</label>
    <select id={id} value={selected?.id ?? ''} disabled={switching || memberships.isFetching || memberships.isError} onChange={event => { if (event.target.value) void change(event.target.value) }} className="w-full min-w-0 rounded-xl border border-border bg-background px-3 py-3 text-base focus-visible:outline-2 focus-visible:outline-primary disabled:opacity-60"><option value="" disabled>{switching ? 'Switching organization…' : memberships.isError ? 'Organizations unavailable' : 'Choose an organization'}</option>{selected && !options.some(organization => organization.id === selected.id) && <option value={selected.id}>{selected.name}</option>}{options.map(organization => <option key={organization.id} value={organization.id}>{organization.name}</option>)}</select>
    {(offset > 0 || memberships.data?.hasMore) && <div className="flex flex-wrap gap-2"><Button variant="outline" disabled={!offset || switching || memberships.isFetching} onClick={() => { setOffset(value => Math.max(0, value - 25)) }}>Previous organizations</Button><Button variant="outline" disabled={!memberships.data?.hasMore || switching || memberships.isFetching} onClick={() => { setOffset(value => value + 25) }}>More organizations</Button></div>}
    <Button variant="outline" disabled={switching || selection.active.isFetching} onClick={selection.reload}>Reload organizations</Button>
    {memberships.isError && <p role="alert" className="text-sm text-destructive">{errorMessage(memberships.error)}</p>}
  </section>
}
