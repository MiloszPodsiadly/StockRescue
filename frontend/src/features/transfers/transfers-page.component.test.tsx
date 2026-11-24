import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { Store } from '@/api/stores'
import type { Transfer, TransferQueueItem } from '@/api/transfers'
import { TenantScope } from '../organizations/tenant-requests'
import { OrganizationContext } from '../organizations/organization-context'
import { CreateTransferPanel, HistoricalTransfersPage, TransferDetailView, TransfersPage } from './transfers-page'
import { reconcileTransferSummaries } from './transfer-summary-reconciliation'

const apiMocks = vi.hoisted(() => ({
  available: vi.fn(), locations: vi.fn(), stores: vi.fn(), product: vi.fn(), lot: vi.fn(), sourceLocation: vi.fn(), lane: vi.fn(), positions: vi.fn(), sourcePosition: vi.fn(),
  custody: vi.fn(), transit: vi.fn(), discrepancies: vi.fn(), activeReturns: vi.fn(), command: vi.fn(),
  capabilities: vi.fn(), settlementPositions: vi.fn(), queue: vi.fn(), historicalQueue: vi.fn(), queueEvidence: vi.fn(), pendingIntents: vi.fn(),
}))

vi.mock('@tanstack/react-router', () => ({ useParams: () => ({ organizationId: orgId }), useNavigate: () => vi.fn() }))
vi.mock('../organizations/organization-page', async () => {
  const React = await import('react')
  return { OrganizationPage: ({ children }: { children: React.ReactNode }) => React.createElement(React.Fragment, null, children) }
})

vi.mock('@/api/inventory', () => ({
  getInventorySnapshot: apiMocks.sourcePosition,
  listInventoryAvailableByLot: apiMocks.available,
  listInventoryPositions: apiMocks.positions,
}))
vi.mock('@/api/storage-locations', () => ({ getStorageLocation: apiMocks.sourceLocation, listStorageLocations: apiMocks.locations }))
vi.mock('@/api/products', () => ({ getProduct: apiMocks.product }))
vi.mock('@/api/lots', () => ({ getLot: apiMocks.lot }))
vi.mock('@/api/stores', () => ({ getStore: vi.fn(), listStores: apiMocks.stores }))
vi.mock('@/api/configuration', () => ({ lookupLane: apiMocks.lane }))
vi.mock('@/components/app-link', () => ({ AppLink: ({ href, children, ...props }: React.ComponentProps<'a'>) => <a href={href} {...props}>{children}</a> }))
vi.mock('@/api/transfers', () => ({
  executeTransferCommand: apiMocks.command,
  getTransfer: vi.fn(),
  getTransferCapabilities: apiMocks.capabilities,
  getTransferSettlementPositions: apiMocks.settlementPositions,
  listPendingTransferCommandIntents: apiMocks.pendingIntents,
  listTransfers: apiMocks.queue,
  listHistoricalTransfers: apiMocks.historicalQueue,
  listTransferCustody: apiMocks.custody,
  listTransferDiscrepancies: apiMocks.queueEvidence,
  listTransferScopedDiscrepancies: apiMocks.discrepancies,
  listActiveTransferReturns: apiMocks.activeReturns,
  listTransfersInTransit: apiMocks.transit,
  listTransferInTransit: apiMocks.transit,
  recoverTransferCommand: vi.fn(),
}))

const orgId = 'org-1'
const sourcePositionA = { positionId: 'position-a', productId: 'product-1', lotId: 'lot-1', locationId: 'source-location-a', positionVersion: 3, available: 10, initialization: 'INITIALIZED', trust: 'CLEAN', unit: 'EA' }
const sourcePositionB = { positionId: 'position-b', productId: 'product-2', lotId: 'lot-2', locationId: 'source-location-b', positionVersion: 7, available: 8, initialization: 'INITIALIZED', trust: 'CLEAN', unit: 'EA' }
const sourceLocationA = { id: 'source-location-a', code: 'SRC-A', name: 'Shelf A', zoneId: 'source-zone', usable: true }
const sourceLocationB = { id: 'source-location-b', code: 'SRC-B', name: 'Shelf B', zoneId: 'source-zone', usable: true }
const destinationLocation = { id: 'destination-location', code: 'DST-A', name: 'Receiving shelf', zoneId: 'destination-zone', usable: true }

function store(id: string, allowedActions: string[]) {
  return { id, code: id.toUpperCase(), name: id, active: true, allowedActions } as Store
}

function transferFixture(status: Transfer['status'] = 'IN_TRANSIT'): Transfer {
  return {
    id: 'transfer-1', sourceStoreId: 'source', destinationStoreId: 'destination', createdAt: '2026-11-20T10:00:00Z',
    updatedAt: '2026-11-20T10:00:00Z', version: 4, lineCount: 1, outstandingQuantity: 2, status,
    canCancel: false,
    lines: [{ id: 'line-1', lineNumber: 1, sourcePositionId: 'position-a', destinationLocationId: 'destination-location',
      productId: 'product-1', lotId: 'lot-1', sku: 'SKU-1', lotCode: 'LOT-1', requestedQuantity: 5, unit: 'EA',
      expectedSourceVersion: 3, picked: true, reservationAllocationId: null,
      quantities: { dispatched: 5, physicallyReceived: 3, confirmedLoss: 0, physicallyReturned: 0, outstanding: 2 } }],
  } as Transfer
}

function queueSummary(overrides: Partial<TransferQueueItem> = {}): TransferQueueItem {
  return { id: 'transfer-duplicate', sourceStoreId: 'source', destinationStoreId: 'destination',
    status: 'IN_TRANSIT', createdAt: '2026-11-20T10:00:00Z', updatedAt: '2026-11-20T11:00:00Z',
    version: 4, lineCount: 1, outstandingQuantity: 2, ...overrides }
}

function renderWithTenant(element: React.ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><TenantScope.Provider value={['tenant', orgId, 'actor-1']}>{element}</TenantScope.Provider></QueryClientProvider>)
}

function renderTransfersWorkspace(role = 'ORG_ADMIN') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const organization = { id: orgId, role } as never
  return render(<QueryClientProvider client={queryClient}>
    <TenantScope.Provider value={['tenant', orgId, 'actor-1']}><OrganizationContext.Provider value={organization}>
      <TransfersPage />
    </OrganizationContext.Provider></TenantScope.Provider>
  </QueryClientProvider>)
}

beforeEach(() => {
  vi.clearAllMocks()
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['REQUEST_TRANSFER', 'READ_INVENTORY']), store('destination', ['READ_STORE'])], hasMore: false })
  apiMocks.available.mockResolvedValue({ items: [sourcePositionA, sourcePositionB], hasMore: false })
  apiMocks.locations.mockImplementation(async (_org: string, storeId: string) => ({ items: storeId === 'destination' ? [destinationLocation] : [], hasMore: false }))
  apiMocks.sourceLocation.mockImplementation(async (_org: string, _store: string, locationId: string) => locationId === 'source-location-a' ? sourceLocationA : sourceLocationB)
  apiMocks.product.mockImplementation(async (_org: string, productId: string) => ({ id: productId, sku: productId === 'product-1' ? 'SKU-1' : 'SKU-2', name: productId === 'product-1' ? 'Product one' : 'Product two' }))
  apiMocks.lot.mockImplementation(async (_org: string, lotId: string) => ({ id: lotId, lotCode: lotId === 'lot-1' ? 'LOT-1' : 'LOT-2' }))
  apiMocks.lane.mockResolvedValue({ status: 'CONFIGURED', lane: { originZoneId: 'source-zone', destinationZoneId: 'destination-zone' } })
  apiMocks.positions.mockResolvedValue({ items: [{ id: 'destination-position', productId: 'product-1', lotId: 'lot-1', locationId: 'destination-location', version: 2 }] })
  apiMocks.sourcePosition.mockResolvedValue({ version: 9, onHand: 12, reserved: 0, available: 12 })
  apiMocks.custody.mockResolvedValue({ items: [], hasMore: false })
  apiMocks.transit.mockResolvedValue({ items: [], hasMore: false })
  apiMocks.discrepancies.mockResolvedValue({ items: [], hasMore: false })
  apiMocks.activeReturns.mockResolvedValue({ items: [] })
  apiMocks.capabilities.mockResolvedValue({ sourceActions: ['READ_STORE', 'READ_TRANSFERS', 'READ_INVENTORY', 'RECEIVE_STOCK', 'REQUEST_TRANSFER'],
    destinationActions: ['READ_STORE', 'READ_TRANSFERS', 'READ_INVENTORY', 'RECEIVE_STOCK', 'REQUEST_TRANSFER_RETURN'],
    canReceivePhysicalSettlement: false, canConfirmPhysicalReturn: false })
  apiMocks.settlementPositions.mockResolvedValue({ sourcePositions: [{ lineId: 'line-1', positionId: 'position-a', version: 9 }],
    destinationPositions: [{ lineId: 'line-1', positionId: null, version: 0 }] })
  apiMocks.queue.mockResolvedValue({ items: [], nextCursor: null, hasMore: false })
  apiMocks.historicalQueue.mockResolvedValue({ items: [], nextCursor: null, hasMore: false })
  apiMocks.queueEvidence.mockResolvedValue({ items: [], nextCursor: null, hasMore: false })
  apiMocks.pendingIntents.mockResolvedValue({ items: [] })
  apiMocks.command.mockResolvedValue({ transferId: 'transfer-1', status: 'REQUESTED', version: 1 })
})

