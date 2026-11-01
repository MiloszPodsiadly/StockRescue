import { WorkspaceFrame } from '@/components/workspace-frame'
import { OrganizationWorkspace } from '../organizations/organization-workspace'
import { Workspace } from './workspace'

export function AccountPage() {
  return <Workspace>{(account, onSignOut) => <WorkspaceFrame onSignOut={onSignOut}>
    <section aria-labelledby="account-heading"><p className="text-xs font-semibold uppercase tracking-[0.18em] text-primary">Your account</p><h1 id="account-heading" className="mt-4 text-4xl font-semibold tracking-tight">You're signed in.</h1><p className="mt-4 text-muted-foreground">Choose your team below, or create your first organization.</p><div className="mt-7 rounded-2xl border border-border bg-card p-5 sm:p-7"><dl className="grid gap-5 sm:grid-cols-2"><div><dt className="text-sm text-muted-foreground">Email address</dt><dd className="mt-1 break-all font-medium">{account.email}</dd></div><div><dt className="text-sm text-muted-foreground">Member since</dt><dd className="mt-1">{new Intl.DateTimeFormat(undefined, { dateStyle: 'long' }).format(new Date(account.createdAt))}</dd></div><div className="sm:col-span-2"><dt className="text-sm text-muted-foreground">Your account reference</dt><dd className="mt-1 break-all font-mono text-sm" data-account-reference>{account.id}</dd></div></dl></div><OrganizationWorkspace key={account.id} actorId={account.id} /></section>
  </WorkspaceFrame>}</Workspace>
}
