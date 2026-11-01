import { useState } from 'react'
import type { ReactNode } from 'react'
import { flushSync } from 'react-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { errorMessage, signOut } from '@/api/auth'
import type { Account } from '@/api/auth'
import { WorkspaceFrame } from '@/components/workspace-frame'
import { AppLink } from '@/components/app-link'
import { StateMessage } from '@/components/state-message'
import { Button } from '@/components/ui/button'
import { useSession } from './session'

export function Workspace({ children }: { children: (account: Account, onSignOut: () => void) => ReactNode }) {
  const session = useSession()
  const client = useQueryClient()
  const navigate = useNavigate()
  const [closing, setClosing] = useState(false)
  const logout = useMutation({ mutationKey: ['identity', session.data?.id, 'sign-out'], retry: false, gcTime: 0, mutationFn: signOut,
    onMutate: async () => { await client.cancelQueries() },
    onSuccess: async () => { await client.cancelQueries(); client.clear(); await navigate({ to: '/sign-in', replace: true }) },
  })
  function close() { flushSync(() => { setClosing(true) }); logout.mutate() }
  if (closing) return <WorkspaceFrame onSignOut={close} signingOut={logout.isPending}><div className="space-y-5"><StateMessage state={logout.isError ? 'unavailable' : 'loading'} title={logout.isError ? 'Sign-out could not be confirmed' : 'Signing out'} description={logout.isError ? errorMessage(logout.error) : 'Closing your session and workspace.'} />{logout.isError && <p role="alert">Your private workspace is closed. Try signing out again to confirm the result.</p>}</div></WorkspaceFrame>
  if (session.isPending) return <WorkspaceFrame><StateMessage state="loading" title="Checking your session" description="Loading your account securely." /></WorkspaceFrame>
  if (session.isError) return <WorkspaceFrame><div className="space-y-5"><StateMessage state="unavailable" title="Session unavailable" description={errorMessage(session.error)} /><Button onClick={() => { void session.refetch() }}>Check session again</Button></div></WorkspaceFrame>
  if (!session.data) return <WorkspaceFrame><div className="space-y-5"><StateMessage state="unavailable" title="Sign in to continue" description="Your session is unavailable or has ended. Sign in again to view your workspace." /><AppLink href="/sign-in" className="inline-flex rounded-xl bg-primary px-5 py-3 font-medium text-primary-foreground">Sign in</AppLink></div></WorkspaceFrame>
  return <>{session.isFetching && <WorkspaceFrame><StateMessage state="loading" title="Checking your session" description="Confirming your current account." /></WorkspaceFrame>}<div key={session.data.id} hidden={session.isFetching} inert={session.isFetching}>{children(session.data, close)}</div></>
}