it('reconciles duplicate transfer snapshots by version, independent of store and page order', () => {
  const stale = queueSummary({ status: 'IN_TRANSIT', version: 4, updatedAt: '2026-11-20T11:00:00Z' })
  const current = queueSummary({ status: 'PARTIALLY_RECEIVED', version: 5, updatedAt: '2026-11-20T11:00:00Z', outstandingQuantity: 1 })
  const separate = queueSummary({ id: 'transfer-separate', version: 2, updatedAt: '2026-11-19T11:00:00Z' })

  expect(reconcileTransferSummaries([stale, current])).toEqual([current])
  expect(reconcileTransferSummaries([current, stale])).toEqual([current])
  expect(reconcileTransferSummaries([current, separate, stale, current])).toEqual([current, separate])
  expect(reconcileTransferSummaries([separate, stale, current, current])).toEqual([current, separate])
})

it('chooses the later update for equal versions and a stable winner for an equal-time conflict', () => {
  const earlier = queueSummary({ status: 'IN_TRANSIT', version: 5, updatedAt: '2026-11-20T11:00:00Z' })
  const later = queueSummary({ status: 'PARTIALLY_RECEIVED', version: 5, updatedAt: '2026-11-20T12:00:00Z' })
  const conflictA = queueSummary({ status: 'IN_TRANSIT', version: 5, updatedAt: '2026-11-20T12:00:00Z' })
  const conflictB = queueSummary({ status: 'PARTIALLY_RECEIVED', version: 5, updatedAt: '2026-11-20T12:00:00Z' })
  const microOlder = queueSummary({ version: 6, updatedAt: '2026-11-20T12:00:00.123456Z' })
  const microNewer = queueSummary({ status: 'PARTIALLY_RECEIVED', version: 6, updatedAt: '2026-11-20T12:00:00.123789Z' })

  expect(reconcileTransferSummaries([earlier, later])).toEqual([later])
  expect(reconcileTransferSummaries([conflictA, conflictB])).toEqual(reconcileTransferSummaries([conflictB, conflictA]))
  expect(reconcileTransferSummaries([microOlder, microNewer])).toEqual([microNewer])
})

it('renders one queue card using the newest summary returned by different store requests', async () => {
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS']), store('destination', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.queue.mockImplementation(async (_org: string, storeId: string) => ({
    items: [queueSummary(storeId === 'source'
      ? { status: 'IN_TRANSIT', version: 4 }
      : { status: 'PARTIALLY_RECEIVED', version: 5, outstandingQuantity: 1 })],
    nextCursor: null, hasMore: false,
  }))
  renderTransfersWorkspace()

  const queue = await screen.findByRole('region', { name: 'Transfer queue' })
  const cards = await within(queue).findAllByRole('listitem')
  expect(cards).toHaveLength(1)
  expect(cards[0]).toHaveTextContent('Partially received')
  expect(cards[0]).not.toHaveTextContent('In transit')
})

it('does not request exhausted stores again when queue, transit and evidence pages continue', async () => {
  const user = userEvent.setup()
  const stores = [store('A', ['READ_TRANSFERS']), store('B', ['READ_TRANSFERS'])]
  apiMocks.stores.mockImplementation(async (_org: string, _state: string, _search: string, offset: number) => ({
    items: offset === 0 ? stores : [], hasMore: false,
  }))
  const transfer = (id: string) => ({ id, sourceStoreId: 'A', destinationStoreId: 'B', status: 'REQUESTED',
    updatedAt: '2026-11-20T10:00:00Z', lineCount: 1, outstandingQuantity: 0 })
  const transitLine = (id: string) => ({ transferId: id, lineId: `line-${id}`, outstanding: 1, ageSeconds: 60,
    dispatchedAt: '2026-11-20T10:00:00Z' })
  const evidence = (id: string) => ({ id: `evidence-${id}`, transferId: id, type: 'DISCREPANCY',
    resolutionScope: 'TRANSFER_OPEN', lineSettled: false, transferClosed: false, recordedAt: '2026-11-20T10:00:00Z' })
  apiMocks.queue.mockImplementation(async (_org: string, storeId: string, _status: string, _signal: AbortSignal, cursor: string | null) =>
    storeId === 'A' ? { items: [transfer('transfer-A')], nextCursor: null, hasMore: false }
      : cursor === null ? { items: [transfer('transfer-B')], nextCursor: 'B-page-2', hasMore: true }
        : { items: [transfer('transfer-B-2')], nextCursor: null, hasMore: false })
  apiMocks.transit.mockImplementation(async (_org: string, storeId: string, _signal: AbortSignal, cursor: string | null) =>
    storeId === 'A' ? { items: [], nextCursor: null, hasMore: false }
      : cursor === null ? { items: [transitLine('transfer-B')], nextCursor: 'B-transit-2', hasMore: true }
        : { items: [transitLine('transfer-B-2')], nextCursor: null, hasMore: false })
  apiMocks.queueEvidence.mockImplementation(async (_org: string, storeId: string, _signal: AbortSignal, cursor: string | null) =>
    storeId === 'A' ? { items: [], nextCursor: null, hasMore: false }
      : cursor === null ? { items: [evidence('transfer-B')], nextCursor: 'B-evidence-2', hasMore: true }
        : { items: [evidence('transfer-B-2')], nextCursor: null, hasMore: false })
  const organization = { id: orgId, role: 'ORG_ADMIN' } as never
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <TenantScope.Provider value={['tenant', orgId, 'actor-1']}><OrganizationContext.Provider value={organization}>
      <TransfersPage />
    </OrganizationContext.Provider></TenantScope.Provider>
  </QueryClientProvider>)

  expect(await screen.findByRole('button', { name: 'Load more transfers' })).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Load more transfers' }))
  await waitFor(() => expect(apiMocks.queue).toHaveBeenCalledTimes(3))
  await user.click(await screen.findByRole('button', { name: 'Load more in-transit lines' }))
  await waitFor(() => expect(apiMocks.transit).toHaveBeenCalledTimes(3))
  await user.click(await screen.findByRole('button', { name: 'Load more evidence' }))
  await waitFor(() => expect(apiMocks.queueEvidence).toHaveBeenCalledTimes(3))

  expect(apiMocks.queue.mock.calls.map(call => [call[1], call[4]])).toEqual([['A', null], ['B', null], ['B', 'B-page-2']])
  expect(apiMocks.transit.mock.calls.map(call => [call[1], call[3]])).toEqual([['A', null], ['B', null], ['B', 'B-transit-2']])
  expect(apiMocks.queueEvidence.mock.calls.map(call => [call[1], call[3]])).toEqual([['A', null], ['B', null], ['B', 'B-evidence-2']])
  expect(screen.queryByRole('button', { name: 'Load more transfers' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Load more in-transit lines' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Load more evidence' })).not.toBeInTheDocument()
})

