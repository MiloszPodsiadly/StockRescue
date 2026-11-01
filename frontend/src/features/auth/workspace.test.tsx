import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { useQuery, QueryClient, QueryClientProvider, useQueryClient } from '@tanstack/react-query'
import { createRootRoute, createRoute, createRouter, createMemoryHistory, RouterProvider } from '@tanstack/react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { getStore } from '@/api/stores'
import type { Store } from '@/api/stores'
import { clearCsrf } from '@/api/client'
import { RootLayout } from '@/root-layout'
import { WorkspaceFrame } from '@/components/workspace-frame'
import { TenantBoundary } from '../organizations/tenant-boundary'
import { useTenantKey } from '../organizations/tenant-requests'
import { Workspace } from './workspace'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const account = (id: string) => ({ id, email: `${id}@example.test`, createdAt: '2026-10-30T00:00:00Z' })
function store(name: string): Store { return { id: 'store', code: 'NORTH', name, zoneId: 'UTC', active: true, version: 0, addressLine1: null, city: null, postalCode: null, countryCode: null, allowedActions: ['READ_STORE'], createdAt: '2026-10-30T00:00:00Z', updatedAt: '2026-10-30T00:00:00Z' } }
beforeEach(() => { vi.spyOn(window, 'scrollTo').mockImplementation(() => {}) })
afterEach(() => { cleanup(); clearCsrf(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

function workspace(clients: QueryClient[]) {
  function Probe() {
    const client = useQueryClient()
    if (!clients.includes(client)) clients.push(client)
    const scope = useTenantKey()
    const saved = useQuery({ queryKey: [...scope, 'store', 'store'], queryFn: ({ signal }) => getStore('org', 'store', signal) })
    return <p>{saved.data?.name ?? 'Loading private store'}</p>
  }
  function Page() { return <Workspace>{(actor, close) => <WorkspaceFrame onSignOut={close}><p>{actor.email}</p><TenantBoundary key={actor.id} actorId={actor.id} organizationId="org"><Probe /></TenantBoundary></WorkspaceFrame>}</Workspace> }
  const root = createRootRoute({ component: RootLayout })
  const index = createRoute({ getParentRoute: () => root, path: '/', component: Page })
  const router = createRouter({ routeTree: root.addChildren([index]), history: createMemoryHistory({ initialEntries: ['/'] }) })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>)
  return client
}

it('closes the complete private workspace when a protected request reports an expired session', async () => {
  const pending = Promise.withResolvers<Response>()
  let signal: AbortSignal | undefined
  const clients: QueryClient[] = []
  vi.stubGlobal('fetch', vi.fn().mockImplementation((request: Request) => {
    if (new URL(request.url).pathname === '/api/v1/auth/me') return Promise.resolve(json(account('alice')))
    signal = request.signal
    return pending.promise
  }))
  workspace(clients)
  await screen.findByText('alice@example.test')
  await waitFor(() => { expect(signal).toBeDefined() })
  expect(clients[0]?.getQueryCache().getAll()[0]?.queryKey).toEqual(['tenant', 'alice', 'org', 'store', 'store'])
  await act(async () => { pending.resolve(json({ code: 'SESSION_EXPIRED' }, 401)) })
  await screen.findByRole('heading', { name: 'Sign in to continue' })
  expect(screen.queryByText('alice@example.test')).not.toBeInTheDocument()
  expect(clients[0]?.getQueryCache().getAll()).toHaveLength(0)
})

it('revalidates a session change from another tab and never accepts the previous actors late private response', async () => {
  const channels: { onmessage: ((event: MessageEvent) => void) | null }[] = []
  vi.stubGlobal('BroadcastChannel', class {
    onmessage: ((event: MessageEvent) => void) | null = null
    constructor() { channels.push(this) }
    postMessage() {}
    close() {}
  })
  const pending = Promise.withResolvers<Response>()
  const clients: QueryClient[] = []
  let actor = 'alice'
  let oldSignal: AbortSignal | undefined
  vi.stubGlobal('fetch', vi.fn().mockImplementation((request: Request) => {
    if (new URL(request.url).pathname === '/api/v1/auth/me') return Promise.resolve(json(account(actor)))
    if (actor === 'alice') { oldSignal = request.signal; return pending.promise }
    return Promise.resolve(json(store('Bobs authorized store')))
  }))
  workspace(clients)
  await screen.findByText('alice@example.test')
  await waitFor(() => { expect(oldSignal).toBeDefined() })
  actor = 'bob'
  await act(async () => { channels.at(-1)?.onmessage?.(new MessageEvent('message', { data: 'changed' })) })
  await screen.findByText('bob@example.test')
  await screen.findByText('Bobs authorized store')
  expect(oldSignal?.aborted).toBe(true)
  await act(async () => { pending.resolve(json(store('Alices private late store'))) })
  expect(screen.queryByText('alice@example.test')).not.toBeInTheDocument()
  expect(screen.queryByText('Alices private late store')).not.toBeInTheDocument()
  expect(clients[0]?.getQueryCache().getAll()).toHaveLength(0)
  expect(clients.at(-1)?.getQueryCache().getAll()[0]?.queryKey).toEqual(['tenant', 'bob', 'org', 'store', 'store'])
})
