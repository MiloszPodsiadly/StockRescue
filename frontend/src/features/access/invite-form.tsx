import { useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { errorMessage } from '@/api/client'
import { inviteStaff } from '@/api/access'
import type { StoreOption, StoreRole } from '@/api/access'
import { useTenantKey } from '../organizations/tenant-requests'

const inputClass = 'mt-2 w-full min-w-0 rounded-xl border border-border bg-background px-4 py-3 text-base outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:opacity-60'
export function InviteForm({ organizationId, stores, signal, onCreated }: { organizationId: string, stores: StoreOption[], signal: () => AbortSignal, onCreated: () => Promise<void> }) {
  const scope = useTenantKey()
  const [email, setEmail] = useState('')
  const [reason, setReason] = useState('')
  const [storeId, setStoreId] = useState('')
  const [role, setRole] = useState<StoreRole>('READ_ONLY_ANALYST')
  const [link, setLink] = useState('')
  const [copyStatus, setCopyStatus] = useState('')
  const invitation = useMutation({
    mutationKey: [...scope, 'invitation-create'], retry: false, gcTime: 0,
    mutationFn: async () => {
      const requestSignal = signal()
      const issued = await inviteStaff(organizationId, { email: email.trim(), reason, storeGrants: storeId ? [{ storeId, role }] : [] }, requestSignal)
      requestSignal.throwIfAborted()
      // Return no secret as mutation data. It exists only in this form until hidden/unmounted.
      setLink(`${window.location.origin}/invitations/accept#token=${issued.token}`)
      setCopyStatus('')
      await onCreated()
    },
  })
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!invitation.isPending) { setLink(''); invitation.mutate() }
  }
  return <section aria-labelledby="invite-heading" className="space-y-4 rounded-xl border border-border bg-background p-5"><h3 id="invite-heading" className="text-xl font-semibold">Invite a staff member</h3><p className="text-sm leading-relaxed text-muted-foreground">No email will be sent. Share the private link manually with the intended person through a trusted channel. It expires after 24 hours and can be claimed once. Access remains pending until an Org Admin verifies and approves the recipient.</p>
    {link ? <div className="space-y-4"><p role="status">Invitation created. It has not been emailed. This private link is shown only now.</p><label className="block text-sm">Private invitation link<input aria-label="Private invitation link" type="password" readOnly value={link} autoComplete="off" className={inputClass} /></label><div className="flex flex-wrap gap-3"><Button onClick={() => { void navigator.clipboard.writeText(link).then(() => { setCopyStatus('Private link copied. Share it securely.') }, () => { setCopyStatus('Copy is unavailable. Select and copy the link field manually.') }) }}>Copy private link</Button><Button variant="outline" onClick={() => { setLink(''); setEmail(''); setReason(''); invitation.reset() }}>Hide private link</Button></div>{copyStatus && <p role="status" className="text-sm">{copyStatus}</p>}</div> : <form onSubmit={submit} className="space-y-4"><label className="block text-sm font-medium">Recipient email<input type="email" required maxLength={254} autoComplete="email" disabled={invitation.isPending} value={email} onChange={event => { setEmail(event.target.value) }} className={inputClass} /></label><label className="block text-sm">Initial store (optional)<select value={storeId} disabled={invitation.isPending} onChange={event => { setStoreId(event.target.value) }} className={inputClass}><option value="">No initial store assignment</option>{stores.map(store => <option key={store.id} value={store.id}>{store.code} · {store.name}</option>)}</select></label>{storeId && <label className="block text-sm">Initial store role<select value={role} disabled={invitation.isPending} onChange={event => { setRole(event.target.value as StoreRole) }} className={inputClass}><option value="READ_ONLY_ANALYST">Read-only Analyst</option><option value="INVENTORY_OPERATOR">Inventory Operator</option><option value="STORE_MANAGER">Store Manager</option></select></label>}<label className="block text-sm font-medium">Reason for invitation<textarea required maxLength={500} value={reason} disabled={invitation.isPending} onChange={event => { setReason(event.target.value) }} className={inputClass} /></label>{invitation.isError && <p role="alert" className="text-sm text-destructive">{errorMessage(invitation.error)} Refresh invitation history before retrying an uncertain result; secrets cannot be retrieved again.</p>}<Button type="submit" disabled={invitation.isPending}>{invitation.isPending ? 'Creating invitation…' : 'Create private invitation'}</Button></form>}</section>
}
