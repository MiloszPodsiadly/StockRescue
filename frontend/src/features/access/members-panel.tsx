import { useCallback, useLayoutEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { StateMessage } from '@/components/state-message'
import { ApiError, errorMessage } from '@/api/client'
import { approveMembership, changeMemberRole, listAccessStores, listInvitations, listMembers, revokeInvitation, revokeMembership, revokeStoreGrant, setStoreGrant } from '@/api/access'
import type { Member, StoreOption, StoreRole } from '@/api/access'
import { useTenantKey, useTenantRequests } from '../organizations/tenant-requests'
import { InviteForm } from './invite-form'
import { Field } from '@/components/form-field'

const inputClass = 'mt-2 w-full min-w-0 rounded-xl border border-border bg-background px-4 py-3 text-base outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:opacity-60'
const roleLabels = { STORE_MANAGER: 'Store Manager', INVENTORY_OPERATOR: 'Inventory Operator', READ_ONLY_ANALYST: 'Read-only Analyst' } as const

export function MembersPanel({ organizationId }: { organizationId: string }) {
  const client = useQueryClient()
  const tenantSignal = useTenantRequests()
  const localRequests = useRef(new AbortController())
  useLayoutEffect(() => {
    if (localRequests.current.signal.aborted) localRequests.current = new AbortController()
    return () => { localRequests.current.abort() }
  }, [])
  const signal = useCallback(() => AbortSignal.any([tenantSignal(), localRequests.current.signal]), [tenantSignal])
  const [offset, setOffset] = useState(0)
  const [inviteOffset, setInviteOffset] = useState(0)
  const [storeOffset, setStoreOffset] = useState(0)
  const [filter, setFilter] = useState<Member['state'] | ''>('')
  const [editing, setEditing] = useState<string | null>(null)
  const [inviteToRevoke, setInviteToRevoke] = useState('')
  const [inviteReason, setInviteReason] = useState('')
  const [notice, setNotice] = useState('')
  const key = useTenantKey()
  const members = useQuery({ queryKey: [...key, 'members', offset, filter], queryFn: ({ signal }) => listMembers(organizationId, offset, filter || undefined, signal), refetchOnWindowFocus: true, refetchInterval: editing ? false : 15000 })
  const invitations = useQuery({ queryKey: [...key, 'invitations', inviteOffset], queryFn: ({ signal }) => listInvitations(organizationId, inviteOffset, signal), refetchOnWindowFocus: true, refetchInterval: 15000 })
  const stores = useQuery({ queryKey: [...key, 'access-stores', storeOffset], queryFn: ({ signal }) => listAccessStores(organizationId, storeOffset, signal), refetchOnWindowFocus: true })
  const action = useMutation({
    mutationKey: [...key, 'security-change'], retry: false, gcTime: 0,
    mutationFn: async (command: (signal: AbortSignal) => Promise<unknown>) => {
      const requestSignal = signal()
      await command(requestSignal)
      requestSignal.throwIfAborted()
    },
    onMutate: async () => { setNotice(''); await client.cancelQueries({ queryKey: [...key, 'members'] }) },
    onSuccess: () => { setNotice('Access change saved.'); setEditing(null); setInviteToRevoke(''); setInviteReason('') },
    onSettled: async (_data, error) => {
      if (localRequests.current.signal.aborted) return
      await Promise.all([client.invalidateQueries({ queryKey: [...key, 'members'] }), client.invalidateQueries({ queryKey: [...key, 'invitations'] })])
      if (!error || error instanceof ApiError && (error.status === 403 || error.status === 404 || error.status === 401)) {
        void client.invalidateQueries({ queryKey: [...key, 'effective-permissions'] })
      }
    },
  })
  const refreshInvitations = async () => { await client.invalidateQueries({ queryKey: [...key, 'invitations'] }) }
  if (members.isPending || invitations.isPending || stores.isPending) return <StateMessage state="loading" title="Loading members and access" description="Checking current administrator access." />
  if (members.isError || invitations.isError || stores.isError) return <div className="space-y-4"><StateMessage state="unavailable" title="Access management unavailable" description={errorMessage(members.error ?? invitations.error ?? stores.error)} /><Button variant="outline" onClick={() => { void client.invalidateQueries({ queryKey: key }) }}>Check access again</Button></div>
  const selected = members.data.items.find(member => member.id === editing)
  return (
    <section aria-labelledby="members-heading" className="space-y-7">
      <div><h2 id="members-heading" className="text-2xl font-semibold">Members and access</h2><p className="mt-3 leading-relaxed text-muted-foreground">Org Admins manage organization access. Store Manager, Inventory Operator and Read-only Analyst roles apply only to explicitly assigned stores. Revoking organization membership also revokes its store grants.</p></div>
      <Button variant="outline" disabled={action.isPending} onClick={() => { void client.invalidateQueries({ queryKey: key }) }}>Reload access</Button>
      {notice && <p role="status">{notice}</p>}
      {action.isError && <p role="alert" className="rounded-xl border border-destructive/30 p-4 text-sm text-destructive">{errorMessage(action.error)}</p>}
      <div><label htmlFor="member-state" className="text-sm font-medium">Membership state</label><select id="member-state" value={filter} disabled={action.isPending} onChange={event => { setFilter(event.target.value as Member['state'] | ''); setOffset(0); setEditing(null) }} className={inputClass}><option value="">All states</option><option value="ACTIVE">Active</option><option value="PENDING">Pending identity review</option><option value="REVOKED">Revoked</option></select></div>
      {action.isPending || members.isFetching ? <StateMessage state="loading" title="Checking current access changes" description="Waiting for the committed result and refreshed memberships." />
        : members.data.items.length === 0 ? <StateMessage state="empty" title="No memberships in this view" description="Change the filter or invite a staff member." />
          : <ul className="space-y-4">{members.data.items.map(member => (
            <li key={member.id} className="rounded-xl border border-border bg-background p-5">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0"><p className="break-all font-medium">{member.email}</p><p className="mt-2 text-sm text-muted-foreground">{member.role === 'ORG_ADMIN' ? 'Org Admin' : 'Member'} · {member.state === 'PENDING' ? 'Pending identity review' : member.state === 'ACTIVE' ? 'Active' : 'Revoked'}</p></div>
                {member.allowedActions.length > 0 && <Button variant="outline" aria-label={`Manage access for ${member.email}`} onClick={() => { setEditing(value => value === member.id ? null : member.id); action.reset() }}>Manage access</Button>}
              </div>
              {member.grants.length > 0 && <ul className="mt-4 space-y-2 text-sm">{member.grants.map(grant => <li key={grant.storeId} className="break-words">{stores.data.items.find(store => store.id === grant.storeId)?.name ?? 'Store outside the current active-store page'} — {roleLabels[grant.role]} · {grant.state === 'ACTIVE' ? 'Active' : 'Revoked'}</li>)}</ul>}
            </li>
          ))}</ul>}
      <Pager offset={offset} hasMore={members.data.hasMore} pending={action.isPending || members.isFetching} label="members" change={value => { setOffset(value); setEditing(null) }} />
      {selected && selected.allowedActions.length > 0 && <div hidden={action.isPending || members.isFetching}><MemberEditor key={`${selected.id}:${selected.version}`} member={selected} stores={stores.data.items} onSubmit={command => { if (!action.isPending) action.mutate(command) }} organizationId={organizationId} /></div>}
      <div><p className="text-sm text-muted-foreground">Store choices show active stores in this organization. A missing store may be on another page or archived.</p><Pager offset={storeOffset} hasMore={stores.data.hasMore} pending={stores.isFetching || action.isPending} label="store choices" change={setStoreOffset} /></div>
      <InviteForm organizationId={organizationId} stores={stores.data.items} signal={signal} onCreated={refreshInvitations} />
      <section aria-labelledby="invitations-heading" className="space-y-4">
        <h3 id="invitations-heading" className="text-xl font-semibold">Invitation history</h3>
        {invitations.isFetching ? <StateMessage state="loading" title="Refreshing invitations" description="Checking current invitation status." />
          : invitations.data.items.length === 0 ? <StateMessage state="empty" title="No invitations yet" description="Create an invitation to add a staff member." />
            : <ul className="space-y-3">{invitations.data.items.map(invitation => (
              <li key={invitation.id} className="rounded-xl border border-border p-4">
                <p className="break-all font-medium">{invitation.email}</p>
                <p className="mt-2 text-sm text-muted-foreground">{invitation.status} · expires {new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(invitation.expiresAt))}</p>
                {invitation.status === 'PENDING' && <Button variant="outline" className="mt-3" aria-label={`Revoke invitation for ${invitation.email}`} disabled={action.isPending} onClick={() => { setInviteToRevoke(invitation.id); setInviteReason('') }}>Revoke invitation</Button>}
                {inviteToRevoke === invitation.id && <form className="mt-4 space-y-3" onSubmit={event => { event.preventDefault(); action.mutate(requestSignal => revokeInvitation(organizationId, invitation, inviteReason, requestSignal)) }}><label className="block text-sm">Reason for revoking invitation<input required maxLength={500} value={inviteReason} onChange={event => { setInviteReason(event.target.value) }} className={inputClass} /></label><Button type="submit" disabled={action.isPending}>Confirm invitation revocation</Button></form>}
              </li>
            ))}</ul>}
        <Pager offset={inviteOffset} hasMore={invitations.data.hasMore} pending={invitations.isFetching || action.isPending} label="invitations" change={setInviteOffset} />
      </section>
    </section>
  )
}

