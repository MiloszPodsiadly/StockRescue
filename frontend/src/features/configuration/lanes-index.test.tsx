import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { Lane } from '@/api/configuration'
import { LanesPage } from './lanes-page'

const api = vi.hoisted(() => ({
  listLanes: vi.fn(), getLane: vi.fn(), lookupLane: vi.fn(), createLane: vi.fn(),
  laneHistory: vi.fn(), supportedCurrencies: vi.fn(), updateLane: vi.fn(), reviseLane: vi.fn(),
}))
const route = vi.hoisted(() => ({ organizationId: 'org-a' }))
const tenant = vi.hoisted(() => ({ signal: undefined as AbortSignal | undefined }))

vi.mock('@/api/configuration', () => api)
vi.mock('@tanstack/react-router', () => ({ useParams: () => ({ organizationId: route.organizationId }) }))
vi.mock('@/components/app-link', () => ({ AppLink: ({ href, children, ...props }: React.ComponentProps<'a'> & { href: string }) => <a href={href} {...props}>{children}</a> }))
vi.mock('../organizations/organization-page', () => ({ OrganizationPage: ({ children }: React.PropsWithChildren) => <>{children}</> }))
vi.mock('../organizations/tenant-requests', () => ({
  useTenantKey: () => ['tenant', route.organizationId],
  useTenantRequests: () => () => tenant.signal ?? new AbortController().signal,
}))
vi.mock('./lane-forms', () => ({
  LaneSetupForm: ({ saving, onSubmit }: { saving: boolean, onSubmit: (body: unknown) => void }) => <form aria-label="Create lane form" onSubmit={event => {
    event.preventDefault()
    const values = new FormData(event.currentTarget)
    onSubmit({
      originStoreId: String(values.get('originStoreId')),
      destinationStoreId: String(values.get('destinationStoreId')),
      originZoneId: 'zone-a', destinationZoneId: 'zone-b', reason: 'Create route between stores',
    })
  }}>
    <label>Origin store<select name="originStoreId" defaultValue="store-a"><option value="store-a">A</option><option value="store-b">B</option></select></label>
    <label>Destination store<select name="destinationStoreId" defaultValue="store-b"><option value="store-a">A</option><option value="store-b">B</option></select></label>
    <button type="submit" disabled={saving}>{saving ? 'Saving lane…' : 'Create lane'}</button>
  </form>,
}))

afterEach(cleanup)

function lane(id: string, originZoneId = 'zone-a', destinationZoneId = 'zone-b'): Lane {
  return {
    id, originStoreId: 'store-a', originStoreName: 'North', destinationStoreId: 'store-b', destinationStoreName: 'South',
    originZoneId, destinationZoneId, active: true, version: 1, latestRevision: 0, latestEffectiveAt: null,
    status: 'UNCONFIGURED', missing: ['NO_EFFECTIVE_COST'], currentCost: null, asOf: '2026-10-31T12:00:00Z',
  }
}

function listPage(items: Lane[] = []) {
  return { items, offset: 0, limit: 25, hasMore: false, asOf: '2026-10-31T12:00:00Z' }
}

function lookupResult(found: Lane | null) {
  return { lane: found, asOf: '2026-10-31T12:00:00Z', missing: found?.missing ?? ['NO_LANE'], status: found?.status ?? 'UNCONFIGURED' }
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } })
  const view = render(<QueryClientProvider client={client}><LanesPage /></QueryClientProvider>)
  return { client, user: userEvent.setup(), view }
}

beforeEach(() => {
  vi.resetAllMocks()
  route.organizationId = 'org-a'
  tenant.signal = new AbortController().signal
  api.listLanes.mockResolvedValue(listPage())
  api.createLane.mockResolvedValue(lane('route-ab'))
  api.lookupLane.mockResolvedValue(lookupResult(null))
})