it('finds historical transfers without active stores and pages authorized archived labels', async () => {
  const user = userEvent.setup()
  const first = { transfer: { id: 'historical-transfer-1', sourceStoreId: 'source', destinationStoreId: 'destination',
    status: 'IN_TRANSIT', version: 4, createdAt: '2026-11-20T10:00:00Z', updatedAt: '2026-11-20T11:00:00Z', lineCount: 1, outstandingQuantity: 2 },
  source: { id: 'source', code: 'NORTH', name: 'North depot', active: true },
  destination: { id: 'destination', code: 'SOUTH', name: 'South depot', active: false } }
  const second = { transfer: { ...first.transfer, id: 'historical-transfer-2', status: 'RECEIVED', outstandingQuantity: 0 },
    source: { ...first.source, active: false }, destination: first.destination }
  apiMocks.historicalQueue.mockImplementation(async (_organizationId: string, _status: string, _signal: AbortSignal, cursor: string | null) =>
    cursor === null ? { items: [first], nextCursor: 'historical-page-2', hasMore: true }
      : { items: [second], nextCursor: null, hasMore: false })
  const organization = { id: orgId, role: 'ORG_ADMIN' } as never
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <TenantScope.Provider value={['tenant', orgId, 'actor-1']}><OrganizationContext.Provider value={organization}>
      <HistoricalTransfersPage />
    </OrganizationContext.Provider></TenantScope.Provider>
  </QueryClientProvider>)

  expect(await screen.findByRole('heading', { name: 'Historical transfers' })).toBeInTheDocument()
  const firstRow = await screen.findByRole('listitem')
  expect(firstRow).toHaveTextContent('NORTH · North depot')
  expect(firstRow).toHaveTextContent('SOUTH · South depot')
  expect(within(firstRow).getByText('Archived')).toBeInTheDocument()
  expect(within(firstRow).getByRole('link', { name: 'Open historical transfer' })).toHaveAttribute(
    'href', `/organizations/${orgId}/transfers/historical-transfer-1`)
  expect(apiMocks.stores).not.toHaveBeenCalled()

  await user.click(await screen.findByRole('button', { name: 'Load more historical transfers' }))
  await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(2))
  const secondRow = screen.getAllByRole('listitem')[1]!
  expect(secondRow).toHaveTextContent('Archived')
  expect(apiMocks.historicalQueue.mock.calls.map(call => [call[1], call[3], call[4]])).toEqual([
    ['', null, 50], ['', 'historical-page-2', 50],
  ])
})

it('keeps store search available after no matches and restores the transfer queue when cleared', async () => {
  const user = userEvent.setup()
  const source = store('source', ['READ_TRANSFERS'])
  apiMocks.stores.mockImplementation(async (_org: string, _state: string, search: string, _offset: number, _signal: AbortSignal, limit?: number) => {
    if (limit === 100) return { items: [source], hasMore: false }
    return search === 'unmatched' ? { items: [], hasMore: false } : { items: [source], hasMore: false }
  })
  apiMocks.queue.mockResolvedValue({ items: [{ id: 'transfer-visible', sourceStoreId: 'source', destinationStoreId: 'destination',
    status: 'REQUESTED', updatedAt: '2026-11-20T10:00:00Z', lineCount: 1, outstandingQuantity: 0 }], nextCursor: null, hasMore: false })
  renderTransfersWorkspace()

  expect(await screen.findByText(/Transfer transfer-visible/)).toBeInTheDocument()
  const search = screen.getByRole('searchbox', { name: 'Find a store' })
  await user.type(search, 'unmatched')
  expect(await screen.findByRole('heading', { name: 'No accessible stores match this search' })).toBeInTheDocument()
  expect(screen.getByRole('searchbox', { name: 'Find a store' })).toHaveValue('unmatched')
  expect(apiMocks.queue.mock.calls.every(call => call[1] === 'source')).toBe(true)

  await user.click(screen.getByRole('button', { name: 'Clear search' }))
  expect(await screen.findByText(/Transfer transfer-visible/)).toBeInTheDocument()
  expect(apiMocks.queue).toHaveBeenCalledWith(orgId, 'source', '', expect.any(AbortSignal), null, 50)
})

it('keeps store pagination and search recovery reachable when an empty page has more results', async () => {
  const user = userEvent.setup()
  const source = store('source', ['READ_TRANSFERS'])
  apiMocks.stores.mockImplementation(async (_org: string, _state: string, search: string, offset: number, _signal: AbortSignal, limit?: number) => {
    if (limit === 100) return { items: [source], hasMore: false }
    if (search === 'unmatched') return offset === 0 ? { items: [], hasMore: true } : { items: [], hasMore: false }
    return { items: [source], hasMore: false }
  })
  renderTransfersWorkspace()

  const search = await screen.findByRole('searchbox', { name: 'Find a store' })
  await user.type(search, 'unmatched')
  expect(await screen.findByRole('heading', { name: 'No accessible stores match this search' })).toBeInTheDocument()
  const loadMore = screen.getByRole('button', { name: 'Load more accessible stores' })
  await user.click(loadMore)
  await waitFor(() => expect(apiMocks.stores).toHaveBeenCalledWith(orgId, 'ACTIVE', 'unmatched', 25, expect.any(AbortSignal)))
  expect(screen.getByRole('searchbox', { name: 'Find a store' })).toHaveValue('unmatched')
  expect(apiMocks.queue.mock.calls.every(call => call[1] === 'source')).toBe(true)
})

it('reports truly missing transfer access without requesting any transfer queues', async () => {
  apiMocks.stores.mockResolvedValue({ items: [], hasMore: false })
  renderTransfersWorkspace('ORG_MEMBER')

  expect(await screen.findByRole('heading', { name: 'No transfer access for any active store' })).toBeInTheDocument()
  expect(screen.getByRole('searchbox', { name: 'Find a store' })).toBeInTheDocument()
  expect(apiMocks.queue).not.toHaveBeenCalled()
  expect(apiMocks.transit).not.toHaveBeenCalled()
  expect(apiMocks.queueEvidence).not.toHaveBeenCalled()
})

it('keeps the store access retry reachable after a store query fails', async () => {
  const source = store('source', ['READ_TRANSFERS'])
  let failSearch = true
  apiMocks.stores.mockImplementation(async (_org: string, _state: string, _search: string, _offset: number, _signal: AbortSignal, limit?: number) => {
    if (limit === 100) return { items: [source], hasMore: false }
    if (failSearch) { failSearch = false; throw new Error('store query unavailable') }
    return { items: [source], hasMore: false }
  })
  renderTransfersWorkspace()

  const retry = await screen.findByRole('button', { name: 'Retry store access' })
  await userEvent.setup().click(retry)
  expect(await screen.findByRole('heading', { name: 'No transfers in this view' })).toBeInTheDocument()
  expect(apiMocks.queue).toHaveBeenCalledWith(orgId, 'source', '', expect.any(AbortSignal), null, 50)
})

