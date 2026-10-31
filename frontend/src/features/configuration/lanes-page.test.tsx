import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { Lane, Revision } from '@/api/configuration'
import { LanePage } from './lanes-page'

const api = vi.hoisted(() => ({
  getLane: vi.fn(), laneHistory: vi.fn(), supportedCurrencies: vi.fn(),
  cancelScheduledLaneRevisions: vi.fn(), createLane: vi.fn(), updateLane: vi.fn(), reviseLane: vi.fn(),
  listLanes: vi.fn(),
}))

vi.mock('@/api/configuration', () => api)
vi.mock('@tanstack/react-router', () => ({ useParams: () => ({ organizationId: 'org', laneId: 'lane' }) }))
vi.mock('@/components/app-link', () => ({ AppLink: ({ href, children, ...props }: React.ComponentProps<'a'> & { href: string }) => <a href={href} {...props}>{children}</a> }))
vi.mock('../organizations/organization-page', () => ({ OrganizationPage: ({ children }: React.PropsWithChildren) => <>{children}</> }))
vi.mock('../organizations/tenant-requests', () => ({
  useTenantKey: () => ['tenant', 'actor', 'org'],
  useTenantRequests: () => () => new AbortController().signal,
}))
vi.mock('./lane-forms', () => ({
  LaneSetupForm: ({ lane, saving, onSubmit }: { lane?: Lane, saving: boolean, onSubmit: (body: unknown) => void }) => lane
    ? <button disabled={saving} onClick={() => onSubmit({
      active: true, originZoneId: 'north-chilled', destinationZoneId: 'south-chilled', version: lane.version,
      reason: 'Change to compatible chilled zones',
    })}>Save lane configuration</button>
    : null,
  CostRevisionForm: () => null,
}))

afterEach(cleanup)

function lane(version: number, active = true): Lane {
  return {
    id: 'lane', originStoreId: 'north', originStoreName: 'North', destinationStoreId: 'south', destinationStoreName: 'South',
    originZoneId: 'north-ambient', destinationZoneId: 'south-ambient', active, version, latestRevision: 3,
    latestEffectiveAt: '2026-10-01T00:00:00Z', status: 'UNCONFIGURED', missing: ['NO_EFFECTIVE_COST'], currentCost: null,
    asOf: '2026-10-31T12:00:00Z',
  }
}

const scheduledRevision: Revision = {
  id: 'revision-3', revision: 3, effectiveAt: '2030-01-01T00:00:00Z', validUntil: null,
  recordedAt: '2026-10-01T00:00:00Z', recordedByMembershipId: 'member-a', costAmount: '99.9900',
  currencyCode: 'EUR', durationMinutes: 60, originZoneId: 'north-ambient', destinationZoneId: 'south-ambient',
  temperatureClass: 'AMBIENT', cancellation: null,
}

function history(revision: Revision | null) {
  return { items: revision ? [revision] : [], offset: 0, limit: 25, hasMore: false }
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } })
  render(<QueryClientProvider client={client}><LanePage /></QueryClientProvider>)
  return { client, user: userEvent.setup() }
}

beforeEach(() => {
  vi.resetAllMocks()
  api.getLane.mockResolvedValue(lane(3))
  api.laneHistory.mockResolvedValue(history(scheduledRevision))
  api.listLanes.mockResolvedValue({ items: [], offset: 0, limit: 25, hasMore: false })
  api.supportedCurrencies.mockResolvedValue({ codes: ['EUR', 'PLN'] })
  api.cancelScheduledLaneRevisions.mockResolvedValue({
    lane: lane(4),
    cancelledRevisions: [{ id: 'cancellation-3', revision: 3, version: 4, cancelledAt: '2026-10-31T12:05:00Z', role: 'ORG_ADMIN', cancelledByMembershipId: 'member-a', reason: 'Carrier agreement changed before the planned start' }],
  })
})

