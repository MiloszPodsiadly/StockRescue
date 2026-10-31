import type { ReactNode } from 'react'
import { Building2, Leaf, Settings, Store, Truck, Users } from 'lucide-react'
import { useRouterState } from '@tanstack/react-router'
import { Button } from './ui/button'
import { AppLink } from './app-link'

export function WorkspaceFrame({ children, organizationId, administrator = false, sidebar, onSignOut, signingOut = false }: { children: ReactNode, organizationId?: string, administrator?: boolean, sidebar?: ReactNode, onSignOut?: () => void, signingOut?: boolean }) {
  const pathname = useRouterState({ select: state => state.location.pathname })
  const base = organizationId ? `/organizations/${organizationId}` : ''
  const links = organizationId ? [
    { href: `${base}/stores`, text: 'Stores', icon: Store },
    ...(administrator ? [{ href: `${base}/transport-lanes`, text: 'Transport lanes', icon: Truck }, { href: `${base}/members`, text: 'Members and access', icon: Users }] : []),
    { href: `${base}/settings`, text: 'Settings', icon: Settings },
  ] : [{ href: '/account', text: 'Account and organizations', icon: Building2 }]
  return <div className="min-h-svh bg-background text-foreground">
    <a href="#workspace-content" className="sr-only z-20 rounded-md bg-primary p-3 text-primary-foreground focus:not-sr-only focus:absolute focus:left-4 focus:top-4">Skip to content</a>
    <header className="border-b border-border bg-card"><div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-4 px-5 py-5 sm:px-8"><AppLink href="/account" aria-label="StockRescue workspace" className="flex items-center gap-3 text-xl font-semibold tracking-tight"><span className="flex size-10 items-center justify-center rounded-xl bg-primary text-primary-foreground"><Leaf aria-hidden="true" className="size-6" /></span>StockRescue.</AppLink><div className="flex flex-wrap items-center gap-4 text-sm"><AppLink href="/account" className="text-primary underline underline-offset-4">Your organizations</AppLink>{onSignOut && <Button variant="outline" onClick={onSignOut} disabled={signingOut}>{signingOut ? 'Signing out…' : 'Sign out'}</Button>}</div></div></header>
    <div className="mx-auto grid max-w-7xl gap-8 px-5 py-7 sm:px-8 lg:grid-cols-[14rem_minmax(0,1fr)] lg:gap-10 lg:py-10">
      <aside className="min-w-0 space-y-6"><div className="space-y-5 rounded-2xl border border-border bg-card p-4">{sidebar}<nav aria-label="Workspace navigation" className="flex flex-wrap gap-2 lg:flex-col">{links.map(({ href, text, icon: Icon }) => {
        const selected = pathname === href || pathname.startsWith(`${href}/`)
        return <AppLink key={href} href={href} aria-current={selected ? 'page' : undefined} className={`inline-flex min-h-11 items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium ${selected ? 'bg-primary text-primary-foreground' : 'text-foreground hover:bg-accent'}`}><Icon aria-hidden="true" className="size-4 shrink-0" />{text}</AppLink>
      })}</nav></div><AppLink href="/" className="inline-flex text-sm text-muted-foreground underline underline-offset-4">Service status</AppLink></aside>
      <main id="workspace-content" tabIndex={-1} className="min-w-0 outline-none">{children}</main>
    </div>
  </div>
}