it('limits combined queue, transit and evidence fan-out while loading every store', async () => {
  const stores = Array.from({ length: 25 }, (_, index) => store(`store-${index + 1}`, ['READ_TRANSFERS']))
  apiMocks.stores.mockResolvedValue({ items: stores, hasMore: false })
  let active = 0
  let maximumActive = 0
  let completed = 0
  async function response<T>(value: T): Promise<T> {
    active++
    maximumActive = Math.max(maximumActive, active)
    await Promise.resolve()
    active--
    completed++
    return value
  }
  apiMocks.queue.mockImplementation(async () => response({ items: [], nextCursor: null, hasMore: false }))
  apiMocks.transit.mockImplementation(async () => response({ items: [], nextCursor: null, hasMore: false }))
  apiMocks.queueEvidence.mockImplementation(async () => response({ items: [], nextCursor: null, hasMore: false }))
  const organization = { id: orgId, role: 'ORG_ADMIN' } as never

  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <TenantScope.Provider value={['tenant', orgId, 'actor-1']}><OrganizationContext.Provider value={organization}>
      <TransfersPage />
    </OrganizationContext.Provider></TenantScope.Provider>
  </QueryClientProvider>)

  await waitFor(() => expect(completed).toBe(75))
  expect(maximumActive).toBeLessThanOrEqual(8)
  expect(apiMocks.queue.mock.calls).toHaveLength(25)
  expect(apiMocks.transit.mock.calls).toHaveLength(25)
  expect(apiMocks.queueEvidence.mock.calls).toHaveLength(25)
  expect(new Set(apiMocks.queue.mock.calls.map(call => call[1])).size).toBe(25)
  expect(new Set(apiMocks.transit.mock.calls.map(call => call[1])).size).toBe(25)
  expect(new Set(apiMocks.queueEvidence.mock.calls.map(call => call[1])).size).toBe(25)
})

it('edits two transfer lines, blocks a duplicate source position and submits each observed version', async () => {
  const user = userEvent.setup()
  const onCommand = vi.fn().mockResolvedValue(undefined)
  renderWithTenant(<CreateTransferPanel organizationId={orgId} administrator onCommand={onCommand} busy={false} />)

  await user.click(screen.getByRole('button', { name: 'New transfer request' }))
  await user.selectOptions(screen.getByLabelText('Source store'), 'source')
  await user.selectOptions(screen.getByLabelText('Destination store'), 'destination')
  const firstSource = await screen.findByLabelText('Source product and lot')
  await user.selectOptions(firstSource, 'position-a')
  await user.selectOptions(screen.getByLabelText('Destination receiving location'), 'destination-location')
  await user.type(screen.getByLabelText('Quantity (EA)'), '4')
  await user.click(screen.getByRole('button', { name: 'Add transfer line' }))

  const secondSource = screen.getByLabelText('Source product and lot for line 2')
  expect(within(secondSource).getByRole('option', { name: /SKU-1.*already selected/ })).toBeDisabled()
  await user.selectOptions(secondSource, 'position-b')
  await user.selectOptions(screen.getByLabelText('Destination receiving location for line 2'), 'destination-location')
  await user.type(screen.getByLabelText('Quantity for line 2 (EA)'), '3')
  await user.type(screen.getByLabelText('Reason for transfer'), 'Rebalance two reviewed items')
  await user.type(screen.getByLabelText('Evidence reference'), 'plan:two-lines')

  const submit = screen.getByRole('button', { name: 'Create transfer request' })
  await waitFor(() => expect(submit).toBeEnabled())
  await user.click(submit)
  await waitFor(() => expect(onCommand).toHaveBeenCalledTimes(1))
  expect(onCommand.mock.calls[0]![0]).toMatchObject({ operation: 'CREATE', body: {
    lines: [
      { sourcePositionId: 'position-a', destinationLocationId: 'destination-location', quantity: 4, expectedSourceVersion: 3 },
      { sourcePositionId: 'position-b', destinationLocationId: 'destination-location', quantity: 3, expectedSourceVersion: 7 },
    ],
  } })
})

it('finds independently searched source and destination stores on later pages', async () => {
  const user = userEvent.setup()
  const onCommand = vi.fn().mockResolvedValue(undefined)
  const sourceStore = store('source-26', ['REQUEST_TRANSFER', 'READ_INVENTORY'])
  const destinationStore = store('destination-26', ['READ_STORE'])
  apiMocks.stores.mockImplementation(async (_org: string, _state: string, query: string, offset: number, _signal: AbortSignal, limit?: number) => {
    if (limit === 100) return { items: query === sourceStore.code ? [sourceStore] : query === destinationStore.code ? [destinationStore] : [], hasMore: false }
    if (query === 'Warehouse Alpha') return offset === 0 ? { items: [], hasMore: true } : { items: [sourceStore], hasMore: false }
    if (query === 'Warehouse Omega') return offset === 0 ? { items: [], hasMore: true } : { items: [destinationStore], hasMore: false }
    return { items: [], hasMore: false }
  })
  const pageOne = Array.from({ length: 100 }, (_, index) => ({ ...sourcePositionA, positionId: `position-${index + 1}`, productId: `product-${index + 1}`, lotId: `lot-${index + 1}`, locationId: `source-location-${index + 1}` }))
  apiMocks.available.mockImplementation(async (_org: string, _store: string, filters: { offset: number }) => filters.offset === 0
    ? { items: pageOne, hasMore: true }
    : { items: [{ ...sourcePositionA, positionId: 'position-101', productId: 'product-101', lotId: 'lot-101', locationId: 'source-location-101', positionVersion: 101 }], hasMore: false })
  const firstLocations = Array.from({ length: 25 }, (_, index) => ({ ...destinationLocation, id: `destination-location-${index + 1}`, code: `DST-${index + 1}` }))
  apiMocks.locations.mockImplementation(async (_org: string, _store: string, _includeArchived: boolean, _query: string, offset: number) => offset === 0
    ? { items: firstLocations, hasMore: true }
    : { items: [{ ...destinationLocation, id: 'destination-location-26', code: 'DST-26' }], hasMore: false })
  apiMocks.sourceLocation.mockImplementation(async (_org: string, _store: string, locationId: string) => ({
    ...sourceLocationA, id: locationId, zoneId: 'source-zone',
  }))
  apiMocks.product.mockImplementation(async (_org: string, productId: string) => ({ id: productId, sku: `SKU-${productId.split('-').at(-1)}`, name: `Product ${productId}` }))
  apiMocks.lot.mockImplementation(async (_org: string, lotId: string) => ({ id: lotId, lotCode: `LOT-${lotId.split('-').at(-1)}` }))

  renderWithTenant(<CreateTransferPanel organizationId={orgId} administrator onCommand={onCommand} busy={false} />)
  await user.click(screen.getByRole('button', { name: 'New transfer request' }))
  await user.type(screen.getByLabelText('Find a source store'), 'Warehouse Alpha')
  await user.click(await screen.findByRole('button', { name: 'Load more source stores' }))
  await user.selectOptions(await screen.findByLabelText('Source store'), 'source-26')
  await user.type(screen.getByLabelText('Find a destination store'), 'Warehouse Omega')
  await user.click(await screen.findByRole('button', { name: 'Load more destination stores' }))
  await user.selectOptions(screen.getByLabelText('Destination store'), 'destination-26')
  await user.click(await screen.findByRole('button', { name: 'Load more available source positions' }))
  await user.selectOptions(screen.getByLabelText('Source product and lot'), 'position-101')
  await user.click(await screen.findByRole('button', { name: 'Load more destination locations' }))
  await user.selectOptions(screen.getByLabelText('Destination receiving location'), 'destination-location-26')
  await user.type(screen.getByLabelText('Quantity (EA)'), '1')
  await user.type(screen.getByLabelText('Reason for transfer'), 'Reach stock outside the first page')
  await user.type(screen.getByLabelText('Evidence reference'), 'selection:page-26')

  await waitFor(() => expect(screen.getByRole('button', { name: 'Create transfer request' })).toBeEnabled())
  await user.click(screen.getByRole('button', { name: 'Create transfer request' }))
  await waitFor(() => expect(onCommand).toHaveBeenCalledTimes(1))
  expect(onCommand.mock.calls[0]![0]).toMatchObject({ operation: 'CREATE', body: {
    sourceStoreId: 'source-26', destinationStoreId: 'destination-26',
    lines: [{ sourcePositionId: 'position-101', destinationLocationId: 'destination-location-26', quantity: 1, expectedSourceVersion: 101 }],
  } })
})