it('finds an existing A-to-B lane after a lost create response without posting again', async () => {
  const saved = lane('route-ab')
  api.createLane.mockRejectedValueOnce(new TypeError('Connection lost after the server saved the lane'))
  api.lookupLane.mockResolvedValueOnce(lookupResult(saved))
  const { user } = renderPage()

  await user.click(await screen.findByRole('button', { name: 'Create lane' }))

  expect(await screen.findByText(/An existing lane was found after the creation result became uncertain\./)).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Open existing lane' })).toHaveAttribute('href', '/organizations/org-a/transport-lanes/route-ab')
  expect(screen.queryByText('Transport lane saved.')).not.toBeInTheDocument()
  expect(api.createLane).toHaveBeenCalledWith('org-a', {
    originStoreId: 'store-a', destinationStoreId: 'store-b', originZoneId: 'zone-a', destinationZoneId: 'zone-b', reason: 'Create route between stores',
  }, expect.any(AbortSignal))
  expect(api.lookupLane).toHaveBeenCalledWith('org-a', 'store-a', 'store-b', expect.any(AbortSignal))
  expect(api.createLane).toHaveBeenCalledTimes(1)
  await waitFor(() => expect(api.listLanes).toHaveBeenCalledTimes(2))
})

it('requires manual review when a recovered lane has different zone evidence', async () => {
  api.createLane.mockRejectedValueOnce(new TypeError('Connection lost after the server saved the lane'))
  api.lookupLane.mockResolvedValueOnce(lookupResult(lane('route-ab', 'other-origin-zone', 'other-destination-zone')))
  const { user } = renderPage()

  await user.click(await screen.findByRole('button', { name: 'Create lane' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('The saved origin or destination zone differs from the requested configuration.')
  expect(screen.getByRole('link', { name: 'Open existing lane' })).toHaveAttribute('href', '/organizations/org-a/transport-lanes/route-ab')
  expect(screen.queryByText('Transport lane saved.')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Create lane' })).not.toBeInTheDocument()
  expect(api.createLane).toHaveBeenCalledTimes(1)
})

it('keeps a missing or unavailable lookup unconfirmed and offers read-only rechecks', async () => {
  api.createLane.mockRejectedValueOnce(new TypeError('Connection lost before a response arrived'))
  api.lookupLane.mockRejectedValueOnce(new TypeError('Lane lookup unavailable')).mockResolvedValueOnce(lookupResult(null))
  const { user } = renderPage()

  await user.click(await screen.findByRole('button', { name: 'Create lane' }))

  expect(await screen.findByText(/^Lane creation result is unconfirmed\./)).toBeInTheDocument()
  expect(screen.getByText(/We could not check whether the lane was saved\./)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Check for saved lane' })).toBeEnabled()
  expect(screen.queryByRole('link', { name: /Open .* lane/ })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Create lane' })).not.toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: 'Check for saved lane' }))

  expect(await screen.findByText(/No saved lane is visible yet\. The earlier request may still be completing/)).toBeInTheDocument()
  expect(api.lookupLane).toHaveBeenCalledTimes(2)
  expect(api.createLane).toHaveBeenCalledTimes(1)
  expect(api.listLanes).toHaveBeenCalledTimes(1)
})

it('does not expose a cached lane when organization access is denied during recovery', async () => {
  api.createLane.mockRejectedValueOnce(new TypeError('Connection lost after the server saved the lane'))
  api.lookupLane.mockRejectedValueOnce(new ApiError(new Response(null, { status: 403 }), { code: 'ACCESS_DENIED' } as never))
  const { user } = renderPage()

  await user.click(await screen.findByRole('button', { name: 'Create lane' }))

  expect(await screen.findByRole('alert')).toHaveTextContent('You do not have permission to perform this action.')
  expect(await screen.findByText(/^Lane creation result is unconfirmed\./)).toBeInTheDocument()
  expect(screen.getByText(/Restore access and check for the saved lane before submitting another creation request\./)).toBeInTheDocument()
  expect(screen.queryByRole('link', { name: /Open .* lane/ })).not.toBeInTheDocument()
  expect(screen.queryByRole('form', { name: 'Create lane form' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Create lane' })).not.toBeInTheDocument()
  expect(api.createLane).toHaveBeenCalledTimes(1)
  expect(api.lookupLane).toHaveBeenCalledTimes(1)
  expect(api.listLanes).toHaveBeenCalledTimes(1)
})

it('keeps the saved lane link and retries the list after its refresh fails', async () => {
  const saved = lane('route-ab')
  api.listLanes.mockResolvedValueOnce(listPage()).mockRejectedValueOnce(new TypeError('Lane list unavailable')).mockResolvedValueOnce(listPage([saved]))
  api.createLane.mockResolvedValueOnce(saved)
  const { user } = renderPage()

  await user.click(await screen.findByRole('button', { name: 'Create lane' }))

  expect(await screen.findByRole('heading', { name: 'Transport lanes unavailable' })).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Open saved lane' })).toHaveAttribute('href', '/organizations/org-a/transport-lanes/route-ab')
  await user.click(screen.getByRole('button', { name: 'Reload saved lanes' }))
  await screen.findByRole('heading', { name: 'Transport lanes' })
  expect(screen.getByRole('link', { name: 'Open saved lane' })).toHaveAttribute('href', '/organizations/org-a/transport-lanes/route-ab')
  expect(api.createLane).toHaveBeenCalledTimes(1)
  expect(api.listLanes).toHaveBeenCalledTimes(3)
})

it('allows only one request when the create form is submitted twice before rendering', async () => {
  const response = Promise.withResolvers<Lane>()
  api.createLane.mockReturnValueOnce(response.promise)
  const { view } = renderPage()
  const form = await screen.findByRole('form', { name: 'Create lane form' })

  act(() => {
    fireEvent.submit(form)
    fireEvent.submit(form)
  })
  await waitFor(() => expect(api.createLane).toHaveBeenCalledTimes(1))
  await act(async () => { response.resolve(lane('route-ab')); await Promise.resolve() })
  expect(await screen.findByRole('link', { name: 'Open saved lane' })).toHaveAttribute('href', '/organizations/org-a/transport-lanes/route-ab')
  view.unmount()
})

it('looks up the reverse route as B-to-A rather than confusing the ordered pair', async () => {
  api.createLane.mockRejectedValueOnce(new TypeError('Connection lost after the server saved the lane'))
  api.lookupLane.mockResolvedValueOnce(lookupResult({
    ...lane('route-ba'), originStoreId: 'store-b', originStoreName: 'South', destinationStoreId: 'store-a', destinationStoreName: 'North',
  }))
  const { user } = renderPage()
  await screen.findByRole('button', { name: 'Create lane' })
  await user.selectOptions(screen.getByLabelText('Origin store'), 'store-b')
  await user.selectOptions(screen.getByLabelText('Destination store'), 'store-a')
  await user.click(screen.getByRole('button', { name: 'Create lane' }))

  expect(await screen.findByRole('link', { name: 'Open existing lane' })).toHaveAttribute('href', '/organizations/org-a/transport-lanes/route-ba')
  expect(api.createLane).toHaveBeenCalledWith('org-a', expect.objectContaining({ originStoreId: 'store-b', destinationStoreId: 'store-a' }), expect.any(AbortSignal))
  expect(api.lookupLane).toHaveBeenCalledWith('org-a', 'store-b', 'store-a', expect.any(AbortSignal))
})

it('aborts lane recovery and discards its result when the active organization changes', async () => {
  const lookup = Promise.withResolvers<ReturnType<typeof lookupResult>>()
  let lookupSignal: AbortSignal | undefined
  api.createLane.mockRejectedValueOnce(new TypeError('Connection lost after the server saved the lane'))
  api.lookupLane.mockImplementationOnce((_org: string, _origin: string, _destination: string, signal: AbortSignal) => {
    lookupSignal = signal
    return lookup.promise
  })
  const { client, user, view } = renderPage()
  await user.click(await screen.findByRole('button', { name: 'Create lane' }))
  await waitFor(() => expect(lookupSignal).toBeDefined())

  act(() => {
    route.organizationId = 'org-b'
    tenant.signal = new AbortController().signal
    view.rerender(<QueryClientProvider client={client}><LanesPage /></QueryClientProvider>)
  })
  expect(lookupSignal?.aborted).toBe(true)
  await screen.findByRole('button', { name: 'Create lane' })
  await act(async () => { lookup.resolve(lookupResult(lane('route-ab'))); await Promise.resolve() })
  expect(screen.queryByRole('link', { name: 'Open existing lane' })).not.toBeInTheDocument()
  expect(api.listLanes).toHaveBeenCalledWith('org-b', 0, expect.any(AbortSignal))
})
