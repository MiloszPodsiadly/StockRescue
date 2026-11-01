import { AppLink } from '@/components/app-link'
import { useId, useLayoutEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { ApiError, errorMessage } from '@/api/client'
import { createStore, updateStore } from '@/api/stores'
import type { CreateStore, Store } from '@/api/stores'
import { useTenantKey, useTenantRequests } from '../organizations/tenant-requests'

const inputClass = 'mt-2 w-full min-w-0 rounded-xl border border-border bg-background px-4 py-3 text-base outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:opacity-60'
const codePattern = '[A-Za-z0-9][A-Za-z0-9_\\-]{0,31}'
type Fields = Pick<CreateStore, 'code' | 'name' | 'addressLine1' | 'city' | 'postalCode' | 'countryCode' | 'zoneId'>
const empty: Fields = { code: '', name: '', addressLine1: '', city: '', postalCode: '', countryCode: '', zoneId: '' }

export function StoreForm({ organizationId, store, onSaved }: { organizationId: string, store?: Store, onSaved: (store: Store) => Promise<void> }) {
  const formId = useId()
  const scope = useTenantKey()
  const [fields, setFields] = useState<Fields>(() => store ? { code: store.code, name: store.name, addressLine1: store.addressLine1 ?? '', city: store.city ?? '', postalCode: store.postalCode ?? '', countryCode: store.countryCode ?? '', zoneId: store.zoneId } : empty)
  const [reason, setReason] = useState('')
  const [submitted, setSubmitted] = useState<CreateStore | null>(null)
  const [saved, setSaved] = useState<Store | null>(null)
  const tenantSignal = useTenantRequests()
  const request = useRef<AbortController | null>(null)
  useLayoutEffect(() => () => { request.current?.abort() }, [])
  const action = useMutation({
    mutationKey: [...scope, 'store', store?.id ?? 'create'], gcTime: 0, retry: false,
    mutationFn: async (body: CreateStore) => {
      const controller = new AbortController()
      request.current = controller
      const signal = AbortSignal.any([tenantSignal(), controller.signal])
      const result = store
        ? await updateStore(organizationId, store.id, { name: fields.name, addressLine1: fields.addressLine1, city: fields.city, postalCode: fields.postalCode, countryCode: fields.countryCode, zoneId: fields.zoneId, version: store.version, reason }, signal)
        : await createStore(organizationId, body, signal)
      signal.throwIfAborted()
      setSaved(result)
      await onSaved(result)
    },
    onError: error => { if (error instanceof ApiError && error.status >= 400 && error.status < 500) setSubmitted(null) },
  })
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (action.isPending) return
    const body = submitted ?? { ...fields, requestId: crypto.randomUUID() }
    setSubmitted(body)
    action.mutate(body)
  }
  const frozen = action.isPending || submitted !== null
  const controls = [
    ['code', 'Store code', 32, true], ['name', 'Store name', 160, true], ['addressLine1', 'Address line', 200, false],
    ['city', 'City', 100, false], ['postalCode', 'Postal code', 20, false], ['countryCode', 'Country code', 2, false], ['zoneId', 'IANA timezone', 100, true],
  ] as const
  if (saved && !store) return <section aria-label="Store creation result" className="space-y-4 rounded-xl border border-primary/30 p-5"><p role="status">Store {saved.code} created.</p><AppLink href={`/organizations/${organizationId}/stores/${saved.id}`} className="inline-flex text-primary underline">Open created store</AppLink><div><Button onClick={() => { setSaved(null); setSubmitted(null); setFields(empty); action.reset() }}>Create another store</Button></div></section>
  return <form onSubmit={submit} className="space-y-5 rounded-2xl border border-border bg-card p-6 sm:p-8">
    <h3 className="text-xl font-semibold">{store ? 'Edit store' : 'Create a store'}</h3>
    <div className="grid gap-5 sm:grid-cols-2">{controls.map(([field, label, maxLength, required]) => <div key={field} className="min-w-0"><label htmlFor={`${formId}-${field}`} className="block text-sm font-medium">{label}</label><input id={`${formId}-${field}`} name={field} required={required} maxLength={maxLength} value={fields[field] ?? ''} readOnly={field === 'code' && Boolean(store)} disabled={frozen} autoComplete={field === 'name' ? 'organization' : 'off'} pattern={field === 'code' ? codePattern : undefined} aria-describedby={field === 'code' || field === 'zoneId' ? `${formId}-${field}-help` : undefined} placeholder={field === 'zoneId' ? 'Europe/Warsaw or UTC' : field === 'countryCode' ? 'PL' : undefined} onChange={event => { setFields(previous => ({ ...previous, [field]: event.target.value })) }} className={inputClass} />{field === 'code' && <p id={`${formId}-${field}-help`} className="mt-2 text-xs text-muted-foreground">Unique in this organization, including archived stores. The code stays fixed after creation.</p>}{field === 'zoneId' && <p id={`${formId}-${field}-help`} className="mt-2 text-xs text-muted-foreground">Choose the store's named timezone explicitly. UTC offsets and abbreviations are not accepted.</p>}</div>)}</div>
    {store && <label className="block text-sm font-medium">Reason for store change<textarea required maxLength={500} value={reason} disabled={frozen} onChange={event => { setReason(event.target.value) }} className={inputClass} /></label>}
    {action.isError && <p role="alert" className="rounded-xl border border-destructive/30 p-4 text-sm text-destructive">{errorMessage(action.error)}{submitted && ' Retry this same request to confirm an uncertain result.'}{store && action.error instanceof ApiError && action.error.status === 409 && ' Reload the saved store and review before editing again.'}</p>}
    <Button type="submit" disabled={action.isPending}>{action.isPending ? 'Saving store…' : submitted ? 'Retry same store request' : store ? 'Save store changes' : 'Create store'}</Button>
  </form>
}
