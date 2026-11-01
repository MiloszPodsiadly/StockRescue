import type { ErrorComponentProps } from '@tanstack/react-router'
import { AppLink } from './app-link'
import { WorkspaceFrame } from './workspace-frame'
import { Button } from './ui/button'

export function MissingPage() {
  return <WorkspaceFrame><section className="space-y-5"><h1 className="text-3xl font-semibold">Page unavailable</h1><p className="text-muted-foreground">Check the address or open a workspace from your organizations.</p><AppLink href="/account" className="text-primary underline">Open your organizations</AppLink></section></WorkspaceFrame>
}
export function RouteFailure({ reset }: ErrorComponentProps) {
  return <WorkspaceFrame><section className="space-y-5"><h1 className="text-3xl font-semibold">This page could not be opened</h1><p role="alert" className="text-muted-foreground">Try again, or return to your organizations. An interrupted change may have been saved; reload its saved values before submitting it again.</p><Button onClick={reset}>Try opening the page again</Button><div><AppLink href="/account" className="text-primary underline">Open your organizations</AppLink></div></section></WorkspaceFrame>
}