it('keeps the selected stores and complete draft when source search is refined', async () => {
  const user = userEvent.setup()
  const onCommand = vi.fn().mockResolvedValue(undefined)
  const source = { ...store('source-alpha', ['REQUEST_TRANSFER', 'READ_INVENTORY']), code: 'ALPHA', name: 'Warehouse Alpha' }
  const destination = { ...store('destination-omega', ['READ_STORE']), code: 'OMEGA', name: 'Warehouse Omega' }
  apiMocks.stores.mockImplementation(async (_org: string, _state: string, query: string, _offset: number, _signal: AbortSignal, limit?: number) => {
    if (limit === 100) return { items: query === source.code ? [source] : query === destination.code ? [destination] : [], hasMore: false }
    if (query === 'Alpha') return { items: [source], hasMore: false }
    if (query === 'Omega') return { items: [destination], hasMore: false }
    if (query.startsWith('Alpha refined')) return { items: [], hasMore: false }
    return { items: [], hasMore: false }
  })
  apiMocks.locations.mockResolvedValue({ items: [destinationLocation], hasMore: false })
  renderWithTenant(<CreateTransferPanel organizationId={orgId} administrator onCommand={onCommand} busy={false} />)
  await user.click(screen.getByRole('button', { name: 'New transfer request' }))
  await user.type(screen.getByLabelText('Find a source store'), 'Alpha')
  await user.selectOptions(await screen.findByLabelText('Source store'), source.id)
  await user.type(screen.getByLabelText('Find a destination store'), 'Omega')
  await user.selectOptions(await screen.findByLabelText('Destination store'), destination.id)
  await user.selectOptions(await screen.findByLabelText('Source product and lot'), 'position-a')
  await user.selectOptions(screen.getByLabelText('Destination receiving location'), 'destination-location')
  await user.type(screen.getByLabelText('Quantity (EA)'), '4')
  await user.type(screen.getByLabelText('Reason for transfer'), 'Move reviewed stock')
  await user.type(screen.getByLabelText('Evidence reference'), 'plan:alpha-omega')

  const sourceSearch = screen.getByRole('searchbox', { name: 'Find a source store' })
  await user.clear(sourceSearch)
  await user.type(sourceSearch, 'Alpha refined')
  expect(await screen.findByText('No eligible source stores match this search.')).toBeInTheDocument()
  expect(screen.getByLabelText('Source store')).toHaveValue(source.id)
  expect(screen.getByLabelText('Destination store')).toHaveValue(destination.id)
  expect(screen.getByLabelText('Source product and lot')).toHaveValue('position-a')
  expect(screen.getByLabelText('Destination receiving location')).toHaveValue('destination-location')
  expect(screen.getByLabelText('Quantity (EA)')).toHaveValue(4)
  expect(screen.getByLabelText('Reason for transfer')).toHaveValue('Move reviewed stock')
  expect(screen.getByLabelText('Evidence reference')).toHaveValue('plan:alpha-omega')

  await waitFor(() => expect(screen.getByRole('button', { name: 'Create transfer request' })).toBeEnabled())
  await user.click(screen.getByRole('button', { name: 'Create transfer request' }))
  await waitFor(() => expect(onCommand).toHaveBeenCalledTimes(1))
  expect(onCommand.mock.calls[0]![0]).toMatchObject({ operation: 'CREATE', body: {
    sourceStoreId: source.id, destinationStoreId: destination.id, reason: 'Move reviewed stock', evidenceReference: 'plan:alpha-omega',
    lines: [{ sourcePositionId: 'position-a', destinationLocationId: 'destination-location', quantity: 4, expectedSourceVersion: 3 }],
  } })
})

it('clears only source or destination dependent draft fields when a store actually changes', async () => {
  const user = userEvent.setup()
  const source = store('source', ['REQUEST_TRANSFER', 'READ_INVENTORY'])
  const alternateSource = store('source-two', ['REQUEST_TRANSFER', 'READ_INVENTORY'])
  const destination = store('destination', ['READ_STORE'])
  const alternateDestination = store('destination-two', ['READ_STORE'])
  apiMocks.stores.mockImplementation(async (_org: string, _state: string, query: string, _offset: number, _signal: AbortSignal, limit?: number) => {
    if (limit === 100) return { items: [source, alternateSource, destination, alternateDestination].filter(item => item.code === query), hasMore: false }
    return { items: [source, alternateSource, destination, alternateDestination], hasMore: false }
  })
  renderWithTenant(<CreateTransferPanel organizationId={orgId} administrator onCommand={vi.fn().mockResolvedValue(undefined)} busy={false} />)
  await user.click(screen.getByRole('button', { name: 'New transfer request' }))
  await user.selectOptions(screen.getByLabelText('Source store'), source.id)
  await user.selectOptions(screen.getByLabelText('Destination store'), destination.id)
  await user.selectOptions(await screen.findByLabelText('Source product and lot'), 'position-a')
  await user.selectOptions(screen.getByLabelText('Destination receiving location'), 'destination-location')
  await user.type(screen.getByLabelText('Quantity (EA)'), '4')
  await user.type(screen.getByLabelText('Reason for transfer'), 'Preserve this reason')
  await user.type(screen.getByLabelText('Evidence reference'), 'case:preserve-evidence')

  await user.selectOptions(screen.getByLabelText('Source store'), alternateSource.id)
  expect(screen.getByLabelText('Source product and lot')).toHaveValue('')
  expect(screen.getByLabelText('Destination store')).toHaveValue(destination.id)
  expect(screen.getByLabelText('Reason for transfer')).toHaveValue('Preserve this reason')
  expect(screen.getByLabelText('Evidence reference')).toHaveValue('case:preserve-evidence')

  await user.selectOptions(await screen.findByLabelText('Source product and lot'), 'position-a')
  await user.selectOptions(screen.getByLabelText('Destination receiving location'), 'destination-location')
  await user.type(screen.getByLabelText('Quantity (EA)'), '4')
  await user.selectOptions(screen.getByLabelText('Destination store'), alternateDestination.id)
  expect(screen.getByLabelText('Source product and lot')).toHaveValue('position-a')
  expect(screen.getByLabelText('Quantity (EA)')).toHaveValue(4)
  expect(screen.getByLabelText('Destination receiving location')).toHaveValue('')
  expect(screen.getByLabelText('Reason for transfer')).toHaveValue('Preserve this reason')
  expect(screen.getByLabelText('Evidence reference')).toHaveValue('case:preserve-evidence')
})

it('excludes the source, archived stores and stores missing required transfer access from selection', async () => {
  const activeSource = store('source', ['REQUEST_TRANSFER', 'READ_INVENTORY'])
  const noInventoryRead = store('no-inventory-read', ['REQUEST_TRANSFER'])
  const archivedSource = { ...store('archived-source', ['REQUEST_TRANSFER', 'READ_INVENTORY']), active: false }
  const activeDestination = store('destination', ['READ_STORE'])
  const noStoreRead = store('no-store-read', [])
  const archivedDestination = { ...store('archived-destination', ['READ_STORE']), active: false }
  apiMocks.stores.mockResolvedValue({ items: [activeSource, noInventoryRead, archivedSource, activeDestination, noStoreRead, archivedDestination], hasMore: false })
  renderWithTenant(<CreateTransferPanel organizationId={orgId} administrator onCommand={vi.fn()} busy={false} />)
  await userEvent.setup().click(screen.getByRole('button', { name: 'New transfer request' }))
  await userEvent.setup().selectOptions(screen.getByLabelText('Source store'), activeSource.id)

  const destinationSelect = screen.getByLabelText('Destination store')
  expect(within(destinationSelect).getByRole('option', { name: /DESTINATION · destination/ })).toBeInTheDocument()
  expect(within(destinationSelect).queryByRole('option', { name: /SOURCE · source/ })).not.toBeInTheDocument()
  expect(within(destinationSelect).queryByRole('option', { name: /NO-STORE-READ/ })).not.toBeInTheDocument()
  expect(within(destinationSelect).queryByRole('option', { name: /ARCHIVED-DESTINATION/ })).not.toBeInTheDocument()

  const sourceSelect = screen.getByLabelText('Source store')
  expect(within(sourceSelect).queryByRole('option', { name: /NO-INVENTORY-READ/ })).not.toBeInTheDocument()
  expect(within(sourceSelect).queryByRole('option', { name: /ARCHIVED-SOURCE/ })).not.toBeInTheDocument()
})