it('requires review instead of rebasing a zone edit over a concurrent lane version change', async () => {
  api.getLane.mockResolvedValueOnce(lane(3, true)).mockResolvedValueOnce(lane(4, false))
  api.laneHistory.mockResolvedValue(history(null))
  const { user } = renderPage()
  await screen.findByRole('button', { name: 'Save lane configuration' })

  await user.click(screen.getByRole('button', { name: 'Save lane configuration' }))

  expect(await screen.findByRole('status')).toHaveTextContent('The lane configuration changed elsewhere. Review the reloaded configuration before editing again.')
  expect(api.updateLane).not.toHaveBeenCalled()
})

it('keeps reconciled cancellation data when an older lane and history read finishes late', async () => {
  const staleLane = Promise.withResolvers<Lane>()
  const staleHistory = Promise.withResolvers<ReturnType<typeof history>>()
  let laneReads = 0
  let historyReads = 0
  let staleLaneSignal: AbortSignal | undefined
  let staleHistorySignal: AbortSignal | undefined
  api.getLane.mockImplementation((_organizationId: string, _laneId: string, signal: AbortSignal) => {
    laneReads += 1
    if (laneReads === 3) { staleLaneSignal = signal; return staleLane.promise }
    return Promise.resolve(lane(laneReads >= 4 ? 4 : 3))
  })
  api.laneHistory.mockImplementation((_organizationId: string, _laneId: string, _offset: number, signal: AbortSignal) => {
    historyReads += 1
    if (historyReads === 4) { staleHistorySignal = signal; return staleHistory.promise }
    return Promise.resolve(historyReads >= 5
      ? history({ ...scheduledRevision, cancellation: {
        id: 'cancellation-3', revision: 3, revisionId: scheduledRevision.id, cancelledAt: '2026-10-31T12:05:00Z', cancelledByMembershipId: 'member-a', reason: 'Carrier agreement changed before the planned start',
      } })
      : history(scheduledRevision))
  })
  const { client, user } = renderPage()
  await screen.findByRole('button', { name: 'Save lane configuration' })
  await user.click(screen.getByRole('button', { name: 'Save lane configuration' }))
  await screen.findByRole('heading', { name: 'Review future costs before changing lane zones' })

  const root = ['tenant', 'actor', 'org', 'lane', 'lane']
  api.cancelScheduledLaneRevisions.mockImplementationOnce(async () => {
    void client.fetchQuery({
      queryKey: [...root, 'detail'],
      queryFn: ({ signal }) => api.getLane('org', 'lane', signal),
      staleTime: 0,
    }).catch(() => undefined)
    void client.fetchQuery({
      queryKey: [...root, 'history', 0],
      queryFn: ({ signal }) => api.laneHistory('org', 'lane', 0, signal),
      staleTime: 0,
    }).catch(() => undefined)
    await waitFor(() => {
      expect(staleLaneSignal).toBeDefined()
      expect(staleHistorySignal).toBeDefined()
    })
    return {
      lane: lane(4),
      cancelledRevisions: [{ id: 'cancellation-3', revision: 3, version: 4, cancelledAt: '2026-10-31T12:05:00Z', role: 'ORG_ADMIN', cancelledByMembershipId: 'member-a', reason: 'Carrier agreement changed before the planned start' }],
    }
  })
  await user.type(screen.getByLabelText('Reason for cancelling scheduled costs'), 'Carrier agreement changed before the planned start')
  await user.click(screen.getByLabelText('I reviewed the listed revisions and confirm cancelling all of them.'))
  await user.click(screen.getByRole('button', { name: 'Cancel all 1 scheduled revision' }))
  await screen.findByText(/Cancelled 1 scheduled revision/)
  expect(staleLaneSignal?.aborted).toBe(true)
  expect(staleHistorySignal?.aborted).toBe(true)

  await act(async () => {
    staleLane.resolve(lane(3))
    staleHistory.resolve(history(scheduledRevision))
    await Promise.resolve()
  })

  expect(client.getQueryData<Lane>([...root, 'detail'])?.version).toBe(4)
  expect(client.getQueryData<ReturnType<typeof history>>([...root, 'history', 0])?.items[0]?.cancellation?.id).toBe('cancellation-3')
})
