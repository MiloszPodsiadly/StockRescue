import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { Lane, Revision } from '@/api/configuration'
import { LanePage } from './lanes-page'

const api = vi.hoisted(() => ({
  getLane: vi.fn(), laneHistory: vi.fn(), supportedCurrencies: vi.fn(),
  cancelScheduledLaneRevisions: vi.fn(), createLane: vi.fn(), updateLane: vi.fn(), reviseLane: vi.fn(),
  listLanes: vi.fn(),
}))
const tenant = vi.hoisted(() => ({ signal: undefined as AbortSignal | undefined }))

vi.mock('@/api/configuration', () => api)
vi.mock('@tanstack/react-router', () => ({ useParams: () => ({ organizationId: 'org', laneId: 'lane' }) }))
vi.mock('@/components/app-link', () => ({ AppLink: ({ href, children, ...props }: React.ComponentProps<'a'> & { href: string }) => <a href={href} {...props}>{children}</a> }))
vi.mock('../organizations/organization-page', () => ({ OrganizationPage: ({ children }: React.PropsWithChildren) => <>{children}</> }))
vi.mock('../organizations/tenant-requests', () => ({
  useTenantKey: () => ['tenant', 'actor', 'org'],
  useTenantRequests: () => () => tenant.signal ?? new AbortController().signal,
}))
vi.mock('./lane-forms', () => ({
  LaneSetupForm: ({ lane, draft, saving, onSubmit }: { lane?: Lane, draft?: { originZoneId?: string }, saving: boolean, onSubmit: (body: unknown) => void }) => lane
    ? <><span data-testid="prepared-zone-draft">{draft?.originZoneId ?? 'no prepared draft'}</span><button disabled={saving} onClick={() => onSubmit({
      active: true, originZoneId: 'north-chilled', destinationZoneId: 'south-chilled', version: lane.version,
      reason: 'Change to compatible chilled zones',
    })}>Save lane configuration</button></>
    : null,
  CostRevisionForm: ({ lane, saving, onSubmit }: { lane: Lane, saving: boolean, onSubmit: (body: unknown) => void }) => <button disabled={saving} onClick={() => onSubmit({
    laneVersion: lane.version, expectedRevision: lane.latestRevision, costAmount: '11.25', currencyCode: 'EUR', durationMinutes: 45, reason: 'Approved current carrier quote',
  })}>Approve current cost for test</button>,
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

function history(revisions: Revision | Revision[] | null) {
  const items = Array.isArray(revisions) ? revisions : revisions ? [revisions] : []
  return { items, offset: 0, limit: 25, hasMore: false }
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } })
  const view = render(<QueryClientProvider client={client}><LanePage /></QueryClientProvider>)
  return { client, user: userEvent.setup(), view }
}