it('keeps a failed source search retry available without discarding its term', async () => {
  const user = userEvent.setup()
  const source = store('source', ['REQUEST_TRANSFER', 'READ_INVENTORY'])
  let fail = true
  apiMocks.stores.mockImplementation(async (_org: string, _state: string, query: string, _offset: number, _signal: AbortSignal, limit?: number) => {
    if (limit === 100) return { items: [source], hasMore: false }
    if (query === 'temporary failure' && fail) { fail = false; throw new Error('store search temporarily unavailable') }
    return query === 'temporary failure' ? { items: [source], hasMore: false } : { items: [source], hasMore: false }
  })
  renderWithTenant(<CreateTransferPanel organizationId={orgId} administrator onCommand={vi.fn()} busy={false} />)
  await user.click(screen.getByRole('button', { name: 'New transfer request' }))
  const search = screen.getByRole('searchbox', { name: 'Find a source store' })
  await user.type(search, 'temporary failure')
  const retry = await screen.findByRole('button', { name: 'Retry source stores' })
  expect(screen.getByRole('searchbox', { name: 'Find a source store' })).toHaveValue('temporary failure')
  await user.click(retry)
  expect(await screen.findByRole('option', { name: /SOURCE · source/ })).toBeInTheDocument()
})

it('surfaces a stale destination position and requires a fresh version before receipt', async () => {
  apiMocks.positions.mockRejectedValueOnce(new ApiError(new Response(null, { status: 409 }), { code: 'INVENTORY_CONFLICT' } as never))
  const transfer = transferFixture()
  const source = store('source', ['READ_TRANSFERS'])
  const destination = store('destination', ['READ_TRANSFERS', 'READ_INVENTORY', 'RECEIVE_STOCK'])
  apiMocks.capabilities.mockResolvedValueOnce({ sourceActions: ['READ_STORE', 'READ_TRANSFERS'], destinationActions: ['READ_STORE', 'READ_TRANSFERS', 'READ_INVENTORY', 'RECEIVE_STOCK'] })
  renderWithTenant(<TransferDetailView organizationId={orgId} transfer={transfer} stores={[source, destination]} onCommand={vi.fn()} busy={false} onRefresh={async () => undefined} />)

  expect(await screen.findByRole('alert')).toHaveTextContent('stock position changed since it was reviewed')
  expect(screen.getByRole('button', { name: 'Record 2 physical units received' })).toBeDisabled()
  expect(screen.queryByRole('form', { name: 'Request physical return' })).not.toBeInTheDocument()
})

it('shows authorized archived participant labels on a refreshed transfer detail', async () => {
  const transfer = { ...transferFixture(), historicalParticipants: {
    source: { id: 'source', code: 'NORTH', name: 'North depot', active: false },
    destination: { id: 'destination', code: 'SOUTH', name: 'South depot', active: false },
  } } as Transfer
  apiMocks.capabilities.mockResolvedValueOnce({ sourceActions: [], destinationActions: [],
    canReceivePhysicalSettlement: false, canConfirmPhysicalReturn: false, historicalSettlementActions: [] })
  renderWithTenant(<TransferDetailView organizationId={orgId} transfer={transfer} stores={[]} onCommand={vi.fn()} busy={false} onRefresh={async () => undefined} />)

  expect(await screen.findByText(/NORTH · North depot/)).toBeInTheDocument()
  expect(screen.getByText(/SOUTH · South depot/)).toBeInTheDocument()
  expect(screen.getAllByText('Archived')).toHaveLength(2)
  expect(screen.queryByText('Source source')).not.toBeInTheDocument()
  expect(screen.queryByText('Destination destination')).not.toBeInTheDocument()
  expect(screen.queryByRole('form', { name: 'Request physical return' })).not.toBeInTheDocument()
})

