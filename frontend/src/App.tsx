import { useQuery } from '@tanstack/react-query'
import { ArrowUpRight, Check, Leaf, RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { StateMessage } from '@/components/state-message'
import { checkBackendReadiness } from '@/lib/health'

export function App() {
  const connection = useQuery({
    queryKey: ['backend-readiness'],
    queryFn: checkBackendReadiness,
    staleTime: 10_000,
    refetchInterval: 30_000,
    retry: false,
  })

  return (
    <div className="min-h-svh bg-background text-foreground">
      <a href="#workspace" className="sr-only z-10 rounded-md bg-primary p-3 text-primary-foreground focus:not-sr-only focus:absolute focus:left-4 focus:top-4">
        Skip to content
      </a>
      <header className="border-b border-border bg-card/80">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-5 py-5 sm:px-8">
          <a href="/" aria-label="StockRescue home" className="flex min-w-0 items-center gap-3">
            <span className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-primary text-primary-foreground">
              <Leaf aria-hidden="true" className="size-6" />
            </span>
            <span className="text-xl font-semibold tracking-tight">StockRescue<span className="text-primary">.</span></span>
          </a>
          <nav aria-label="Account navigation" className="flex flex-wrap items-center gap-4"><a href="/account" className="text-sm font-medium text-primary hover:underline">Open workspace</a><a href="/sign-in" className="text-sm font-medium text-primary hover:underline">Sign in</a></nav>
        </div>
      </header>

      <main id="workspace" tabIndex={-1} className="mx-auto max-w-6xl px-5 py-12 outline-none sm:px-8 sm:py-20">
        <div className="mb-8 flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.18em] text-primary">
          <span className="size-1.5 rounded-full bg-primary" /> Your team workspace
        </div>
        <div className="grid gap-8 lg:grid-cols-[minmax(0,1.3fr)_minmax(18rem,0.8fr)] lg:gap-16">
          <section aria-labelledby="welcome-title" className="min-w-0">
            <h1 id="welcome-title" className="max-w-xl text-4xl leading-[1.12] font-semibold tracking-[-0.045em] sm:text-6xl">
              Your team.<br /><span className="text-primary">One workspace.</span>
            </h1>
            <p className="mt-6 max-w-lg text-base leading-relaxed text-muted-foreground sm:text-lg">
              Set up your stores, give colleagues the access they need and keep transport assumptions clear.
            </p>
            <div className="mt-10 rounded-2xl border border-border bg-card p-6 sm:p-8">
              <h2 className="text-xl font-semibold">Start with your organization</h2><p className="mt-3 text-sm leading-relaxed text-muted-foreground">Create your account, configure stores and storage zones, invite your team and review transport costs with their history.</p><a href="/sign-up" className="mt-5 inline-flex rounded-xl bg-primary px-5 py-3 font-medium text-primary-foreground">Create an account</a><p className="mt-5 text-sm text-muted-foreground">Inventory onboarding and stock operations are not available yet. No retailer results or forecasts are displayed.</p>
            </div>
          </section>

          <aside aria-labelledby="connection-title" className="min-w-0 self-start rounded-2xl border border-border bg-card p-6 shadow-sm sm:p-8 lg:mt-2">
            <div className="mb-8 flex items-start justify-between gap-3">
              <div>
                <p className="text-xs font-medium uppercase tracking-widest text-muted-foreground">Workspace connection</p>
                <h2 id="connection-title" className="mt-2 text-2xl font-semibold tracking-tight">A live check.</h2>
              </div>
              <ArrowUpRight aria-hidden="true" className="size-5 text-muted-foreground" />
            </div>
            <div aria-live="polite" className="min-h-36">
              {connection.isPending ? (
                <StateMessage state="loading" title="Checking connection" description="Contacting the backend to verify availability." />
              ) : connection.isError ? (
                <StateMessage state="unavailable" title="Backend unavailable" description="We could not verify the connection. Check the local services and try again." />
              ) : (
                <div role="status">
                  <span className="mb-4 flex size-10 items-center justify-center rounded-full bg-accent text-primary"><Check aria-hidden="true" className="size-5" /></span>
                  <h3 className="text-lg font-semibold">Backend available</h3>
                  <p className="mt-2 text-sm leading-relaxed text-muted-foreground">The backend reports that it is ready. Inventory workflows are still being built.</p>
                </div>
              )}
            </div>
            <Button variant="outline" className="mt-6 w-full" disabled={connection.isFetching}
              onClick={() => { void connection.refetch() }}>
              <RefreshCw aria-hidden="true" className="size-4" />
              {connection.isFetching ? 'Checking…' : 'Check again'}
            </Button>
            <p className="mt-4 text-center text-xs leading-relaxed text-muted-foreground">Service availability is checked every 30 seconds.</p>
          </aside>
        </div>
        <footer className="mt-16 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-6 text-xs text-muted-foreground">
          <span>StockRescue AI</span><span>Thoughtful stock. Smaller footprint.</span>
        </footer>
      </main>
    </div>
  )
}
