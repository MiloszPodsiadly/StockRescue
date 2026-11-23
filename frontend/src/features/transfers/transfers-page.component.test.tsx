import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { Store } from '@/api/stores'
import type { Transfer } from '@/api/transfers'
import { TenantScope } from '../organizations/tenant-requests'
import { OrganizationContext } from '../organizations/organization-context'
import { CreateTransferPanel, TransferDetailView, TransfersPage } from './transfers-page'

const apiMocks = vi.hoisted(() => ({
  available: vi.fn(), locations: vi.fn(), stores: vi.fn(), product: vi.fn(), lot: vi.fn(), sourceLocation: vi.fn(), lane: vi.fn(), positions: vi.fn(), sourcePosition: vi.fn(),
  custody: vi.fn(), transit: vi.fn(), discrepancies: vi.fn(), activeReturns: vi.fn(), command: vi.fn(),
  capabilities: vi.fn(), settlementPositions: vi.fn(), queue: vi.fn(), queueEvidence: vi.fn(), pendingIntents: vi.fn(),
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

function renderWithTenant(element: React.ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><TenantScope.Provider value={['tenant', orgId, 'actor-1']}>{element}</TenantScope.Provider></QueryClientProvider>)
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
  apiMocks.queueEvidence.mockResolvedValue({ items: [], nextCursor: null, hasMore: false })
  apiMocks.pendingIntents.mockResolvedValue({ items: [] })
  apiMocks.command.mockResolvedValue({ transferId: 'transfer-1', status: 'REQUESTED', version: 1 })
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

it('finds a later store, source position and destination location through server pages', async () => {
  const user = userEvent.setup()
  const onCommand = vi.fn().mockResolvedValue(undefined)
  const sourceStore = store('source-26', ['REQUEST_TRANSFER', 'READ_INVENTORY'])
  const destinationStore = store('destination-26', ['READ_STORE'])
  apiMocks.stores.mockImplementation(async (_org: string, _state: string, query: string) => ({
    items: query === '26' ? [sourceStore, destinationStore] : [], hasMore: false,
  }))
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
  await user.type(screen.getByLabelText('Find a source or destination store'), '26')
  await user.selectOptions(await screen.findByLabelText('Source store'), 'source-26')
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