it('shows and submits only the historical actions explicitly returned for an archived destination', async () => {
  const user = userEvent.setup()
  const transfer = transferFixture()
  const source = store('source', ['READ_TRANSFERS'])
  const destination = store('destination', ['READ_TRANSFERS'])
  const onCommand = vi.fn().mockResolvedValue(undefined)
  apiMocks.capabilities.mockResolvedValueOnce({ sourceActions: ['READ_STORE', 'READ_TRANSFERS'], destinationActions: ['READ_STORE', 'READ_TRANSFERS'],
    canReceivePhysicalSettlement: false, canConfirmPhysicalReturn: false,
    historicalSettlementActions: ['REQUEST_RETURN', 'CONFIRM_LOSS', 'OPEN_DISCREPANCY'] })
  apiMocks.activeReturns.mockResolvedValueOnce({ items: [] })
  renderWithTenant(<TransferDetailView organizationId={orgId} transfer={transfer} stores={[source, destination]} onCommand={onCommand} busy={false} onRefresh={async () => undefined} />)

  expect(await screen.findByRole('note', { name: 'Administrative historical settlement' })).toHaveTextContent('does not reactivate either store')
  const request = await screen.findByRole('form', { name: 'Request physical return' })
  expect(await screen.findByRole('form', { name: 'Resolve loss or damage' })).toBeInTheDocument()
  expect(await screen.findByRole('form', { name: 'Open a custody discrepancy' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Confirm reconciliation' })).not.toBeInTheDocument()
  await user.type(within(request).getByLabelText('Return request reference'), 'legacy-return-request')
  await user.type(within(request).getByLabelText('Reason for return request'), 'Documented historical custody review')
  await user.type(within(request).getByLabelText('Evidence reference'), 'carrier:legacy-return-request')
  await user.click(within(request).getByRole('checkbox', { name: /I understand this request records intent only/ }))
  await user.click(within(request).getByRole('button', { name: 'Record return request' }))

  await waitFor(() => expect(onCommand).toHaveBeenCalledTimes(1))
  expect(onCommand.mock.calls[0]![0]).toMatchObject({ operation: 'REQUEST_RETURN', transferId: transfer.id,
    body: { expectedVersion: transfer.version, lines: [{ lineId: 'line-1' }] } })
})

it('keeps historical settlement actions unavailable until active returns are verified', async () => {
  const transfer = transferFixture()
  const source = store('source', ['READ_TRANSFERS'])
  const destination = store('destination', ['READ_TRANSFERS'])
  apiMocks.activeReturns.mockRejectedValueOnce(new Error('return verification unavailable'))
  apiMocks.capabilities.mockResolvedValueOnce({ sourceActions: ['READ_TRANSFERS'], destinationActions: ['READ_TRANSFERS'],
    canReceivePhysicalSettlement: false, canConfirmPhysicalReturn: false, historicalSettlementActions: ['REQUEST_RETURN', 'CONFIRM_LOSS', 'OPEN_DISCREPANCY'] })
  renderWithTenant(<TransferDetailView organizationId={orgId} transfer={transfer} stores={[source, destination]} onCommand={vi.fn()} busy={false} onRefresh={async () => undefined} />)

  expect(await screen.findByRole('alert')).toHaveTextContent('physical return state could not be verified')
  expect(screen.queryByRole('form', { name: 'Request physical return' })).not.toBeInTheDocument()
  expect(screen.queryByRole('form', { name: 'Resolve loss or damage' })).not.toBeInTheDocument()
  expect(screen.queryByRole('form', { name: 'Open a custody discrepancy' })).not.toBeInTheDocument()
})

it('shows and submits historical close only when the server grants the reconciled transfer capability', async () => {
  const user = userEvent.setup()
  const fixture = transferFixture('RECONCILIATION_REQUIRED')
  const transfer = { ...fixture, outstandingQuantity: 0, lines: [{ ...fixture.lines[0]!,
    quantities: { ...fixture.lines[0]!.quantities, physicallyReturned: 2, outstanding: 0 } }] }
  const source = store('source', ['READ_TRANSFERS'])
  const destination = store('destination', ['READ_TRANSFERS'])
  const onCommand = vi.fn().mockResolvedValue(undefined)
  apiMocks.activeReturns.mockResolvedValueOnce({ items: [] })
  apiMocks.capabilities.mockResolvedValueOnce({ sourceActions: ['READ_STORE', 'READ_TRANSFERS'],
    destinationActions: ['READ_STORE', 'READ_TRANSFERS'], canReceivePhysicalSettlement: false,
    canConfirmPhysicalReturn: false, historicalSettlementActions: ['CLOSE'] })
  renderWithTenant(<TransferDetailView organizationId={orgId} transfer={transfer} stores={[source, destination]}
    onCommand={onCommand} busy={false} onRefresh={async () => undefined} />)

  expect(await screen.findByRole('note', { name: 'Administrative historical settlement' })).toBeInTheDocument()
  const close = await screen.findByRole('form', { name: 'Confirm final reconciliation' })
  await user.type(within(close).getByLabelText('Reason'), 'Verified all historical custody is settled')
  await user.type(within(close).getByLabelText('Evidence reference'), 'reconciliation:legacy-transfer')
  await user.click(within(close).getByRole('checkbox'))
  await user.click(within(close).getByRole('button', { name: 'Confirm reconciliation' }))

  await waitFor(() => expect(onCommand).toHaveBeenCalledTimes(1))
  expect(onCommand.mock.calls[0]![0]).toMatchObject({ operation: 'CLOSE', transferId: transfer.id,
    body: { expectedVersion: transfer.version, evidenceReference: 'reconciliation:legacy-transfer' } })
})

it('uses the narrow historical settlement capability and reviewed version for an archived destination receipt', async () => {
  const user = userEvent.setup()
  const transfer = transferFixture()
  const source = store('source', ['READ_TRANSFERS'])
  const destination = store('destination', ['READ_TRANSFERS'])
  const onCommand = vi.fn().mockResolvedValue(undefined)
  apiMocks.capabilities.mockResolvedValueOnce({ sourceActions: ['READ_STORE', 'READ_TRANSFERS'], destinationActions: ['READ_STORE', 'READ_TRANSFERS'],
    canReceivePhysicalSettlement: true, canConfirmPhysicalReturn: false })
  renderWithTenant(<TransferDetailView organizationId={orgId} transfer={transfer} stores={[source, destination]} onCommand={onCommand} busy={false} onRefresh={async () => undefined} />)

  const form = await screen.findByRole('form', { name: 'Receive line 1' })
  expect(apiMocks.settlementPositions).toHaveBeenCalledWith(orgId, transfer.id, expect.any(AbortSignal))
  expect(await within(form).findByText(/Destination position version: 0/)).toBeInTheDocument()
  await user.type(within(form).getByLabelText('Receiving document reference'), 'legacy-receipt-1')
  await user.type(within(form).getByLabelText('Document line reference'), 'legacy-line-1')
  await user.type(within(form).getByLabelText('Reason for receipt'), 'Verified legacy receipt')
  await user.type(within(form).getByLabelText('Evidence reference'), 'legacy:receipt-1')
  await user.click(within(form).getByRole('checkbox'))
  await user.click(within(form).getByRole('button', { name: 'Record 2 physical units received' }))
  await waitFor(() => expect(onCommand).toHaveBeenCalledTimes(1))
  expect(onCommand.mock.calls[0]![0]).toMatchObject({ operation: 'RECEIVE', transferId: transfer.id,
    body: { lines: [{ lineId: 'line-1', expectedDestinationVersion: 0 }] } })
  expect(onCommand.mock.calls[0]![0].body).not.toHaveProperty('physicallyReceivedAt')
})

it('uses the narrow historical settlement capability and reviewed version for an archived source return', async () => {
  const user = userEvent.setup()
  const transfer = transferFixture('RECONCILIATION_REQUIRED')
  const source = store('source', ['READ_TRANSFERS'])
  const destination = store('destination', ['READ_TRANSFERS'])
  const onCommand = vi.fn().mockResolvedValue(undefined)
  apiMocks.activeReturns.mockResolvedValue({ items: [{ transferId: transfer.id, lineId: 'line-1', evidenceId: 'return-evidence-1',
    requestId: 'return-command-1', requestReference: 'RETURN-1', requestedQuantity: 2, settledQuantity: 0, unsettledQuantity: 2,
    state: 'PENDING', transferVersion: transfer.version, requestedAt: '2026-11-20T10:00:00Z' }] })
  apiMocks.capabilities.mockResolvedValueOnce({ sourceActions: ['READ_STORE', 'READ_TRANSFERS'], destinationActions: ['READ_STORE', 'READ_TRANSFERS'],
    canReceivePhysicalSettlement: false, canConfirmPhysicalReturn: true })
  apiMocks.settlementPositions.mockResolvedValueOnce({ sourcePositions: [{ lineId: 'line-1', positionId: 'position-a', version: 13 }],
    destinationPositions: [{ lineId: 'line-1', positionId: null, version: 0 }] })
  renderWithTenant(<TransferDetailView organizationId={orgId} transfer={transfer} stores={[source, destination]} onCommand={onCommand} busy={false} onRefresh={async () => undefined} />)

  const form = await screen.findByRole('form', { name: 'Confirm physical return request RETURN-1' })
  expect(apiMocks.settlementPositions).toHaveBeenCalledWith(orgId, transfer.id, expect.any(AbortSignal))
  expect(await within(form).findByText(/Current source position version: 13/)).toBeInTheDocument()
  await user.type(within(form).getByLabelText('Physical receipt reference'), 'legacy-return-document')
  await user.type(within(form).getByLabelText('Return document line for line 1'), 'legacy-return-line')
  await user.type(within(form).getByLabelText('Reason for physical return'), 'Verified return at archived source')
  await user.type(within(form).getByLabelText('Evidence reference'), 'legacy:source-return')
  await user.click(within(form).getByRole('checkbox', { name: 'I physically received and verified every selected quantity, SKU and lot at the source store.' }))
  await user.click(within(form).getByRole('button', { name: 'Confirm physical return received' }))
  await waitFor(() => expect(onCommand).toHaveBeenCalledTimes(1))
  expect(onCommand.mock.calls[0]![0]).toMatchObject({ operation: 'CONFIRM_RETURN', transferId: transfer.id,
    body: { lines: [{ lineId: 'line-1', expectedSourceVersion: 13 }] } })
})

it('hides return actions from an unauthorized destination and disables an authorized pending submission', async () => {
  const transfer = transferFixture()
  const source = store('source', ['READ_TRANSFERS'])
  const unauthorizedDestination = store('destination', ['READ_TRANSFERS'])
  apiMocks.capabilities.mockResolvedValueOnce({ sourceActions: ['READ_STORE', 'READ_TRANSFERS'], destinationActions: ['READ_STORE', 'READ_TRANSFERS'] })
  const { unmount } = renderWithTenant(<TransferDetailView organizationId={orgId} transfer={transfer} stores={[source, unauthorizedDestination]} onCommand={vi.fn()} busy={false} onRefresh={async () => undefined} />)
  await waitFor(() => expect(apiMocks.activeReturns).toHaveBeenCalled())
  expect(screen.queryByRole('form', { name: 'Request physical return' })).not.toBeInTheDocument()
  unmount()

  const destination = store('destination', ['READ_TRANSFERS', 'REQUEST_TRANSFER_RETURN'])
  apiMocks.capabilities.mockResolvedValueOnce({ sourceActions: ['READ_STORE', 'READ_TRANSFERS'], destinationActions: ['READ_STORE', 'READ_TRANSFERS', 'REQUEST_TRANSFER_RETURN'] })
  renderWithTenant(<TransferDetailView organizationId={orgId} transfer={transfer} stores={[source, destination]} onCommand={vi.fn()} busy={true} onRefresh={async () => undefined} />)
  const request = await screen.findByRole('form', { name: 'Request physical return' })
  expect(within(request).getByText(/2 EA outstanding/)).toBeInTheDocument()
  expect(within(request).getByRole('button', { name: 'Saving return request…' })).toBeDisabled()
})

it('shows cancellation only when the backend authorizes the current transfer state', async () => {
  const transfer = { ...transferFixture('REQUESTED'), canCancel: true }
  const source = store('source', ['READ_TRANSFERS', 'REQUEST_TRANSFER'])
  const destination = store('destination', ['READ_TRANSFERS'])
  renderWithTenant(<TransferDetailView organizationId={orgId} transfer={transfer} stores={[source, destination]} onCommand={vi.fn()} busy={false} onRefresh={async () => undefined} />)
  expect(await screen.findByRole('form', { name: 'Cancel transfer before dispatch' })).toBeInTheDocument()
})

it('shows the current active return to the source receiver and blocks changes while a command is pending', async () => {
  const transfer = transferFixture('RECONCILIATION_REQUIRED')
  const source = store('source', ['READ_TRANSFERS', 'READ_INVENTORY', 'RECEIVE_STOCK'])
  const destination = store('destination', ['READ_TRANSFERS'])
  apiMocks.activeReturns.mockResolvedValue({ items: [{ transferId: transfer.id, lineId: 'line-1', evidenceId: 'return-evidence-1', requestId: 'return-command-1', requestReference: 'RETURN-1', requestedQuantity: 2, settledQuantity: 0, unsettledQuantity: 2, state: 'PENDING', transferVersion: transfer.version, requestedAt: '2026-11-20T10:00:00Z' }] })
  apiMocks.capabilities.mockResolvedValueOnce({ sourceActions: ['READ_STORE', 'READ_TRANSFERS', 'READ_INVENTORY', 'RECEIVE_STOCK'], destinationActions: ['READ_STORE', 'READ_TRANSFERS'] })
  renderWithTenant(<TransferDetailView organizationId={orgId} transfer={transfer} stores={[source, destination]} onCommand={vi.fn()} busy={true} onRefresh={async () => undefined} />)

  const physicalReturn = await screen.findByRole('form', { name: 'Confirm physical return request RETURN-1' })
  expect(screen.queryByRole('form', { name: 'Request physical return' })).not.toBeInTheDocument()
  expect(screen.queryByRole('form', { name: 'Receive line 1' })).not.toBeInTheDocument()
  expect(within(physicalReturn).getByText(/Current source position version: 9/)).toBeInTheDocument()
  expect(within(physicalReturn).getByRole('button', { name: 'Saving physical return…' })).toBeDisabled()
})

it('disables transfer stock actions when the authoritative active-return read fails', async () => {
  const transfer = transferFixture()
  const source = store('source', ['READ_TRANSFERS', 'READ_INVENTORY', 'RECEIVE_STOCK'])
  const destination = store('destination', ['READ_TRANSFERS', 'REQUEST_TRANSFER_RETURN'])
  apiMocks.activeReturns.mockRejectedValue(new ApiError(new Response(null, { status: 503 }), { code: 'SERVICE_UNAVAILABLE' } as never))
  apiMocks.capabilities.mockResolvedValueOnce({ sourceActions: ['READ_STORE', 'READ_TRANSFERS'], destinationActions: ['READ_STORE', 'READ_TRANSFERS', 'RECEIVE_STOCK', 'READ_INVENTORY', 'REQUEST_TRANSFER_RETURN'] })
  renderWithTenant(<TransferDetailView organizationId={orgId} transfer={transfer} stores={[source, destination]} onCommand={vi.fn()} busy={false} onRefresh={async () => undefined} />)

  expect(await screen.findByRole('alert')).toHaveTextContent('Current physical return state could not be verified')
  expect(screen.queryByRole('form', { name: 'Receive line 1' })).not.toBeInTheDocument()
  expect(screen.queryByRole('form', { name: 'Request physical return' })).not.toBeInTheDocument()
})

it('shows custody reconciliation evidence with its actor, state, reference and resolution', async () => {
  const transfer = transferFixture('CLOSED_WITH_DISCREPANCY')
  const source = store('source', ['READ_TRANSFERS'])
  const destination = store('destination', ['READ_TRANSFERS'])
  apiMocks.discrepancies.mockResolvedValue({ items: [{ id: 'reconciliation-1', transferId: transfer.id, lineId: 'line-1',
    type: 'CUSTODY_RECONCILIATION', reason: 'Manager reviewed the completed return', evidenceReference: 'close:case-1',
    expectedState: '{"outstanding":0}', observedState: '{"closed":true}', actorId: 'manager-1', resolutionScope: 'TRANSFER_CLOSED', lineSettled: true, transferClosed: true,
    recordedAt: '2026-11-20T12:00:00Z' }], hasMore: false })
  renderWithTenant(<TransferDetailView organizationId={orgId} transfer={transfer} stores={[source, destination]} onCommand={vi.fn()} busy={false} onRefresh={async () => undefined} />)

  const evidence = await screen.findByText(/CUSTODY_RECONCILIATION/)
  expect(evidence).toBeInTheDocument()
  expect(screen.getByText(/Actor manager-1/)).toBeInTheDocument()
  expect(screen.getByText(/Transfer closed/)).toBeInTheDocument()
  expect(screen.getByText(/close:case-1/)).toBeInTheDocument()
})

it('appends the next custody and reconciliation pages without replacing the first page', async () => {
  const user = userEvent.setup()
  const transfer = transferFixture()
  const source = store('source', ['READ_TRANSFERS'])
  const destination = store('destination', ['READ_TRANSFERS'])
  const firstMovement = { id: 'movement-1', lineId: 'line-1', version: 1, type: 'DISPATCH', commandId: 'command-1',
    actorId: 'actor-1', quantity: 2, outstandingBefore: 0, outstandingAfter: 2, stockStoreId: 'source',
    stockMovementId: 'stock-1', stockPositionId: 'position-1', reason: 'First custody entry',
    evidenceReference: 'dispatch:one', recordedAt: '2026-11-20T10:00:00Z' }
  const secondMovement = { ...firstMovement, id: 'movement-2', version: 2, type: 'RECEIPT', reason: 'Second custody entry', recordedAt: '2026-11-20T11:00:00Z' }
  apiMocks.custody.mockImplementation(async (_org: string, _transfer: string, _signal: AbortSignal, cursor: string | null) => ({
    items: [cursor === null ? firstMovement : secondMovement], nextCursor: cursor === null ? 'movement-cursor' : null, hasMore: cursor === null,
  }))
  const firstEvidence = { id: 'evidence-1', transferId: transfer.id, lineId: 'line-1', type: 'DISCREPANCY',
    reason: 'First evidence entry', evidenceReference: 'case:first', expectedState: '{}', observedState: '{}',
    actorId: 'actor-1', resolutionScope: 'OPEN', lineSettled: false, transferClosed: false, recordedAt: '2026-11-20T10:00:00Z' }
  const secondEvidence = { ...firstEvidence, id: 'evidence-2', type: 'CUSTODY_RECONCILIATION', reason: 'Second evidence entry',
    evidenceReference: 'case:second', resolutionScope: 'LINE_SETTLED', lineSettled: true, recordedAt: '2026-11-20T11:00:00Z' }
  apiMocks.discrepancies.mockImplementation(async (_org: string, _transfer: string, _signal: AbortSignal, cursor: string | null) => ({
    items: [cursor === null ? firstEvidence : secondEvidence], nextCursor: cursor === null ? 'evidence-cursor' : null, hasMore: cursor === null,
  }))
  renderWithTenant(<TransferDetailView organizationId={orgId} transfer={transfer} stores={[source, destination]} onCommand={vi.fn()} busy={false} onRefresh={async () => undefined} />)

  expect(await screen.findByText(/First custody entry/)).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Load more custody entries' }))
  expect(await screen.findByText(/Second custody entry/)).toBeInTheDocument()
  expect(screen.getByText(/First custody entry/)).toBeInTheDocument()

  expect(await screen.findByText(/case:first/)).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Load more reconciliation records' }))
  expect(await screen.findByText(/case:second/)).toBeInTheDocument()
  expect(screen.getByText(/case:first/)).toBeInTheDocument()
})