function Pager({ offset, hasMore, pending, label, change }: { offset: number, hasMore: boolean, pending: boolean, label: string, change: (offset: number) => void }) {
  if (!offset && !hasMore) return null
  return <div className="mt-4 flex flex-wrap gap-3"><Button variant="outline" disabled={!offset || pending} onClick={() => { change(Math.max(0, offset - 25)) }}>Previous {label}</Button><Button variant="outline" disabled={!hasMore || pending} onClick={() => { change(offset + 25) }}>More {label}</Button></div>
}

function MemberEditor({ member, stores, organizationId, onSubmit }: { member: Member, stores: StoreOption[], organizationId: string, onSubmit: (command: (signal: AbortSignal) => Promise<unknown>) => void }) {
  const [operation, setOperation] = useState<Member['allowedActions'][number]>(() => member.state === 'PENDING' && member.allowedActions.includes('APPROVE') ? 'APPROVE' : member.allowedActions.includes('SET_GRANT') ? 'SET_GRANT' : member.allowedActions.includes('CHANGE_ROLE') ? 'CHANGE_ROLE' : member.allowedActions[0])
  const [reason, setReason] = useState('')
  const [verified, setVerified] = useState(false)
  const [verifiedId, setVerifiedId] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [role, setRole] = useState<Member['role']>(member.role)
  const [storeId, setStoreId] = useState('')
  const [storeRole, setStoreRole] = useState<StoreRole>('READ_ONLY_ANALYST')
  const labels = { APPROVE: 'Approve verified recipient', CHANGE_ROLE: 'Change organization role', REVOKE: 'Revoke organization membership', SET_GRANT: 'Set store access', REVOKE_GRANT: 'Revoke store access' } as const
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (operation === 'APPROVE' && (!verified || verifiedId !== member.userId)) return
    if ((operation === 'REVOKE' || operation === 'REVOKE_GRANT') && !confirmed) return
    onSubmit(signal => {
      switch (operation) {
        case 'APPROVE': return approveMembership(organizationId, member, verifiedId, reason, signal)
        case 'CHANGE_ROLE': return changeMemberRole(organizationId, member, role, reason, signal)
        case 'REVOKE': return revokeMembership(organizationId, member, reason, signal)
        case 'SET_GRANT': return setStoreGrant(organizationId, member, storeId, storeRole, reason, signal)
        case 'REVOKE_GRANT': return revokeStoreGrant(organizationId, member, storeId, reason, signal)
      }
    })
  }
  return <form onSubmit={submit} className="space-y-5 rounded-xl border border-primary/30 bg-background p-5"><h3 className="break-all text-lg font-semibold">Manage {member.email}</h3><Field label="Access operation">{id => <select id={id} value={operation} onChange={event => { setOperation(event.target.value as typeof operation); setConfirmed(false); setStoreId('') }} className={inputClass}>{member.allowedActions.map(action => <option key={action} value={action}>{labels[action]}</option>)}</select>}</Field>
    {operation === 'APPROVE' && <div className="space-y-4 rounded-xl border border-border p-4"><p className="text-sm leading-relaxed">Registration does not verify the email address. Contact the intended person through a trusted channel, have them sign in, and compare their account reference before activating access.</p><p className="break-all text-sm">Claimed account reference: <code>{member.userId}</code></p><div className="text-sm"><p className="font-medium">Store assignments activated by this approval</p>{member.plannedGrants.length ? <ul className="mt-2 space-y-2">{member.plannedGrants.map(grant => <li key={grant.storeId} className="break-all">{stores.find(store => store.id === grant.storeId)?.name ?? `Store reference ${grant.storeId}`} · {roleLabels[grant.role]}</li>)}</ul> : <p className="mt-2 text-muted-foreground">No store assignments.</p>}</div><label className="block text-sm">Verified account reference<input required value={verifiedId} onChange={event => { setVerifiedId(event.target.value) }} className={inputClass} autoComplete="off" /></label><label className="flex items-start gap-3 text-sm"><input type="checkbox" required checked={verified} onChange={event => { setVerified(event.target.checked) }} className="mt-1" />I independently verified the intended person and this account reference.</label></div>}
    {operation === 'CHANGE_ROLE' && <Field label="New organization role" help="The last active Org Admin must remain. Organization role changes are audited.">{(id, description) => <select id={id} aria-describedby={description} value={role} onChange={event => { setRole(event.target.value as typeof role) }} className={inputClass}><option value="MEMBER">Member</option><option value="ORG_ADMIN">Org Admin — manages all organization access</option></select>}</Field>}
    {(operation === 'SET_GRANT' || operation === 'REVOKE_GRANT') && <label className="block text-sm">Store for access change<select required value={storeId} onChange={event => { setStoreId(event.target.value) }} className={inputClass}><option value="" disabled>Choose a store</option>{operation === 'SET_GRANT' ? stores.map(store => <option key={store.id} value={store.id}>{store.code} · {store.name}</option>) : member.grants.filter(grant => grant.state === 'ACTIVE').map(grant => <option key={grant.storeId} value={grant.storeId}>{stores.find(store => store.id === grant.storeId)?.name ?? `Store reference ${grant.storeId}`}</option>)}</select></label>}
    {operation === 'SET_GRANT' && <label className="block text-sm">Store role<select value={storeRole} onChange={event => { setStoreRole(event.target.value as StoreRole) }} className={inputClass}>{Object.entries(roleLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>}
    <Field label="Reason for access change" help="Explain the business reason. Do not enter passwords, invitation links or other secrets.">{(id, description) => <textarea id={id} aria-describedby={description} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
    {(operation === 'REVOKE' || operation === 'REVOKE_GRANT') && <label className="flex items-start gap-3 text-sm"><input type="checkbox" required checked={confirmed} onChange={event => { setConfirmed(event.target.checked) }} className="mt-1" />I confirm this access should be revoked.</label>}
    <Button type="submit" disabled={operation === 'APPROVE' && (!verified || verifiedId !== member.userId) || operation === 'CHANGE_ROLE' && role === member.role}>Apply access change</Button></form>
}
