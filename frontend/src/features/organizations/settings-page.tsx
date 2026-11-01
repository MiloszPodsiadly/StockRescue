import { useParams } from '@tanstack/react-router'
import { AppLink } from '@/components/app-link'
import { OrganizationPage } from './organization-page'
import { useOrganization } from './organization-context'

export function SettingsPage() {
  const { organizationId } = useParams({ strict: false })
  return <OrganizationPage organizationId={organizationId}><Settings /></OrganizationPage>
}
function Settings() {
  const organization = useOrganization()
  const base = `/organizations/${organization.id}`
  const links = [
    { href: `${base}/stores`, title: 'Stores and storage zones', description: 'Open a store to review its timezone, address and declared storage compatibility.' },
    ...(organization.role === 'ORG_ADMIN' ? [
      { href: `${base}/transport-lanes`, title: 'Transport lanes and costs', description: 'Configure directed routes and approve costs while preserving earlier revisions.' },
      { href: `${base}/members`, title: 'Team access', description: 'Invite colleagues, review their identity and manage store assignments.' },
    ] : []),
    { href: '/account', title: 'Your account', description: 'Review your account reference and switch between your organizations.' },
  ]
  return <section className="space-y-7"><div><h2 className="text-2xl font-semibold">Settings</h2><p className="mt-3 text-muted-foreground">Configuration and access for your current workspace.</p></div><dl className="grid gap-5 rounded-2xl border border-border bg-card p-5 sm:grid-cols-2 sm:p-7"><div><dt className="text-sm text-muted-foreground">Organization</dt><dd className="mt-2 break-words font-medium">{organization.name}</dd></div><div><dt className="text-sm text-muted-foreground">Your organization role</dt><dd className="mt-2 font-medium">{organization.role === 'ORG_ADMIN' ? 'Org Admin' : 'Member'}</dd></div><div className="sm:col-span-2"><dt className="text-sm text-muted-foreground">Organization reference</dt><dd className="mt-2 break-all font-mono text-sm">{organization.id}</dd></div></dl><div className="grid gap-4 sm:grid-cols-2">{links.map(link => <article key={link.href} className="rounded-2xl border border-border bg-card p-5 sm:p-7"><h3 className="text-lg font-semibold"><AppLink href={link.href} className="text-primary underline underline-offset-4">{link.title}</AppLink></h3><p className="mt-3 text-sm leading-relaxed text-muted-foreground">{link.description}</p></article>)}</div></section>
}