beforeEach(() => {
  vi.resetAllMocks()
  tenant.signal = new AbortController().signal
  api.getLane.mockResolvedValue(lane(3))
  api.laneHistory.mockResolvedValue(history(scheduledRevision))
  api.listLanes.mockResolvedValue({ items: [], offset: 0, limit: 25, hasMore: false })
  api.supportedCurrencies.mockResolvedValue({ codes: ['EUR', 'PLN'] })
  api.cancelScheduledLaneRevisions.mockResolvedValue({
    lane: lane(4),
    cancelledRevisions: [{ id: 'cancellation-3', revisionId: scheduledRevision.id, revision: 3, version: 4, cancelledAt: '2026-10-31T12:05:00Z', role: 'ORG_ADMIN', cancelledByMembershipId: 'member-a', reason: 'Carrier agreement changed before the planned start' }],
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
      cancelledRevisions: [{ id: 'cancellation-3', revisionId: scheduledRevision.id, revision: 3, version: 4, cancelledAt: '2026-10-31T12:05:00Z', role: 'ORG_ADMIN', cancelledByMembershipId: 'member-a', reason: 'Carrier agreement changed before the planned start' }],
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

it('cancels scheduled costs independently, leaves zones alone and allows a fresh current cost', async () => {
  let savedCancellation = false
  api.getLane.mockImplementation(async () => lane(savedCancellation ? 4 : 3))
  api.laneHistory.mockImplementation(async () => history(savedCancellation
    ? { ...scheduledRevision, cancellation: {
      id: 'cancellation-3', revision: 3, revisionId: scheduledRevision.id, cancelledAt: '2026-10-31T12:05:00Z', cancelledByMembershipId: 'member-a', reason: 'Carrier agreement changed before the planned start',
    } }
    : scheduledRevision))
  api.cancelScheduledLaneRevisions.mockImplementation(async () => {
    savedCancellation = true
    return {
      lane: lane(4),
      cancelledRevisions: [{ id: 'cancellation-3', revisionId: scheduledRevision.id, revision: 3, version: 4, cancelledAt: '2026-10-31T12:05:00Z', role: 'ORG_ADMIN', cancelledByMembershipId: 'member-a', reason: 'Carrier agreement changed before the planned start' }],
    }
  })
  const { user } = renderPage()

  await screen.findByRole('button', { name: 'Review and cancel scheduled costs' })
  await user.click(screen.getByRole('button', { name: 'Review and cancel scheduled costs' }))
  const review = await screen.findByRole('region', { name: 'Review and cancel scheduled costs' })
  expect(review).toHaveTextContent('The lane configuration and storage zones will not change.')
  expect(review).toHaveTextContent('99.9900 EUR · 60 minutes')
  expect(api.updateLane).not.toHaveBeenCalled()
  expect(screen.getByRole('button', { name: 'Cancel all 1 scheduled revision' })).toBeDisabled()

  await user.click(screen.getByRole('button', { name: 'Close review without cancelling' }))
  expect(api.cancelScheduledLaneRevisions).not.toHaveBeenCalled()

  await user.click(screen.getByRole('button', { name: 'Review and cancel scheduled costs' }))
  await user.type(screen.getByLabelText('Reason for cancelling scheduled costs'), 'Carrier agreement changed before the planned start')
  await user.click(screen.getByLabelText('I reviewed the listed revisions and confirm cancelling all of them.'))
  await user.click(screen.getByRole('button', { name: 'Cancel all 1 scheduled revision' }))
  expect(await screen.findByRole('status')).toHaveTextContent('Saved history confirms 1 reviewed scheduled cost cancelled.')
  expect(api.cancelScheduledLaneRevisions).toHaveBeenCalledWith('org', 'lane', {
    laneVersion: 3, expectedRevision: 3, reason: 'Carrier agreement changed before the planned start',
  }, expect.any(AbortSignal))
  expect(api.updateLane).not.toHaveBeenCalled()

  await user.click(screen.getByRole('button', { name: 'Approve current cost for test' }))
  await waitFor(() => expect(api.reviseLane).toHaveBeenCalledWith('org', 'lane', expect.objectContaining({ laneVersion: 4, expectedRevision: 3 }), expect.any(AbortSignal)))
  expect(api.reviseLane.mock.calls[0]?.[2]).not.toHaveProperty('effectiveAt')
})

it('keeps a zone-change draft prepared after cancellation and waits for a separate save', async () => {
  let savedCancellation = false
  api.getLane.mockImplementation(async () => lane(savedCancellation ? 4 : 3))
  api.laneHistory.mockImplementation(async () => history(savedCancellation
    ? { ...scheduledRevision, cancellation: {
      id: 'cancellation-3', revision: 3, revisionId: scheduledRevision.id, cancelledAt: '2026-10-31T12:05:00Z', cancelledByMembershipId: 'member-a', reason: 'Carrier agreement changed before the planned start',
    } }
    : scheduledRevision))
  api.cancelScheduledLaneRevisions.mockImplementation(async () => {
    savedCancellation = true
    return {
      lane: lane(4),
      cancelledRevisions: [{ id: 'cancellation-3', revisionId: scheduledRevision.id, revision: 3, version: 4, cancelledAt: '2026-10-31T12:05:00Z', role: 'ORG_ADMIN', cancelledByMembershipId: 'member-a', reason: 'Carrier agreement changed before the planned start' }],
    }
  })
  const { user } = renderPage()
  await user.click(await screen.findByRole('button', { name: 'Save lane configuration' }))
  await screen.findByRole('region', { name: 'Review future costs before changing lane zones' })
  await user.type(screen.getByLabelText('Reason for cancelling scheduled costs'), 'Carrier agreement changed before the planned start')
  await user.click(screen.getByLabelText('I reviewed the listed revisions and confirm cancelling all of them.'))
  await user.click(screen.getByRole('button', { name: 'Cancel all 1 scheduled revision' }))

  expect(await screen.findByRole('status')).toHaveTextContent('review the selected zones and save again')
  expect(screen.getByTestId('prepared-zone-draft')).toHaveTextContent('north-chilled')
  expect(api.updateLane).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: 'Save lane configuration' }))
  await waitFor(() => expect(api.updateLane).toHaveBeenCalledWith('org', 'lane', expect.objectContaining({
    originZoneId: 'north-chilled', destinationZoneId: 'south-chilled', version: 4,
  }), expect.any(AbortSignal)))
})

it('reconciles a lost cancellation response from saved evidence without retrying the command', async () => {
  let savedCancellation = false
  api.getLane.mockImplementation(async () => lane(savedCancellation ? 4 : 3))
  api.laneHistory.mockImplementation(async () => history(savedCancellation
    ? { ...scheduledRevision, cancellation: {
      id: 'cancellation-3', revision: 3, revisionId: scheduledRevision.id, cancelledAt: '2026-10-31T12:05:00Z', cancelledByMembershipId: 'member-a', reason: 'Carrier agreement changed before the planned start',
    } }
    : scheduledRevision))
  api.cancelScheduledLaneRevisions.mockImplementation(async () => {
    savedCancellation = true
    throw new TypeError('Network disconnected after server commit')
  })
  const { user } = renderPage()
  await user.click(await screen.findByRole('button', { name: 'Review and cancel scheduled costs' }))
  await user.type(await screen.findByLabelText('Reason for cancelling scheduled costs'), 'Carrier agreement changed before the planned start')
  await user.click(screen.getByLabelText('I reviewed the listed revisions and confirm cancelling all of them.'))
  await user.click(screen.getByRole('button', { name: 'Cancel all 1 scheduled revision' }))

  expect(await screen.findByRole('status')).toHaveTextContent('Saved history confirms 1 reviewed scheduled cost cancelled.')
  expect(api.cancelScheduledLaneRevisions).toHaveBeenCalledTimes(1)
  expect(api.laneHistory.mock.calls.length).toBeGreaterThan(3)
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it('refreshes the complete schedule and requires new confirmation after a stale cancellation conflict', async () => {
  let cancellationAttempted = false
  let laneReads = 0
  const laterRevision: Revision = {
    ...scheduledRevision, id: 'revision-4', revision: 4, effectiveAt: '2031-01-01T00:00:00Z', costAmount: '120.0000',
  }
  api.getLane.mockImplementation(async () => lane(++laneReads >= 3 ? 4 : 3))
  api.laneHistory.mockImplementation(async () => history(cancellationAttempted ? [scheduledRevision, laterRevision] : scheduledRevision))
  api.cancelScheduledLaneRevisions.mockImplementation(async () => {
    cancellationAttempted = true
    throw new ApiError(new Response(null, { status: 409 }), { code: 'CONFIGURATION_CONFLICT' } as never)
  })
  const { user } = renderPage()
  await user.click(await screen.findByRole('button', { name: 'Review and cancel scheduled costs' }))
  await user.type(await screen.findByLabelText('Reason for cancelling scheduled costs'), 'Carrier agreement changed before the planned start')
  await user.click(screen.getByLabelText('I reviewed the listed revisions and confirm cancelling all of them.'))
  await user.click(screen.getByRole('button', { name: 'Cancel all 1 scheduled revision' }))

  const refreshed = await screen.findByRole('region', { name: 'Review and cancel scheduled costs' })
  expect(refreshed).toHaveTextContent('Revision 4')
  expect(refreshed).toHaveTextContent('120.0000 EUR · 60 minutes')
  expect(screen.getByRole('button', { name: 'Cancel all 2 scheduled revisions' })).toBeDisabled()
  expect(screen.getByRole('alert')).toHaveTextContent('CONFIGURATION_CONFLICT')
  expect(api.cancelScheduledLaneRevisions).toHaveBeenCalledTimes(1)
  expect(api.getLane).toHaveBeenCalledTimes(3)
})

it('does not send two cancellation commands for rapid confirmation clicks', async () => {
  let savedCancellation = false
  let complete!: (value: { lane: Lane, cancelledRevisions: Array<{ id: string, revisionId: string, revision: number, version: number, cancelledAt: string, role: 'ORG_ADMIN', cancelledByMembershipId: string, reason: string }> }) => void
  api.getLane.mockImplementation(async () => lane(savedCancellation ? 4 : 3))
  api.laneHistory.mockImplementation(async () => history(savedCancellation
    ? { ...scheduledRevision, cancellation: {
      id: 'cancellation-3', revision: 3, revisionId: scheduledRevision.id, cancelledAt: '2026-10-31T12:05:00Z', cancelledByMembershipId: 'member-a', reason: 'Carrier agreement changed before the planned start',
    } }
    : scheduledRevision))
  api.cancelScheduledLaneRevisions.mockImplementation(() => new Promise(resolve => { complete = resolve }))
  const { user } = renderPage()
  await user.click(await screen.findByRole('button', { name: 'Review and cancel scheduled costs' }))
  await user.type(await screen.findByLabelText('Reason for cancelling scheduled costs'), 'Carrier agreement changed before the planned start')
  await user.click(screen.getByLabelText('I reviewed the listed revisions and confirm cancelling all of them.'))
  const confirm = screen.getByRole('button', { name: 'Cancel all 1 scheduled revision' })
  fireEvent.click(confirm)
  fireEvent.click(confirm)
  expect(api.cancelScheduledLaneRevisions).toHaveBeenCalledTimes(1)
  await act(async () => {
    savedCancellation = true
    complete({
    lane: lane(4),
    cancelledRevisions: [{ id: 'cancellation-3', revisionId: scheduledRevision.id, revision: 3, version: 4, cancelledAt: '2026-10-31T12:05:00Z', role: 'ORG_ADMIN', cancelledByMembershipId: 'member-a', reason: 'Carrier agreement changed before the planned start' }],
    })
  })
  expect(await screen.findByRole('status')).toHaveTextContent('Saved history confirms 1 reviewed scheduled cost cancelled.')
})

it('aborts an in-flight lane command when the lane screen unmounts', async () => {
  let commandSignal: AbortSignal | undefined
  api.cancelScheduledLaneRevisions.mockImplementation((_organizationId: string, _laneId: string, _body: unknown, signal: AbortSignal) => {
    commandSignal = signal
    return new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }))
  })
  const { user, view } = renderPage()
  await user.click(await screen.findByRole('button', { name: 'Review and cancel scheduled costs' }))
  await user.type(await screen.findByLabelText('Reason for cancelling scheduled costs'), 'Carrier agreement changed before the planned start')
  await user.click(screen.getByLabelText('I reviewed the listed revisions and confirm cancelling all of them.'))
  fireEvent.click(screen.getByRole('button', { name: 'Cancel all 1 scheduled revision' }))
  await waitFor(() => expect(commandSignal).toBeDefined())
  view.unmount()
  expect(commandSignal?.aborted).toBe(true)
})

