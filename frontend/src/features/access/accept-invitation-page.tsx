import { useLayoutEffect, useRef, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { StateMessage } from '@/components/state-message'
import { WorkspaceFrame } from '@/components/workspace-frame'
import { AppLink } from '@/components/app-link'
import { Field, inputClass } from '@/components/form-field'
import { errorMessage } from '@/api/client'
import { acceptInvitation } from '@/api/access'
import type { Account } from '@/api/auth'
import { useSession } from '../auth/session'

export function AcceptInvitationPage() {
  const [initialToken] = useState(() => new URLSearchParams(window.location.hash.slice(1)).get('token') ?? '')
  const session = useSession()
  useLayoutEffect(() => { window.history.replaceState(window.history.state, '', window.location.pathname) }, [])
  return <WorkspaceFrame><section className="mx-auto max-w-2xl space-y-7 rounded-2xl border border-border bg-card p-5 sm:p-8"><h1 className="text-3xl font-semibold">Accept a staff invitation</h1><p className="leading-relaxed text-muted-foreground">Use the account matching the invited email address. Claiming this invitation requests an administrator identity review. It does not activate organization or store access.</p>
    {session.isPending ? <StateMessage state="loading" title="Checking your session" description="Resolving your signed-in account." /> : session.isError ? <div className="space-y-4"><StateMessage state="error" title="Session unavailable" description={errorMessage(session.error)} /><Button onClick={() => { void session.refetch() }}>Check session again</Button></div> : !session.data ? <div className="space-y-4"><StateMessage state="empty" title="Sign in as the intended recipient" description="Keep this page open. Sign in or register in another tab, then return here to preserve the private invitation in memory." /><div className="flex flex-wrap gap-4"><a href="/sign-in" target="_blank" rel="noopener noreferrer" className="text-primary underline">Sign in in another tab</a><a href="/sign-up" target="_blank" rel="noopener noreferrer" className="text-primary underline">Create an account in another tab</a></div><Button onClick={() => { void session.refetch() }}>Check signed-in account</Button></div> : <InvitationClaim key={session.data.id} account={session.data} initialToken={initialToken} />}
  </section></WorkspaceFrame>
}
function InvitationClaim({ account, initialToken }: { account: Account, initialToken: string }) {
  const [token, setToken] = useState(initialToken)
  const [accepted, setAccepted] = useState(false)
  const request = useRef<AbortController | null>(null)
  useLayoutEffect(() => () => { request.current?.abort() }, [])
  const claim = useMutation({ mutationKey: ['identity', account.id, 'invitation-claim'], retry: false, gcTime: 0,
    mutationFn: async () => {
      const controller = new AbortController(); request.current = controller
      await acceptInvitation(token, controller.signal)
      controller.signal.throwIfAborted()
      setToken(''); setAccepted(true)
    },
  })
  if (accepted) return <div className="space-y-5"><StateMessage state="empty" title="Awaiting administrator review" description="Your invitation was claimed. Ask the administrator to verify you through a trusted channel and approve this account before access becomes active." /><div><p className="text-sm font-medium">Your account reference</p><p className="mt-2 break-all font-mono text-sm">{account.id}</p></div><AppLink href="/account" className="inline-flex text-primary underline">Return to your account</AppLink></div>
  return <form className="space-y-5" onSubmit={event => { event.preventDefault(); if (!claim.isPending) claim.mutate() }}><p className="break-all text-sm">Signed in as {account.email}</p><Field label="Invitation code" help="The private link is removed from the address bar and is never saved in browser storage. If you refresh before claiming it, open the original link again.">{(id, description) => <input id={id} aria-describedby={description} type="password" required pattern={'[A-Za-z0-9_\\-]{43}'} maxLength={43} autoComplete="off" disabled={claim.isPending} value={token} onChange={event => { setToken(event.target.value) }} className={inputClass} />}</Field>{claim.isError && <p role="alert" className="text-sm text-destructive">{errorMessage(claim.error)} If the result was uncertain, ask the administrator to check pending membership before retrying.</p>}<Button type="submit" disabled={claim.isPending}>{claim.isPending ? 'Requesting review…' : 'Request membership review'}</Button></form>
}