it('blocks further lane actions when refreshed authorization is revoked during cancellation', async () => {
  let revoked = false
  const denied = () => new ApiError(new Response(null, { status: 403 }), { code: 'ACCESS_DENIED' } as never)
  api.cancelScheduledLaneRevisions.mockImplementation(async () => { revoked = true; throw denied() })
  api.getLane.mockImplementation(async () => { if (revoked) throw denied(); return lane(3) })
  api.laneHistory.mockImplementation(async () => { if (revoked) throw denied(); return history(scheduledRevision) })
  const { user } = renderPage()
  await user.click(await screen.findByRole('button', { name: 'Review and cancel scheduled costs' }))
  await user.type(await screen.findByLabelText('Reason for cancelling scheduled costs'), 'Carrier agreement changed before the planned start')
  await user.click(screen.getByLabelText('I reviewed the listed revisions and confirm cancelling all of them.'))
  fireEvent.click(screen.getByRole('button', { name: 'Cancel all 1 scheduled revision' }))
  expect(await screen.findByRole('heading', { name: 'Lane state needs reconciliation' })).toBeInTheDocument()
  expect(api.cancelScheduledLaneRevisions).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('button', { name: 'Reload lane and history before continuing' })).toBeEnabled()
})

it('loads every future schedule across history pages larger than the initial page', async () => {
  const revisions = Array.from({ length: 101 }, (_, index): Revision => ({
    ...scheduledRevision,
    id: `revision-${index + 1}`,
    revision: index + 1,
    effectiveAt: `2030-01-${String(Math.floor(index / 24) + 1).padStart(2, '0')}T${String(index % 24).padStart(2, '0')}:00:00Z`,
  }))
  api.laneHistory.mockImplementation(async (...args) => {
    const offset = args[2] as number
    const limit = (args[4] as number | undefined) ?? 25
    const items = revisions.slice(offset, offset + limit)
    return { items, offset, limit, hasMore: offset + limit < revisions.length }
  })
  const { user } = renderPage()
  await user.click(await screen.findByRole('button', { name: 'Review and cancel scheduled costs' }))
  const list = await screen.findByRole('list', { name: 'Scheduled cost revisions to cancel' })
  expect(list.querySelectorAll('li')).toHaveLength(101)
  expect(screen.getByRole('button', { name: 'Cancel all 101 scheduled revisions' })).toBeDisabled()
  expect(api.laneHistory).toHaveBeenCalledWith('org', 'lane', 0, expect.any(AbortSignal), 100)
  expect(api.laneHistory).toHaveBeenCalledWith('org', 'lane', 100, expect.any(AbortSignal), 100)
})

it('keeps cancellation available for inactive lanes and explains archived endpoint limits', async () => {
  api.getLane.mockResolvedValue({ ...lane(3, false), missing: ['INACTIVE_LANE'] })
  const { user, client } = renderPage()
  await screen.findByText(/To recover this lane, select compatible active zones, enable the lane and save\./)
  expect(screen.queryByRole('button', { name: 'Approve current cost for test' })).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Review and cancel scheduled costs' }))
  expect(await screen.findByRole('region', { name: 'Review and cancel scheduled costs' })).toBeInTheDocument()
  cleanup()
  client.clear()

  api.getLane.mockResolvedValue({ ...lane(3), missing: ['INACTIVE_STORE'] })
  renderPage()
  expect(await screen.findByText('An endpoint store is archived. Future costs can still be cancelled for audit, but transport cannot be re-enabled while that store remains archived.')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Save lane configuration' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Approve current cost for test' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Review and cancel scheduled costs' })).toBeInTheDocument()
})
