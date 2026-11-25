import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { Store } from '@/api/stores'
import type { Transfer, TransferCommandIntent, TransferQueueItem } from '@/api/transfers'
import { TenantRequests, TenantScope } from '../organizations/tenant-requests'
import { OrganizationContext } from '../organizations/organization-context'
import { CreateTransferPanel, HistoricalTransfersPage, TransferDetailView, TransfersPage } from './transfers-page'
import { reconcileTransferSummaries } from './transfer-summary-reconciliation'

const apiMocks = vi.hoisted(() => ({
  available: vi.fn(), locations: vi.fn(), stores: vi.fn(), getStore: vi.fn(), product: vi.fn(), lot: vi.fn(), sourceLocation: vi.fn(), lane: vi.fn(), positions: vi.fn(), sourcePosition: vi.fn(),
  custody: vi.fn(), transit: vi.fn(), discrepancies: vi.fn(), activeReturns: vi.fn(), command: vi.fn(),
  capabilities: vi.fn(), settlementPositions: vi.fn(), queue: vi.fn(), historicalQueue: vi.fn(), queueEvidence: vi.fn(), pendingIntents: vi.fn(),
  inspectIntent: vi.fn(), acknowledgeIntent: vi.fn(), prepareIntent: vi.fn(), markIntentSubmitted: vi.fn(), resolveIntent: vi.fn(),
  navigate: vi.fn(),
}))

vi.mock('@tanstack/react-router', () => ({ useParams: () => ({ organizationId: orgId }), useNavigate: () => apiMocks.navigate }))
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
vi.mock('@/api/stores', () => ({ getStore: apiMocks.getStore, listStores: apiMocks.stores }))
vi.mock('@/api/configuration', () => ({ lookupLane: apiMocks.lane }))
vi.mock('@/components/app-link', () => ({ AppLink: ({ href, children, ...props }: React.ComponentProps<'a'>) => <a href={href} {...props}>{children}</a> }))
vi.mock('@/api/transfers', async importOriginal => ({
  ...await importOriginal<typeof import('@/api/transfers')>(),
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
  inspectTransferCommandIntent: apiMocks.inspectIntent,
  acknowledgeTransferCommandIntent: apiMocks.acknowledgeIntent,
  prepareTransferCommandIntent: apiMocks.prepareIntent,
  markTransferCommandIntentSubmitted: apiMocks.markIntentSubmitted,
  resolveFailedTransferCommandIntent: apiMocks.resolveIntent,
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

function renderWithTenant(element: React.ReactNode, queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  return { ...render(<QueryClientProvider client={queryClient}><TenantScope.Provider value={['tenant', orgId, 'actor-1']}><TenantRequests.Provider value={() => new AbortController().signal}>{element}</TenantRequests.Provider></TenantScope.Provider></QueryClientProvider>), queryClient }
}

function renderTransfersWorkspace(role = 'ORG_ADMIN', scope: readonly ['tenant', string, string] = ['tenant', orgId, 'actor-1']) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const organization = { id: orgId, role } as never
  const rendered = render(<QueryClientProvider client={queryClient}>
    <TenantScope.Provider value={scope}><TenantRequests.Provider value={() => new AbortController().signal}><OrganizationContext.Provider value={organization}>
      <TransfersPage />
    </OrganizationContext.Provider></TenantRequests.Provider></TenantScope.Provider>
  </QueryClientProvider>)
  return { ...rendered, queryClient }
}

beforeEach(() => {
  vi.clearAllMocks()
  apiMocks.getStore.mockImplementation(async (_org: string, id: string) => id === 'source' || id.startsWith('source-')
    ? store(id, ['READ_STORE', 'REQUEST_TRANSFER', 'READ_INVENTORY'])
    : store(id, ['READ_STORE']))
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
  apiMocks.inspectIntent.mockResolvedValue({})
  apiMocks.acknowledgeIntent.mockResolvedValue({})
  apiMocks.prepareIntent.mockResolvedValue({ id: 'intent-1' })
  apiMocks.markIntentSubmitted.mockResolvedValue({})
  apiMocks.resolveIntent.mockResolvedValue({})
})

const recoveryReadFailures = [
  { name: '400 validation', status: 400, create: () => new ApiError(new Response(null, { status: 400 }), { code: 'INVALID_REQUEST' } as never) },
  { name: '409 conflict', status: 409, create: () => new ApiError(new Response(null, { status: 409 }), { code: 'INVENTORY_CONFLICT' } as never) },
  { name: '422 validation', status: 422, create: () => new ApiError(new Response(null, { status: 422 }), { code: 'INVALID_REQUEST' } as never) },
  { name: '408 timeout', status: 408, create: () => new ApiError(new Response(null, { status: 408 })) },
  { name: '429 rate limit', status: 429, create: () => new ApiError(new Response(null, { status: 429 }), { code: 'ACCESS_RATE_LIMITED' } as never) },
  { name: '403 access denial', status: 403, create: () => new ApiError(new Response(null, { status: 403 }), { code: 'ACCESS_DENIED' } as never) },
  { name: '404 unavailable', status: 404, create: () => new ApiError(new Response(null, { status: 404 }), { code: 'RESOURCE_NOT_FOUND' } as never) },
  { name: '503 unavailable', status: 503, create: () => new ApiError(new Response(null, { status: 503 }), { code: 'SERVICE_UNAVAILABLE' } as never) },
  { name: 'network failure', status: null, create: () => new TypeError('network disconnected') },
] as const

function savedTransferIntent(state: 'PREPARED' | 'SUBMITTED' = 'PREPARED', id = 'intent-recovery') {
  return { id, operation: 'CLOSE', transferId: 'transfer-recovery',
    requestId: '8529e39b-f6a2-49ca-a29d-73fc208d9863', state,
    preparedAt: '2026-11-20T10:00:00Z', subject: 'Transfer close', redacted: false }
}

function savedTransferInspection(intent: ReturnType<typeof savedTransferIntent>) {
  return { ...intent, executionStatus: intent.state === 'PREPARED' ? 'PREPARED' as const : 'SUBMITTED_AWAITING_OUTCOME' as const,
    payload: { requestId: intent.requestId, expectedVersion: 7 }, committedOutcome: null }
}

async function prepareSingleTransferRequest(user: ReturnType<typeof userEvent.setup>) {
  apiMocks.stores.mockResolvedValue({ items: [
    store('source', ['READ_STORE', 'READ_TRANSFERS', 'REQUEST_TRANSFER', 'READ_INVENTORY']),
    store('destination', ['READ_STORE', 'READ_TRANSFERS']),
  ], hasMore: false })
  await user.click(await screen.findByRole('button', { name: 'New transfer request' }))
  await user.selectOptions(screen.getByLabelText('Source store'), 'source')
  await user.selectOptions(screen.getByLabelText('Destination store'), 'destination')
  await user.selectOptions(await screen.findByLabelText('Source product and lot'), 'position-a')
  await user.selectOptions(screen.getByLabelText('Destination receiving location'), 'destination-location')
  await user.type(screen.getByLabelText('Quantity (EA)'), '4')
  await user.type(screen.getByLabelText('Reason for transfer'), 'Rebalance reviewed stock')
  await user.type(screen.getByLabelText('Evidence reference'), 'plan:recovery-review')
  const submit = screen.getByRole('button', { name: 'Create transfer request' })
  await waitFor(() => expect(submit).toBeEnabled())
  await user.click(submit)
}

function mockTransferCreationStores() {
  apiMocks.stores.mockResolvedValue({ items: [
    store('source', ['READ_STORE', 'READ_TRANSFERS', 'REQUEST_TRANSFER', 'READ_INVENTORY']),
    store('destination', ['READ_STORE', 'READ_TRANSFERS']),
  ], hasMore: false })
}

it.each(recoveryReadFailures)('keeps a PREPARED command recoverable when its $name pending-list read fails', async ({ create, status }) => {
  const user = userEvent.setup()
  const intent = savedTransferIntent()
  const inspection = savedTransferInspection(intent)
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.pendingIntents.mockResolvedValueOnce({ items: [intent] }).mockResolvedValueOnce({ items: [intent] })
    .mockImplementationOnce(async () => { throw create() })
  apiMocks.inspectIntent.mockResolvedValueOnce(inspection)
  renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await user.click(await within(recovery).findByRole('button', { name: 'Retry exact saved command' }))

  await waitFor(() => expect(recovery).toHaveTextContent(status === 403 || status === 404
    ? /request details stay hidden/i : 'Keep the command blocked'))
  expect(within(recovery).getByRole('button', { name: 'Inspect saved outcome' })).toBeEnabled()
  expect(recovery).toHaveTextContent('remains blocked')
  expect(recovery).not.toHaveTextContent(intent.requestId)
  expect(apiMocks.pendingIntents).toHaveBeenCalledTimes(3)
  expect(apiMocks.markIntentSubmitted).not.toHaveBeenCalled()
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.acknowledgeIntent).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
})

it.each(recoveryReadFailures)('does not resolve a SUBMITTED command after its $name inspection read fails', async ({ create, status }) => {
  const user = userEvent.setup()
  const intent = savedTransferIntent('SUBMITTED')
  const inspection = savedTransferInspection(intent)
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.pendingIntents.mockResolvedValue({ items: [intent] })
  apiMocks.inspectIntent.mockResolvedValueOnce(inspection).mockImplementationOnce(async () => { throw create() })
  renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await user.click(await within(recovery).findByRole('button', { name: 'Retry exact saved command' }))

  await waitFor(() => expect(recovery).toHaveTextContent(status === 403 || status === 404
    ? /request details stay hidden/i : 'saved command could not be verified'))
  expect(within(recovery).getByRole('button', { name: 'Inspect saved outcome' })).toBeEnabled()
  expect(apiMocks.pendingIntents).toHaveBeenCalledTimes(3)
  expect(apiMocks.markIntentSubmitted).not.toHaveBeenCalled()
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.acknowledgeIntent).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
  if (status === 403 || status === 404) {
    expect(recovery).toHaveTextContent('details stay hidden')
    expect(recovery).not.toHaveTextContent(intent.requestId)
  }
})

it('keeps a saved command blocked when marking it submitted fails', async () => {
  const user = userEvent.setup()
  const intent = savedTransferIntent()
  const inspection = savedTransferInspection(intent)
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.pendingIntents.mockResolvedValue({ items: [intent] })
  apiMocks.inspectIntent.mockResolvedValue(inspection)
  apiMocks.markIntentSubmitted.mockRejectedValueOnce(new ApiError(new Response(null, { status: 422 }), { code: 'INVALID_REQUEST' } as never))
  renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await user.click(await within(recovery).findByRole('button', { name: 'Retry exact saved command' }))

  await waitFor(() => expect(recovery).toHaveTextContent('could not be marked as submitted'))
  expect(within(recovery).getByRole('button', { name: 'Inspect saved outcome' })).toBeEnabled()
  expect(apiMocks.markIntentSubmitted).toHaveBeenCalledTimes(1)
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.acknowledgeIntent).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
})

it.each([
  { name: '408 timeout', create: () => new ApiError(new Response(null, { status: 408 })) },
  { name: '429 rate limit', create: () => new ApiError(new Response(null, { status: 429 }), { code: 'ACCESS_RATE_LIMITED' } as never) },
])('keeps an exact retry recoverable after execution returns a $name', async ({ create }) => {
  const user = userEvent.setup()
  const intent = savedTransferIntent()
  const inspection = savedTransferInspection(intent)
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.pendingIntents.mockResolvedValue({ items: [intent] })
  apiMocks.inspectIntent.mockResolvedValue(inspection)
  apiMocks.markIntentSubmitted.mockResolvedValue({ id: intent.id, state: 'SUBMITTED', redacted: false })
  apiMocks.command.mockRejectedValueOnce(create())
  renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await user.click(await within(recovery).findByRole('button', { name: 'Retry exact saved command' }))

  await waitFor(() => expect(recovery).toHaveTextContent('may still be processing'))
  expect(recovery).toBeInTheDocument()
  expect(apiMocks.markIntentSubmitted).toHaveBeenCalledTimes(1)
  expect(apiMocks.command).toHaveBeenCalledTimes(1)
  expect(apiMocks.command.mock.calls[0]![1]).toMatchObject({
    operation: 'CLOSE', transferId: intent.transferId,
    body: { requestId: intent.requestId, expectedVersion: 7 },
  })
  expect(apiMocks.acknowledgeIntent).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
})

it('resolves only a supported definitive business rejection from an attempted transfer command', async () => {
  const user = userEvent.setup()
  const intent = savedTransferIntent()
  const inspection = savedTransferInspection(intent)
  const rejected = { ...inspection, state: 'CONFLICTED', executionStatus: 'CONFLICTED' }
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.pendingIntents.mockResolvedValueOnce({ items: [intent] }).mockResolvedValueOnce({ items: [intent] })
    .mockResolvedValueOnce({ items: [intent] }).mockResolvedValueOnce({ items: [] })
  apiMocks.inspectIntent.mockResolvedValueOnce(inspection).mockResolvedValueOnce(inspection).mockResolvedValueOnce(rejected)
  apiMocks.markIntentSubmitted.mockResolvedValue({ id: intent.id, state: 'SUBMITTED', redacted: false })
  apiMocks.command.mockRejectedValueOnce(new ApiError(new Response(null, { status: 409 }), { code: 'INVENTORY_CONFLICT' } as never))
  renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await user.click(await within(recovery).findByRole('button', { name: 'Retry exact saved command' }))

  await waitFor(() => expect(screen.queryByRole('region', { name: 'Recover saved transfer command' })).not.toBeInTheDocument())
  expect(screen.getByText(/saved command conflicted before committing/)).toBeInTheDocument()
  expect(apiMocks.command).toHaveBeenCalledTimes(1)
  expect(apiMocks.resolveIntent).toHaveBeenCalledTimes(1)
  expect(apiMocks.resolveIntent).toHaveBeenCalledWith(orgId, intent.id, 'CONFLICTED', expect.anything())
  expect(apiMocks.acknowledgeIntent).not.toHaveBeenCalled()
})

it('does not mark a committed command failed when acknowledgement is unavailable', async () => {
  const user = userEvent.setup()
  const intent = savedTransferIntent()
  const inspection = savedTransferInspection(intent)
  const committed = { ...inspection, executionStatus: 'COMMITTED_AWAITING_ACKNOWLEDGEMENT',
    committedOutcome: { transferId: intent.transferId, status: 'CLOSED_WITH_DISCREPANCY', version: 8 } }
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.pendingIntents.mockResolvedValue({ items: [intent] })
  apiMocks.inspectIntent.mockResolvedValueOnce(inspection).mockResolvedValueOnce(inspection).mockResolvedValueOnce(committed)
  apiMocks.markIntentSubmitted.mockResolvedValue({ id: intent.id, state: 'SUBMITTED', redacted: false })
  apiMocks.command.mockResolvedValue({ transferId: intent.transferId, status: 'CLOSED_WITH_DISCREPANCY', version: 8 })
  apiMocks.acknowledgeIntent.mockRejectedValueOnce(new ApiError(new Response(null, { status: 409 }), { code: 'INVENTORY_CONFLICT' } as never))
  renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await user.click(await within(recovery).findByRole('button', { name: 'Retry exact saved command' }))

  await waitFor(() => expect(recovery).toHaveTextContent('business result is committed'))
  expect(apiMocks.command).toHaveBeenCalledTimes(1)
  expect(apiMocks.acknowledgeIntent).toHaveBeenCalledTimes(1)
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
})

it('shows a truthful error after a definitive prepare rejection and a fresh empty pending list', async () => {
  const user = userEvent.setup()
  mockTransferCreationStores()
  apiMocks.pendingIntents.mockResolvedValueOnce({ items: [] }).mockResolvedValueOnce({ items: [] })
  apiMocks.prepareIntent.mockRejectedValueOnce(new ApiError(new Response(null, { status: 422 }), { code: 'INVALID_REQUEST' } as never))
  renderTransfersWorkspace()
  await prepareSingleTransferRequest(user)

  expect(await screen.findByRole('alert')).toHaveTextContent('rejected before it could be prepared')
  expect(screen.getByRole('alert')).toHaveTextContent('No stock operation was sent')
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.acknowledgeIntent).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
})

it('recovers the exact server intent after a lost prepare response without sending the stock command', async () => {
  const user = userEvent.setup()
  mockTransferCreationStores()
  const intentId = 'intent-after-lost-prepare'
  let requestId = ''
  apiMocks.pendingIntents.mockResolvedValueOnce({ items: [] }).mockImplementationOnce(async () => ({ items: [{
    id: intentId, operation: 'CREATE', transferId: null, requestId, state: 'PREPARED',
    preparedAt: '2026-11-20T10:00:00Z', subject: 'Transfer request', redacted: false,
  }] }))
  apiMocks.prepareIntent.mockImplementationOnce(async (_organizationId: string, intent: TransferCommandIntent) => {
    requestId = intent.body.requestId
    throw new TypeError('prepare response lost')
  })
  const rendered = renderTransfersWorkspace()
  await prepareSingleTransferRequest(user)

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  expect(recovery).toHaveTextContent('CREATE')
  expect(recovery).toHaveTextContent(requestId)
  expect(rendered.queryClient.getQueryData(['tenant', orgId, 'actor-1', 'transfer-command-intents', 'pending']))
    .toMatchObject({ page: { items: [{ id: intentId, requestId }] } })
  expect(screen.getByRole('button', { name: 'Inspect saved outcome' })).toBeEnabled()
  expect(apiMocks.prepareIntent).toHaveBeenCalledTimes(1)
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
  expect(apiMocks.acknowledgeIntent).not.toHaveBeenCalled()
})

it('keeps the newer pending intent when an older empty prepare-recovery read finishes last', async () => {
  const user = userEvent.setup()
  mockTransferCreationStores()
  const oldRead = Promise.withResolvers<{ items: never[] }>()
  const newIntent = { id: 'intent-newer-read', operation: 'CREATE', transferId: null,
    requestId: '3be09d14-c277-43cf-8f82-227741419b81', state: 'PREPARED',
    preparedAt: '2026-11-20T11:00:00Z', subject: 'Transfer request', redacted: false }
  let calls = 0
  apiMocks.pendingIntents.mockImplementation(async () => {
    calls++
    if (calls === 1) return { items: [] }
    if (calls === 2) return oldRead.promise
    return { items: [newIntent] }
  })
  apiMocks.prepareIntent.mockRejectedValueOnce(new TypeError('prepare response lost'))
  const rendered = renderTransfersWorkspace()
  await prepareSingleTransferRequest(user)
  await waitFor(() => expect(calls).toBe(2))

  const newerQuery = rendered.queryClient.refetchQueries({ queryKey: ['tenant', orgId, 'actor-1', 'transfer-command-intents', 'pending'], exact: true })
  await waitFor(() => expect(calls).toBe(3))
  await newerQuery
  await act(async () => { oldRead.resolve({ items: [] }) })

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  expect(recovery).toHaveTextContent(newIntent.requestId)
  expect(rendered.queryClient.getQueryData(['tenant', orgId, 'actor-1', 'transfer-command-intents', 'pending']))
    .toMatchObject({ page: { items: [{ id: newIntent.id }] } })
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.acknowledgeIntent).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
})

it('does not restore older readable request details after a newer pending read is redacted', async () => {
  const user = userEvent.setup()
  mockTransferCreationStores()
  const oldRead = Promise.withResolvers<{ items: { id: string, operation: string, transferId: string, requestId: string,
    state: string, preparedAt: string, subject: string, redacted: boolean }[] }>()
  let requestId = ''
  let calls = 0
  const redactedIntent = { id: 'intent-now-redacted', operation: null, transferId: null, requestId: null,
    state: 'PREPARED', preparedAt: '2026-11-20T11:00:00Z', subject: 'Saved command', redacted: true }
  apiMocks.pendingIntents.mockImplementation(async () => {
    calls++
    if (calls === 1) return { items: [] }
    if (calls === 2) return oldRead.promise
    return { items: [redactedIntent] }
  })
  apiMocks.prepareIntent.mockImplementationOnce(async (_organizationId: string, intent: TransferCommandIntent) => {
    requestId = intent.body.requestId
    throw new TypeError('prepare response lost')
  })
  const rendered = renderTransfersWorkspace()
  await prepareSingleTransferRequest(user)
  await waitFor(() => expect(calls).toBe(2))

  const newerQuery = rendered.queryClient.refetchQueries({ queryKey: ['tenant', orgId, 'actor-1', 'transfer-command-intents', 'pending'], exact: true })
  await waitFor(() => expect(calls).toBe(3))
  await newerQuery
  await act(async () => { oldRead.resolve({ items: [{ ...redactedIntent, operation: 'CREATE', transferId: 'transfer-private',
    requestId, subject: 'Private request', redacted: false }] }) })

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  expect(recovery).toHaveTextContent(/request details stay hidden/i)
  expect(recovery).not.toHaveTextContent(requestId)
  expect(recovery).not.toHaveTextContent('transfer-private')
  expect(within(recovery).queryByRole('button', { name: 'Retry exact saved command' })).not.toBeInTheDocument()
  expect(rendered.queryClient.getQueryData(['tenant', orgId, 'actor-1', 'transfer-command-intents', 'pending']))
    .toMatchObject({ page: { items: [{ id: redactedIntent.id, redacted: true }] } })
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
})

it('ignores a late rejection from an older pending read after a newer read succeeds', async () => {
  const user = userEvent.setup()
  mockTransferCreationStores()
  const oldRead = Promise.withResolvers<{ items: never[] }>()
  const newIntent = { id: 'intent-after-late-rejection', operation: 'CREATE', transferId: null,
    requestId: '3be09d14-c277-43cf-8f82-227741419b81', state: 'PREPARED',
    preparedAt: '2026-11-20T11:00:00Z', subject: 'Transfer request', redacted: false }
  let calls = 0
  apiMocks.pendingIntents.mockImplementation(async () => {
    calls++
    if (calls === 1) return { items: [] }
    if (calls === 2) return oldRead.promise
    return { items: [newIntent] }
  })
  apiMocks.prepareIntent.mockRejectedValueOnce(new TypeError('prepare response lost'))
  const rendered = renderTransfersWorkspace()
  await prepareSingleTransferRequest(user)
  await waitFor(() => expect(calls).toBe(2))

  const newerQuery = rendered.queryClient.refetchQueries({ queryKey: ['tenant', orgId, 'actor-1', 'transfer-command-intents', 'pending'], exact: true })
  await waitFor(() => expect(calls).toBe(3))
  await newerQuery
  await act(async () => { oldRead.reject(new TypeError('obsolete pending read failed')) })

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  expect(recovery).toHaveTextContent(newIntent.requestId)
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
})

it('keeps a prepare command blocked while a newer pending read fails, then clears its notice after a fresh empty read', async () => {
  const user = userEvent.setup()
  mockTransferCreationStores()
  const oldRead = Promise.withResolvers<{ items: never[] }>()
  const newerRead = Promise.withResolvers<{ items: never[] }>()
  let calls = 0
  apiMocks.pendingIntents.mockImplementation(async () => {
    calls++
    if (calls === 1) return { items: [] }
    if (calls === 2) return oldRead.promise
    if (calls === 3) return newerRead.promise
    return { items: [] }
  })
  apiMocks.prepareIntent.mockRejectedValueOnce(new TypeError('prepare response lost'))
  const rendered = renderTransfersWorkspace()
  await prepareSingleTransferRequest(user)
  await waitFor(() => expect(calls).toBe(2))

  const newerQuery = rendered.queryClient.refetchQueries({ queryKey: ['tenant', orgId, 'actor-1', 'transfer-command-intents', 'pending'], exact: true })
  await waitFor(() => expect(calls).toBe(3))
  await act(async () => { oldRead.resolve({ items: [] }) })
  expect(await screen.findByRole('alert')).toHaveTextContent('current recovery status is not confirmed')
  expect(screen.getByRole('button', { name: 'Check saved commands again' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Check saved commands again' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Checking saved command status' })).toBeDisabled()
  await act(async () => { newerRead.reject(new TypeError('newer pending read failed')) })
  await newerQuery
  await waitFor(() => expect(screen.getByRole('button', { name: 'Check saved commands again' })).toBeEnabled())

  await user.click(screen.getByRole('button', { name: 'Check saved commands again' }))
  await waitFor(() => expect(calls).toBe(4))
  await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
  expect(screen.getByRole('button', { name: 'Create transfer request' })).toBeEnabled()
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
})

it('does not release a terminal intent from an obsolete empty reconciliation after a newer intent appears', async () => {
  const user = userEvent.setup()
  const terminal = savedTransferIntent('SUBMITTED', 'intent-terminal-race')
  const nextIntent = { ...savedTransferIntent('PREPARED', 'intent-next-race'), operation: 'RECEIVE', transferId: 'transfer-next',
    requestId: '3be09d14-c277-43cf-8f82-227741419b81', subject: 'Transfer receipt' }
  const terminalInspection = { ...savedTransferInspection(terminal), executionStatus: 'REJECTED', state: 'REJECTED' }
  const obsoleteReconciliation = Promise.withResolvers<{ items: never[] }>()
  let calls = 0
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.pendingIntents.mockImplementation(async () => {
    calls++
    if (calls === 1 || calls === 2) return { items: [terminal] }
    if (calls === 3) return obsoleteReconciliation.promise
    return { items: [nextIntent] }
  })
  apiMocks.inspectIntent.mockResolvedValueOnce(terminalInspection)
  const rendered = renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await waitFor(() => expect(calls).toBe(3))
  const newerQuery = rendered.queryClient.refetchQueries({ queryKey: ['tenant', orgId, 'actor-1', 'transfer-command-intents', 'pending'], exact: true })
  await waitFor(() => expect(calls).toBe(4))
  await newerQuery
  await act(async () => { obsoleteReconciliation.resolve({ items: [] }) })

  const currentRecovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  expect(currentRecovery).toHaveTextContent(nextIntent.requestId)
  expect(screen.queryByText(/saved command was rejected before committing/)).not.toBeInTheDocument()
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
  expect(apiMocks.acknowledgeIntent).not.toHaveBeenCalled()
})

it('ignores a late error from a canceled React Query pending fetch after explicit recovery succeeds', async () => {
  const user = userEvent.setup()
  const intent = savedTransferIntent()
  const inspection = savedTransferInspection(intent)
  const lateQueryRead = Promise.withResolvers<{ items: typeof intent[] }>()
  let calls = 0
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.pendingIntents.mockImplementation(async () => {
    calls++
    if (calls === 1 || calls === 2) return { items: [intent] }
    if (calls === 3) return lateQueryRead.promise
    return { items: [intent] }
  })
  apiMocks.inspectIntent.mockResolvedValue(inspection)
  const rendered = renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await waitFor(() => expect(recovery).toHaveTextContent('This command was saved but not submitted'))
  const lateQuery = rendered.queryClient.refetchQueries({ queryKey: ['tenant', orgId, 'actor-1', 'transfer-command-intents', 'pending'], exact: true })
  await waitFor(() => expect(calls).toBe(3))

  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await waitFor(() => expect(calls).toBe(4))
  await waitFor(() => expect(apiMocks.inspectIntent).toHaveBeenCalledTimes(2))
  await act(async () => { lateQueryRead.reject(new TypeError('canceled pending query failed late')) })
  await lateQuery

  expect(recovery).toHaveTextContent(intent.requestId)
  expect(recovery).toHaveTextContent('This command was saved but not submitted')
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
  expect(apiMocks.acknowledgeIntent).not.toHaveBeenCalled()
})

it('keeps a newer redacted React Query result authoritative over an older readable fetch', async () => {
  const user = userEvent.setup()
  const intent = savedTransferIntent()
  const inspection = savedTransferInspection(intent)
  const redactedSummary = { ...intent, operation: null, transferId: null, requestId: null, redacted: true }
  const redactedInspection = { ...redactedSummary, executionStatus: 'PREPARED', payload: null, committedOutcome: null }
  const lateQueryRead = Promise.withResolvers<{ items: typeof intent[] }>()
  let calls = 0
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.pendingIntents.mockImplementation(async () => {
    calls++
    if (calls === 1 || calls === 2) return { items: [intent] }
    if (calls === 3) return lateQueryRead.promise
    return { items: [redactedSummary] }
  })
  apiMocks.inspectIntent.mockResolvedValueOnce(inspection).mockResolvedValueOnce(redactedInspection)
  const rendered = renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await waitFor(() => expect(recovery).toHaveTextContent('This command was saved but not submitted'))
  const lateQuery = rendered.queryClient.refetchQueries({ queryKey: ['tenant', orgId, 'actor-1', 'transfer-command-intents', 'pending'], exact: true })
  await waitFor(() => expect(calls).toBe(3))

  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await waitFor(() => expect(calls).toBe(4))
  await waitFor(() => expect(apiMocks.inspectIntent).toHaveBeenCalledTimes(2))
  expect(recovery).toHaveTextContent(/request details stay hidden/i)
  await act(async () => { lateQueryRead.resolve({ items: [intent] }) })
  await lateQuery

  expect(recovery).toHaveTextContent(/request details stay hidden/i)
  expect(recovery).not.toHaveTextContent(intent.requestId)
  expect(within(recovery).queryByRole('button', { name: 'Retry exact saved command' })).not.toBeInTheDocument()
  expect(rendered.queryClient.getQueryData(['tenant', orgId, 'actor-1', 'transfer-command-intents', 'pending']))
    .toMatchObject({ page: { items: [{ id: intent.id, redacted: true }] } })
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
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

it.each(['transit', 'evidence'] as const)('keeps the empty queue and %s retry independent until its read recovers', async kind => {
  const user = userEvent.setup()
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  const pending = Promise.withResolvers<never>()
  const query = kind === 'transit' ? apiMocks.transit : apiMocks.queueEvidence
  query.mockImplementationOnce(() => pending.promise).mockResolvedValueOnce({
    items: kind === 'transit'
      ? [{ transferId: 'transfer-recovered', lineId: 'line-recovered', outstanding: 3, ageSeconds: 60, dispatchedAt: '2026-11-20T10:00:00Z' }]
      : [{ id: 'evidence-recovered', transferId: 'transfer-recovered', type: 'DISCREPANCY', resolutionScope: 'TRANSFER_OPEN', lineSettled: false, transferClosed: false, recordedAt: '2026-11-20T10:00:00Z' }],
    nextCursor: null, hasMore: false,
  })
  renderTransfersWorkspace()

  const section = await screen.findByRole('region', { name: kind === 'transit' ? 'In-transit lines' : 'Transfer evidence history' })
  expect(await within(screen.getByRole('region', { name: 'Transfer queue' })).findByText('No transfers match the selected queue filters.')).toBeInTheDocument()
  expect(within(section).getByRole('status')).toHaveTextContent(kind === 'transit' ? 'Loading outstanding custody lines' : 'Loading reconciliation evidence')
  expect(screen.queryByText('No transfers in this view')).not.toBeInTheDocument()

  await act(async () => { pending.reject(new Error(`${kind} read unavailable`)) })
  expect(await within(section).findByRole('alert')).toHaveTextContent(kind === 'transit' ? 'In-transit records could not be loaded' : 'Transfer evidence could not be loaded')
  const retry = within(section).getByRole('button', { name: kind === 'transit' ? 'Retry in-transit records' : 'Retry evidence history' })
  await user.click(retry)
  if (kind === 'transit') expect(await within(section).findByText(/3 units/)).toBeInTheDocument()
  else expect(await within(section).findByText(/DISCREPANCY/)).toBeInTheDocument()
  expect(query).toHaveBeenCalledTimes(2)
})

it('shows queue errors alongside successful custody and evidence reads', async () => {
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.queue.mockRejectedValue(new Error('queue read failed'))
  apiMocks.transit.mockResolvedValue({ items: [{ transferId: 'transfer-visible', lineId: 'line-visible', outstanding: 2, ageSeconds: 60, dispatchedAt: '2026-11-20T10:00:00Z' }], nextCursor: null, hasMore: false })
  apiMocks.queueEvidence.mockResolvedValue({ items: [{ id: 'evidence-visible', transferId: 'transfer-visible', type: 'DISCREPANCY', resolutionScope: 'TRANSFER_OPEN', lineSettled: false, transferClosed: false, recordedAt: '2026-11-20T10:00:00Z' }], nextCursor: null, hasMore: false })
  renderTransfersWorkspace()

  const queue = await screen.findByRole('region', { name: 'Transfer queue' })
  expect(await within(queue).findByRole('alert')).toHaveTextContent('Transfer queue could not be loaded')
  expect(await screen.findByRole('region', { name: 'In-transit lines' })).toHaveTextContent('2 units')
  expect(await screen.findByRole('region', { name: 'Transfer evidence history' })).toHaveTextContent('DISCREPANCY')
  expect(within(queue).queryByText('No transfers match the selected queue filters.')).not.toBeInTheDocument()
})

it('reports an empty queue, custody list and evidence list only after all reads succeed', async () => {
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  renderTransfersWorkspace()

  expect(await screen.findByText('No transfers match the selected queue filters.')).toBeInTheDocument()
  expect(await screen.findByText('No outstanding in-transit lines are visible for these stores.')).toBeInTheDocument()
  expect(await screen.findByText('No transfer reconciliation evidence is visible for these stores.')).toBeInTheDocument()
  expect(screen.queryByText('No transfers in this view')).not.toBeInTheDocument()
})

it('keeps a queue continuation available when the current page has no visible rows', async () => {
  const user = userEvent.setup()
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.queue.mockImplementationOnce(async () => ({ items: [], nextCursor: 'queue-next', hasMore: true }))
    .mockImplementationOnce(async () => ({ items: [queueSummary({ id: 'transfer-after-empty-page' })], nextCursor: null, hasMore: false }))
  renderTransfersWorkspace()

  const queue = await screen.findByRole('region', { name: 'Transfer queue' })
  expect(await within(queue).findByText('No transfers are visible on this page. More results are available.')).toBeInTheDocument()
  await user.click(within(queue).getByRole('button', { name: 'Load more transfers' }))
  expect(await within(queue).findByText(/transfer-after-empty-page/)).toBeInTheDocument()
  expect(within(queue).queryByText('No transfers match the selected queue filters.')).not.toBeInTheDocument()
})

it('keeps stale queue results visible and labels them when a background refresh fails', async () => {
  const user = userEvent.setup()
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.queue.mockImplementationOnce(async () => ({ items: [queueSummary({ id: 'transfer-stale' })], nextCursor: null, hasMore: false }))
    .mockRejectedValueOnce(new ApiError(new Response(null, { status: 503 }), { code: 'SERVICE_UNAVAILABLE' } as never))
  renderTransfersWorkspace()

  const queue = await screen.findByRole('region', { name: 'Transfer queue' })
  expect(await within(queue).findByText(/transfer-stale/)).toBeInTheDocument()
  await user.click(within(queue).getByRole('button', { name: 'Refresh queue' }))
  expect(await within(queue).findByRole('alert')).toHaveTextContent('Transfer queue could not be loaded')
  expect(within(queue).getByText('Showing previously loaded transfers; the latest refresh failed.')).toBeInTheDocument()
  expect(within(queue).getByText(/transfer-stale/)).toBeInTheDocument()
})

it.each((['queue', 'transit', 'evidence'] as const).flatMap(dataset => [403, 404].map(status => ({ dataset, status }))))(
  'hides cached transfer, custody and evidence records after a $status denial in the $dataset read until fresh access succeeds', async ({ dataset, status }) => {
    const user = userEvent.setup()
    const freshStoreRead = Promise.withResolvers<{ items: Store[], hasMore: false }>()
    let workspaceStoreReads = 0
    apiMocks.stores.mockImplementation(async (_org, _status, _search, _offset, _signal, pageSize) => {
      if (pageSize !== undefined) return { items: [store('source', ['READ_TRANSFERS'])], hasMore: false }
      workspaceStoreReads++
      return workspaceStoreReads === 1 ? { items: [store('source', ['READ_TRANSFERS'])], hasMore: false } : freshStoreRead.promise
    })
    const staleTransfer = queueSummary({ id: 'transfer-cached-before-denial' })
    const freshTransfer = queueSummary({ id: 'transfer-fresh-after-denial', status: 'PARTIALLY_RECEIVED', version: 5 })
    const staleTransit = { transferId: staleTransfer.id, lineId: 'stale-line', outstanding: 3, ageSeconds: 120, dispatchedAt: '2026-11-20T10:00:00Z' }
    const freshTransit = { transferId: freshTransfer.id, lineId: 'fresh-line', outstanding: 2, ageSeconds: 60, dispatchedAt: '2026-11-20T10:00:00Z' }
    const staleEvidence = { id: 'stale-evidence-record', transferId: staleTransfer.id, type: 'STALE_EVIDENCE', resolutionScope: 'TRANSFER_OPEN', lineSettled: false, transferClosed: false, recordedAt: '2026-11-20T10:00:00Z' }
    const freshEvidence = { id: 'fresh-evidence-record', transferId: freshTransfer.id, type: 'FRESH_EVIDENCE', resolutionScope: 'TRANSFER_OPEN', lineSettled: false, transferClosed: false, recordedAt: '2026-11-20T11:00:00Z' }
    const denied = new ApiError(new Response(null, { status }), { code: status === 403 ? 'FORBIDDEN' : 'NOT_FOUND' } as never)
    let denyNextSelectedRead = false
    let accessRevalidationStarted = false
    const selectedRead = dataset === 'queue' ? apiMocks.queue : dataset === 'transit' ? apiMocks.transit : apiMocks.queueEvidence
    apiMocks.queue.mockImplementation(async () => ({ items: accessRevalidationStarted ? [freshTransfer] : [staleTransfer], nextCursor: null, hasMore: false }))
    apiMocks.transit.mockImplementation(async () => ({ items: accessRevalidationStarted ? [freshTransit] : [staleTransit], nextCursor: null, hasMore: false }))
    apiMocks.queueEvidence.mockImplementation(async () => ({ items: accessRevalidationStarted ? [freshEvidence] : [staleEvidence], nextCursor: null, hasMore: false }))
    selectedRead.mockImplementation(async () => {
      if (denyNextSelectedRead) {
        denyNextSelectedRead = false
        accessRevalidationStarted = true
        throw denied
      }
      return dataset === 'queue' ? { items: accessRevalidationStarted ? [freshTransfer] : [staleTransfer], nextCursor: null, hasMore: false }
        : dataset === 'transit' ? { items: accessRevalidationStarted ? [freshTransit] : [staleTransit], nextCursor: null, hasMore: false }
          : { items: accessRevalidationStarted ? [freshEvidence] : [staleEvidence], nextCursor: null, hasMore: false }
    })
    const rendered = renderTransfersWorkspace()

    expect(await screen.findByText(/transfer-cached-before-denial/)).toBeInTheDocument()
    expect(await screen.findByText(/stale-li/)).toBeInTheDocument()
    expect(await screen.findByText(/STALE_EVIDENCE/)).toBeInTheDocument()
    denyNextSelectedRead = true
    if (dataset === 'queue') {
      await user.click(within(screen.getByRole('region', { name: 'Transfer queue' })).getByRole('button', { name: 'Refresh queue' }))
    } else {
      await act(async () => {
        await rendered.queryClient.refetchQueries({ queryKey: ['tenant', orgId, 'actor-1', 'transfers', dataset === 'transit' ? 'in-transit' : 'evidence'], type: 'active' })
      })
    }

    expect(await screen.findByText(/Previously loaded transfer, custody and evidence records are hidden/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry transfer access' })).toBeInTheDocument()
    expect(screen.queryByText(/transfer-cached-before-denial/)).not.toBeInTheDocument()
    expect(screen.queryByText(/stale-li/)).not.toBeInTheDocument()
    expect(screen.queryByText(/STALE_EVIDENCE/)).not.toBeInTheDocument()
    expect(screen.getByLabelText('Find a store')).toBeInTheDocument()

    await act(async () => { freshStoreRead.resolve({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false }) })
    expect(await screen.findByText(/transfer-fresh-after-denial/)).toBeInTheDocument()
    expect(await screen.findByText(/fresh-li/)).toBeInTheDocument()
    expect(await screen.findByText(/FRESH_EVIDENCE/)).toBeInTheDocument()
    expect(screen.queryByText(/transfer-cached-before-denial/)).not.toBeInTheDocument()
    expect(screen.queryByText(/stale-li/)).not.toBeInTheDocument()
    expect(screen.queryByText(/STALE_EVIDENCE/)).not.toBeInTheDocument()
  })

it('does not show a late queue page after the operator changes the store filter', async () => {
  const user = userEvent.setup()
  apiMocks.stores.mockResolvedValue({ items: [store('A', ['READ_TRANSFERS']), store('B', ['READ_TRANSFERS'])], hasMore: false })
  const lateStoreA = Promise.withResolvers<{ items: TransferQueueItem[], nextCursor: null, hasMore: false }>()
  const storeAStarted = Promise.withResolvers<void>()
  apiMocks.queue.mockImplementation(async (_org: string, storeId: string) => {
    if (storeId === 'A') { storeAStarted.resolve(); return lateStoreA.promise }
    return { items: [queueSummary({ id: 'transfer-store-B' })], nextCursor: null, hasMore: false }
  })
  renderTransfersWorkspace()

  await storeAStarted.promise
  await user.selectOptions(await screen.findByLabelText('Store queue'), 'B')
  const queue = await screen.findByRole('region', { name: 'Transfer queue' })
  expect(await within(queue).findByText(/transfer-store-B/)).toBeInTheDocument()
  await act(async () => { lateStoreA.resolve({ items: [queueSummary({ id: 'transfer-late-store-A' })], nextCursor: null, hasMore: false }) })
  expect(within(queue).queryByText(/transfer-late-store-A/)).not.toBeInTheDocument()
  expect(within(queue).getByText(/transfer-store-B/)).toBeInTheDocument()
})

it('discards a late transfer read after the authenticated actor scope changes', async () => {
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  const lateRead = Promise.withResolvers<{ items: TransferQueueItem[], nextCursor: null, hasMore: false }>()
  const firstReadStarted = Promise.withResolvers<void>()
  let calls = 0
  apiMocks.queue.mockImplementation(async () => {
    calls++
    if (calls === 1) { firstReadStarted.resolve(); return lateRead.promise }
    return { items: [queueSummary({ id: 'transfer-actor-two' })], nextCursor: null, hasMore: false }
  })
  const rendered = renderTransfersWorkspace('ORG_ADMIN', ['tenant', orgId, 'actor-1'])
  await firstReadStarted.promise

  rendered.rerender(<QueryClientProvider client={rendered.queryClient}>
    <TenantScope.Provider value={['tenant', orgId, 'actor-2']}><TenantRequests.Provider value={() => new AbortController().signal}><OrganizationContext.Provider value={{ id: orgId, role: 'ORG_ADMIN' } as never}>
      <TransfersPage />
    </OrganizationContext.Provider></TenantRequests.Provider></TenantScope.Provider>
  </QueryClientProvider>)
  const queue = await screen.findByRole('region', { name: 'Transfer queue' })
  expect(await within(queue).findByText(/transfer-actor-two/)).toBeInTheDocument()
  await act(async () => { lateRead.resolve({ items: [queueSummary({ id: 'transfer-actor-one' })], nextCursor: null, hasMore: false }) })
  expect(within(queue).queryByText(/transfer-actor-one/)).not.toBeInTheDocument()
  expect(within(queue).getByText(/transfer-actor-two/)).toBeInTheDocument()
})

it('does not publish a late recovery inspection after the authenticated actor scope changes', async () => {
  const user = userEvent.setup()
  const intent = { id: 'intent-actor-scope', operation: 'CLOSE', transferId: 'transfer-actor-scope',
    requestId: '8529e39b-f6a2-49ca-a29d-73fc208d9863', state: 'PREPARED',
    preparedAt: '2026-11-20T10:00:00Z', subject: 'Transfer close', redacted: false }
  const inspection = { ...intent, executionStatus: 'PREPARED', payload: { requestId: intent.requestId, expectedVersion: 4 }, committedOutcome: null }
  const recoveryListRead = Promise.withResolvers<{ items: typeof intent[] }>()
  const inspectionRead = Promise.withResolvers<typeof inspection>()
  const inspectionStarted = Promise.withResolvers<void>()
  let pendingListCalls = 0
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.pendingIntents.mockImplementation(async () => {
    pendingListCalls++
    if (pendingListCalls === 1) return { items: [intent] }
    if (pendingListCalls === 2) return recoveryListRead.promise
    return { items: [] }
  })
  apiMocks.inspectIntent.mockImplementation(() => {
    inspectionStarted.resolve()
    return inspectionRead.promise
  })
  const rendered = renderTransfersWorkspace('ORG_ADMIN', ['tenant', orgId, 'actor-1'])

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await waitFor(() => expect(pendingListCalls).toBe(2))
  await act(async () => { recoveryListRead.resolve({ items: [intent] }) })
  await inspectionStarted.promise

  rendered.rerender(<QueryClientProvider client={rendered.queryClient}>
    <TenantScope.Provider value={['tenant', orgId, 'actor-2']}><TenantRequests.Provider value={() => new AbortController().signal}><OrganizationContext.Provider value={{ id: orgId, role: 'ORG_ADMIN' } as never}>
      <TransfersPage />
    </OrganizationContext.Provider></TenantRequests.Provider></TenantScope.Provider>
  </QueryClientProvider>)
  await waitFor(() => expect(pendingListCalls).toBe(3))
  await act(async () => { inspectionRead.resolve(inspection) })

  await waitFor(() => expect(screen.queryByRole('region', { name: 'Recover saved transfer command' })).not.toBeInTheDocument())
  expect(screen.queryByText(intent.requestId)).not.toBeInTheDocument()
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.acknowledgeIntent).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
})

it('switches to a different server pending intent while an older inspection is still loading', async () => {
  const user = userEvent.setup()
  const intent = { id: 'intent-being-inspected', operation: 'CLOSE', transferId: 'transfer-old',
    requestId: '8529e39b-f6a2-49ca-a29d-73fc208d9863', state: 'PREPARED',
    preparedAt: '2026-11-20T10:00:00Z', subject: 'Transfer close', redacted: false }
  const nextIntent = { ...intent, id: 'intent-now-pending', operation: 'RECEIVE', transferId: 'transfer-new',
    requestId: '3be09d14-c277-43cf-8f82-227741419b81', subject: 'Transfer receipt' }
  const inspection = { ...intent, executionStatus: 'PREPARED', payload: { requestId: intent.requestId, expectedVersion: 4 }, committedOutcome: null }
  const recoveryListRead = Promise.withResolvers<{ items: typeof intent[] }>()
  const newerPendingRead = Promise.withResolvers<{ items: typeof nextIntent[] }>()
  const inspectionRead = Promise.withResolvers<typeof inspection>()
  const inspectionStarted = Promise.withResolvers<void>()
  let pendingListCalls = 0
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.pendingIntents.mockImplementation(async () => {
    pendingListCalls++
    if (pendingListCalls === 1) return { items: [intent] }
    if (pendingListCalls === 2) return recoveryListRead.promise
    if (pendingListCalls === 3) return newerPendingRead.promise
    return { items: [nextIntent] }
  })
  apiMocks.inspectIntent.mockImplementation(() => {
    inspectionStarted.resolve()
    return inspectionRead.promise
  })
  const rendered = renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await waitFor(() => expect(pendingListCalls).toBe(2))
  await act(async () => { recoveryListRead.resolve({ items: [intent] }) })
  await inspectionStarted.promise

  const newerQuery = rendered.queryClient.refetchQueries({ queryKey: ['tenant', orgId, 'actor-1', 'transfer-command-intents', 'pending'], exact: true })
  await waitFor(() => expect(pendingListCalls).toBe(3))
  await act(async () => { newerPendingRead.resolve({ items: [nextIntent] }) })
  await newerQuery
  await waitFor(() => expect(recovery).toHaveTextContent(nextIntent.requestId))
  await act(async () => { inspectionRead.resolve(inspection) })

  expect(recovery).toHaveTextContent(nextIntent.requestId)
  expect(within(recovery).queryByText(intent.requestId)).not.toBeInTheDocument()
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.acknowledgeIntent).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
})

it('keeps a committed CLOSE distinct from lost acknowledgement and reconciles an already acknowledged intent without another write', async () => {
  const user = userEvent.setup()
  const intent = { id: 'intent-close', operation: 'CLOSE', transferId: 'transfer-close', requestId: '8529e39b-f6a2-49ca-a29d-73fc208d9863', state: 'SUBMITTED', preparedAt: '2026-11-20T10:00:00Z', subject: 'Transfer close', redacted: false }
  const committed = {
    id: intent.id, operation: 'CLOSE', transferId: 'transfer-close', requestId: intent.requestId,
    state: 'SUBMITTED', preparedAt: intent.preparedAt, executionStatus: 'COMMITTED_AWAITING_ACKNOWLEDGEMENT',
    payload: { requestId: intent.requestId, expectedVersion: 9 },
    committedOutcome: { transferId: 'transfer-close', status: 'CLOSED_WITH_DISCREPANCY', version: 10 }, redacted: false,
  }
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS', 'REQUEST_TRANSFER', 'READ_INVENTORY'])], hasMore: false })
  apiMocks.pendingIntents.mockResolvedValueOnce({ items: [intent] }).mockResolvedValueOnce({ items: [intent] })
    .mockResolvedValueOnce({ items: [intent] }).mockResolvedValueOnce({ items: [intent] }).mockResolvedValue({ items: [] })
  apiMocks.inspectIntent.mockResolvedValueOnce(committed).mockResolvedValueOnce(committed).mockResolvedValueOnce({
    ...committed, executionStatus: 'ACKNOWLEDGED', state: 'ACKNOWLEDGED',
  })
  apiMocks.acknowledgeIntent.mockRejectedValueOnce(new Error('acknowledgement response was lost'))
  renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  expect(await within(recovery).findByRole('status')).toHaveTextContent('business result is committed, but acknowledgement remains unconfirmed')
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.prepareIntent).not.toHaveBeenCalled()
  await user.click(await screen.findByRole('button', { name: 'New transfer request' }))
  expect(screen.getByRole('button', { name: 'Resolve saved command first' })).toBeDisabled()

  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await waitFor(() => expect(screen.queryByRole('region', { name: 'Recover saved transfer command' })).not.toBeInTheDocument())
  expect(screen.getByText('The saved command is acknowledged and no longer pending.')).toBeInTheDocument()
  expect(apiMocks.acknowledgeIntent).toHaveBeenCalledTimes(1)
  expect(apiMocks.inspectIntent).toHaveBeenCalledTimes(3)
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(screen.getByRole('button', { name: 'Create transfer request' })).toBeDisabled()
})

it.each([
  { executionStatus: 'REJECTED', redacted: false },
  { executionStatus: 'REJECTED', redacted: true },
  { executionStatus: 'CONFLICTED', redacted: false },
  { executionStatus: 'CONFLICTED', redacted: true },
] as const)('reconciles a cross-session $executionStatus intent from a fresh pending list without repeating a write', async ({ executionStatus, redacted }) => {
  const user = userEvent.setup()
  const intent = { id: `intent-${executionStatus}-${redacted}`, operation: redacted ? null : 'CLOSE',
    transferId: redacted ? null : 'transfer-terminal', requestId: redacted ? null : '8529e39b-f6a2-49ca-a29d-73fc208d9863',
    state: executionStatus, preparedAt: '2026-11-20T10:00:00Z', subject: 'Transfer close', redacted }
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS', 'REQUEST_TRANSFER', 'READ_INVENTORY'])], hasMore: false })
  apiMocks.pendingIntents.mockResolvedValueOnce({ items: [intent] }).mockResolvedValue({ items: [] })
  apiMocks.inspectIntent.mockResolvedValue({
    id: intent.id, operation: redacted ? null : 'CLOSE', transferId: redacted ? null : 'transfer-terminal',
    requestId: redacted ? null : intent.requestId, state: executionStatus, preparedAt: intent.preparedAt,
    executionStatus, payload: redacted ? null : { requestId: intent.requestId, expectedVersion: 9 },
    committedOutcome: null, redacted,
  })
  renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))

  await waitFor(() => expect(screen.queryByRole('region', { name: 'Recover saved transfer command' })).not.toBeInTheDocument())
  expect(await screen.findByRole('status')).toHaveTextContent(executionStatus === 'REJECTED' ? 'rejected' : 'conflicted')
  expect(apiMocks.pendingIntents).toHaveBeenCalledTimes(3)
  expect(apiMocks.acknowledgeIntent).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.prepareIntent).not.toHaveBeenCalled()
})

it('discards previously readable saved-request details when the server redacts the current intent', async () => {
  const user = userEvent.setup()
  const requestId = '8529e39b-f6a2-49ca-a29d-73fc208d9863'
  const prepared = { id: 'intent-access-change', operation: 'CLOSE', transferId: 'transfer-private', requestId,
    state: 'PREPARED', preparedAt: '2026-11-20T10:00:00Z', subject: 'Transfer close', redacted: false }
  const redacted = { ...prepared, operation: null, transferId: null, requestId: null, redacted: true }
  const readableInspection = { ...prepared, executionStatus: 'PREPARED', payload: { requestId, expectedVersion: 7, reason: 'PRIVATE_PAYLOAD_MARKER' }, committedOutcome: null }
  const redactedInspection = { ...redacted, executionStatus: 'PREPARED', payload: null, committedOutcome: null }
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.pendingIntents.mockResolvedValueOnce({ items: [prepared] })
    .mockResolvedValueOnce({ items: [prepared] }).mockResolvedValueOnce({ items: [redacted] })
  apiMocks.inspectIntent.mockResolvedValueOnce(readableInspection).mockResolvedValueOnce(redactedInspection)
  renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  expect(within(recovery).getByText(new RegExp(requestId))).toBeInTheDocument()
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  expect(await within(recovery).findByRole('button', { name: 'Retry exact saved command' })).toBeInTheDocument()
  expect(within(recovery).getByText(new RegExp(requestId))).toBeInTheDocument()

  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  expect(await within(recovery).findByText(/request details stay hidden/i)).toBeInTheDocument()
  expect(within(recovery).queryByText(new RegExp(requestId))).not.toBeInTheDocument()
  expect(within(recovery).queryByText(/PRIVATE_PAYLOAD_MARKER|transfer-private|CLOSE/)).not.toBeInTheDocument()
  expect(within(recovery).queryByRole('button', { name: 'Retry exact saved command' })).not.toBeInTheDocument()
  expect(within(recovery).getByRole('button', { name: 'Resolve without transfer details' })).toBeInTheDocument()
  expect(within(recovery).queryByRole('link')).not.toBeInTheDocument()
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.acknowledgeIntent).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
})

it('keeps a terminal intent locked when its fresh pending read fails, then releases after a successful reconciliation', async () => {
  const user = userEvent.setup()
  const intent = { id: 'intent-terminal-refresh', operation: 'CLOSE', transferId: 'transfer-terminal',
    requestId: '8529e39b-f6a2-49ca-a29d-73fc208d9863', state: 'SUBMITTED',
    preparedAt: '2026-11-20T10:00:00Z', subject: 'Transfer close', redacted: false }
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.pendingIntents.mockResolvedValueOnce({ items: [intent] }).mockResolvedValueOnce({ items: [intent] })
    .mockRejectedValueOnce(new Error('pending intent refresh unavailable'))
    .mockResolvedValueOnce({ items: [intent] }).mockResolvedValueOnce({ items: [] })
  apiMocks.inspectIntent.mockResolvedValue({
    id: intent.id, operation: intent.operation, transferId: intent.transferId, requestId: intent.requestId,
    state: intent.state, preparedAt: intent.preparedAt, executionStatus: 'REJECTED',
    payload: { requestId: intent.requestId, expectedVersion: 7 }, committedOutcome: null, redacted: false,
  })
  renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  expect(await within(recovery).findByRole('status')).toHaveTextContent('Keep the command blocked')
  expect(recovery).toBeInTheDocument()
  expect(within(recovery).getByRole('button', { name: 'Inspect saved outcome' })).toBeEnabled()
  expect(apiMocks.command).not.toHaveBeenCalled()

  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await waitFor(() => expect(screen.queryByRole('region', { name: 'Recover saved transfer command' })).not.toBeInTheDocument())
  expect(screen.getByText(/The saved command was rejected before committing/)).toBeInTheDocument()
  expect(apiMocks.pendingIntents).toHaveBeenCalledTimes(5)
  expect(apiMocks.acknowledgeIntent).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
  expect(apiMocks.command).not.toHaveBeenCalled()
})

it('keeps an exact retry blocked when its result and pending-list refresh both fail', async () => {
  const user = userEvent.setup()
  const intent = { id: 'intent-exact-retry', operation: 'CLOSE', transferId: 'transfer-retry',
    requestId: '8529e39b-f6a2-49ca-a29d-73fc208d9863', state: 'PREPARED',
    preparedAt: '2026-11-20T10:00:00Z', subject: 'Transfer close', redacted: false }
  const inspection = { ...intent, executionStatus: 'PREPARED', payload: { requestId: intent.requestId, expectedVersion: 7 }, committedOutcome: null }
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS', 'REQUEST_TRANSFER', 'READ_INVENTORY'])], hasMore: false })
  apiMocks.pendingIntents.mockResolvedValueOnce({ items: [intent] }).mockResolvedValueOnce({ items: [intent] })
    .mockResolvedValueOnce({ items: [intent] }).mockRejectedValueOnce(new Error('pending refresh unavailable'))
  apiMocks.inspectIntent.mockResolvedValue(inspection)
  apiMocks.markIntentSubmitted.mockResolvedValue({ id: intent.id, state: 'SUBMITTED', redacted: false })
  apiMocks.command.mockRejectedValueOnce(new Error('connection lost after exact command submission'))
  renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await user.click(await within(recovery).findByRole('button', { name: 'Retry exact saved command' }))

  expect(await within(recovery).findByRole('status')).toHaveTextContent('Keep the command blocked')
  expect(recovery).toBeInTheDocument()
  expect(within(recovery).getByRole('button', { name: 'Inspect saved outcome' })).toBeEnabled()
  expect(apiMocks.markIntentSubmitted).toHaveBeenCalledTimes(1)
  expect(apiMocks.command).toHaveBeenCalledTimes(1)
  expect(apiMocks.acknowledgeIntent).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
})

it('keeps a redacted command blocked when safe resolution and its pending refresh both fail', async () => {
  const user = userEvent.setup()
  const intent = { id: 'intent-redacted-resolution', operation: null, transferId: null, requestId: null,
    state: 'PREPARED', preparedAt: '2026-11-20T10:00:00Z', subject: 'Saved command', redacted: true }
  const inspection = { ...intent, executionStatus: 'PREPARED', payload: null, committedOutcome: null }
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.pendingIntents.mockResolvedValueOnce({ items: [intent] }).mockResolvedValueOnce({ items: [intent] })
    .mockResolvedValueOnce({ items: [intent] }).mockRejectedValueOnce(new Error('pending refresh unavailable'))
  apiMocks.inspectIntent.mockResolvedValue(inspection)
  apiMocks.resolveIntent.mockRejectedValueOnce(new Error('safe resolution unavailable'))
  renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await user.click(await within(recovery).findByRole('button', { name: 'Resolve without transfer details' }))

  expect(await within(recovery).findByRole('status')).toHaveTextContent('Keep the command blocked')
  expect(recovery).toBeInTheDocument()
  expect(within(recovery).queryByRole('button', { name: 'Resolve without transfer details' })).not.toBeInTheDocument()
  expect(within(recovery).getByRole('button', { name: 'Inspect saved outcome' })).toBeEnabled()
  expect(apiMocks.resolveIntent).toHaveBeenCalledTimes(1)
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.acknowledgeIntent).not.toHaveBeenCalled()
})

it('reconciles successful redacted resolution after its pending refresh fails without resolving twice', async () => {
  const user = userEvent.setup()
  const intent = { id: 'intent-redacted-resolution-retry', operation: null, transferId: null, requestId: null,
    state: 'PREPARED', preparedAt: '2026-11-20T10:00:00Z', subject: 'Saved command', redacted: true }
  const prepared = { ...intent, executionStatus: 'PREPARED', payload: null, committedOutcome: null }
  const rejected = { ...intent, executionStatus: 'REJECTED', state: 'REJECTED', payload: null, committedOutcome: null }
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.pendingIntents.mockResolvedValueOnce({ items: [intent] }).mockResolvedValueOnce({ items: [intent] })
    .mockResolvedValueOnce({ items: [intent] }).mockRejectedValueOnce(new Error('post-resolution pending refresh unavailable'))
    .mockResolvedValueOnce({ items: [] }).mockResolvedValueOnce({ items: [] })
  apiMocks.inspectIntent.mockResolvedValueOnce(prepared).mockResolvedValueOnce(prepared)
    .mockResolvedValueOnce(rejected).mockResolvedValueOnce(rejected)
  apiMocks.resolveIntent.mockResolvedValueOnce({ id: intent.id, state: 'REJECTED', redacted: true })
  renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await user.click(await within(recovery).findByRole('button', { name: 'Resolve without transfer details' }))
  expect(await within(recovery).findByRole('status')).toHaveTextContent('Keep the command blocked')
  expect(recovery).toBeInTheDocument()
  expect(apiMocks.resolveIntent).toHaveBeenCalledTimes(1)
  expect(apiMocks.command).not.toHaveBeenCalled()

  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await waitFor(() => expect(screen.queryByRole('region', { name: 'Recover saved transfer command' })).not.toBeInTheDocument())
  expect(screen.getByText('The saved command is rejected. Transfer details remain hidden.')).toBeInTheDocument()
  expect(apiMocks.resolveIntent).toHaveBeenCalledTimes(1)
  expect(apiMocks.acknowledgeIntent).not.toHaveBeenCalled()
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.pendingIntents).toHaveBeenCalledTimes(6)
})

it('reconciles an acknowledged result after a failed pending refresh without acknowledging twice', async () => {
  const user = userEvent.setup()
  const intent = { id: 'intent-ack-refresh', operation: 'CLOSE', transferId: 'transfer-ack',
    requestId: '8529e39b-f6a2-49ca-a29d-73fc208d9863', state: 'SUBMITTED',
    preparedAt: '2026-11-20T10:00:00Z', subject: 'Transfer close', redacted: false }
  const committed = { ...intent, executionStatus: 'COMMITTED_AWAITING_ACKNOWLEDGEMENT',
    payload: { requestId: intent.requestId, expectedVersion: 7 },
    committedOutcome: { transferId: intent.transferId, status: 'CLOSED_WITH_DISCREPANCY', version: 8 } }
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS'])], hasMore: false })
  apiMocks.pendingIntents.mockResolvedValueOnce({ items: [intent] }).mockResolvedValueOnce({ items: [intent] })
    .mockRejectedValueOnce(new Error('acknowledged pending refresh unavailable'))
    .mockResolvedValueOnce({ items: [] }).mockResolvedValueOnce({ items: [] })
  apiMocks.inspectIntent.mockResolvedValueOnce(committed).mockResolvedValueOnce({
    ...committed, state: 'ACKNOWLEDGED', executionStatus: 'ACKNOWLEDGED',
  })
  apiMocks.acknowledgeIntent.mockResolvedValueOnce({ id: intent.id, state: 'ACKNOWLEDGED', redacted: false })
  renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  expect(await within(recovery).findByRole('status')).toHaveTextContent('Keep the command blocked')
  expect(recovery).toBeInTheDocument()
  expect(apiMocks.acknowledgeIntent).toHaveBeenCalledTimes(1)
  expect(apiMocks.command).not.toHaveBeenCalled()

  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await waitFor(() => expect(screen.queryByRole('region', { name: 'Recover saved transfer command' })).not.toBeInTheDocument())
  expect(screen.getByText('The saved command is acknowledged and no longer pending.')).toBeInTheDocument()
  expect(apiMocks.acknowledgeIntent).toHaveBeenCalledTimes(1)
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.pendingIntents).toHaveBeenCalledTimes(5)
})

it('acknowledges a redacted committed command and releases the gate only after fresh reconciliation', async () => {
  const user = userEvent.setup()
  const intentId = 'intent-redacted-committed'
  const privateRequestId = 'c0ffee00-0000-4000-8000-000000000001'
  const privateTransferId = 'private-transfer-redacted-committed'
  const privatePayloadMarker = 'PRIVATE_PAYLOAD_REDACTED_COMMITTED'
  const preparedAt = '2026-11-20T10:00:00Z'
  const readableSummary = { id: intentId, operation: 'CLOSE', transferId: privateTransferId, requestId: privateRequestId,
    state: 'SUBMITTED', preparedAt, subject: 'Transfer close', redacted: false }
  const redactedSummary = { ...readableSummary, operation: null, transferId: null, requestId: null, redacted: true }
  const readableInspection = { ...readableSummary, executionStatus: 'SUBMITTED_AWAITING_OUTCOME',
    payload: { requestId: privateRequestId, expectedVersion: 9, reason: privatePayloadMarker }, committedOutcome: null }
  const redactedCommittedInspection = { ...redactedSummary, executionStatus: 'COMMITTED_AWAITING_ACKNOWLEDGEMENT',
    payload: null, committedOutcome: null }
  const acknowledgement = Promise.withResolvers<{ id: string, state: 'ACKNOWLEDGED', redacted: true }>()
  const reconciliation = Promise.withResolvers<{ items: never[] }>()
  let pendingCalls = 0
  apiMocks.stores.mockResolvedValue({ items: [
    store('source', ['READ_TRANSFERS', 'REQUEST_TRANSFER', 'READ_INVENTORY']), store('destination', ['READ_STORE']),
  ], hasMore: false })
  apiMocks.pendingIntents.mockImplementation(async () => {
    pendingCalls++
    if (pendingCalls === 1 || pendingCalls === 2) return { items: [readableSummary] }
    if (pendingCalls === 3 || pendingCalls === 4) return { items: [redactedSummary] }
    if (pendingCalls === 5) return reconciliation.promise
    return { items: [] }
  })
  apiMocks.inspectIntent.mockResolvedValueOnce(readableInspection).mockResolvedValueOnce(redactedCommittedInspection)
  apiMocks.acknowledgeIntent.mockImplementationOnce(() => acknowledgement.promise)
  const rendered = renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(screen.getByRole('button', { name: 'New transfer request' }))
  expect(screen.getByRole('button', { name: 'Resolve saved command first' })).toBeDisabled()
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  expect(await within(recovery).findByRole('button', { name: 'Retry exact saved command' })).toBeInTheDocument()
  expect(recovery).toHaveTextContent(privateRequestId)

  const redactedRefresh = rendered.queryClient.refetchQueries({
    queryKey: ['tenant', orgId, 'actor-1', 'transfer-command-intents', 'pending'], exact: true,
  })
  await redactedRefresh
  await waitFor(() => {
    expect(recovery).toHaveTextContent(/request details stay hidden/i)
    expect(recovery).not.toHaveTextContent(privateRequestId)
    expect(recovery).not.toHaveTextContent(privateTransferId)
    expect(recovery).not.toHaveTextContent('CLOSE')
    expect(recovery).not.toHaveTextContent(privatePayloadMarker)
  })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await waitFor(() => expect(apiMocks.acknowledgeIntent).toHaveBeenCalledTimes(1))
  expect(apiMocks.acknowledgeIntent).toHaveBeenCalledWith(orgId, intentId, expect.anything())
  expect(within(recovery).queryByRole('button', { name: 'Retry exact saved command' })).not.toBeInTheDocument()
  expect(within(recovery).queryByRole('button', { name: 'Resolve without transfer details' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: /Saving request/ })).toBeDisabled()
  expect(recovery).not.toHaveTextContent(privateRequestId)
  expect(recovery).not.toHaveTextContent(privateTransferId)
  expect(recovery).not.toHaveTextContent('CLOSE')
  expect(recovery).not.toHaveTextContent(privatePayloadMarker)
  expect(within(recovery).queryByRole('link')).not.toBeInTheDocument()
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.prepareIntent).not.toHaveBeenCalled()
  expect(apiMocks.markIntentSubmitted).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
  expect(apiMocks.navigate).not.toHaveBeenCalled()

  await act(async () => { acknowledgement.resolve({ id: intentId, state: 'ACKNOWLEDGED', redacted: true }) })
  await waitFor(() => expect(pendingCalls).toBe(5))
  expect(recovery).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /Saving request/ })).toBeDisabled()
  expect(recovery).not.toHaveTextContent(privateRequestId)
  expect(recovery).not.toHaveTextContent(privateTransferId)
  expect(recovery).not.toHaveTextContent('CLOSE')
  expect(recovery).not.toHaveTextContent(privatePayloadMarker)
  expect(apiMocks.acknowledgeIntent).toHaveBeenCalledTimes(1)
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.prepareIntent).not.toHaveBeenCalled()
  expect(apiMocks.markIntentSubmitted).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
  expect(apiMocks.navigate).not.toHaveBeenCalled()

  await act(async () => { reconciliation.resolve({ items: [] }) })
  await waitFor(() => expect(screen.queryByRole('region', { name: 'Recover saved transfer command' })).not.toBeInTheDocument())
  expect(await screen.findByText('The command is acknowledged. Transfer details remain hidden because access changed.')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Resolve saved command first' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Create transfer request' })).toBeDisabled()
  expect(apiMocks.acknowledgeIntent).toHaveBeenCalledTimes(1)
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.prepareIntent).not.toHaveBeenCalled()
  expect(apiMocks.markIntentSubmitted).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
  expect(apiMocks.navigate).not.toHaveBeenCalled()
})

it('keeps same-intent recovery redacted when inspection outruns authorization refresh', async () => {
  const user = userEvent.setup()
  const intentId = 'intent-access-revoked-during-inspection'
  const privateRequestId = 'c0ffee00-0000-4000-8000-000000000002'
  const privateTransferId = 'private-transfer-access-revoked'
  const preparedAt = '2026-11-20T10:00:00Z'
  const readableSummary = { id: intentId, operation: 'CLOSE', transferId: privateTransferId, requestId: privateRequestId,
    state: 'SUBMITTED', preparedAt, subject: 'Transfer close', redacted: false }
  const redactedSummary = { ...readableSummary, operation: null, transferId: null, requestId: null, redacted: true }
  const redactedCommittedInspection = { ...redactedSummary, executionStatus: 'COMMITTED_AWAITING_ACKNOWLEDGEMENT',
    payload: null, committedOutcome: null }
  const firstInspection = Promise.withResolvers<typeof redactedCommittedInspection>()
  const acknowledgement = Promise.withResolvers<{ id: string, state: 'ACKNOWLEDGED', redacted: true }>()
  const reconciliation = Promise.withResolvers<{ items: never[] }>()
  let pendingCalls = 0
  apiMocks.stores.mockResolvedValue({ items: [
    store('source', ['READ_TRANSFERS', 'REQUEST_TRANSFER', 'READ_INVENTORY']), store('destination', ['READ_STORE']),
  ], hasMore: false })
  apiMocks.pendingIntents.mockImplementation(async () => {
    pendingCalls++
    if (pendingCalls === 1 || pendingCalls === 2) return { items: [readableSummary] }
    if (pendingCalls === 3) return { items: [redactedSummary] }
    if (pendingCalls === 4) return reconciliation.promise
    return { items: [] }
  })
  apiMocks.inspectIntent.mockImplementationOnce(() => firstInspection.promise).mockResolvedValueOnce(redactedCommittedInspection)
  apiMocks.acknowledgeIntent.mockImplementationOnce(() => acknowledgement.promise)
  renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  expect(recovery).toHaveTextContent(privateRequestId)
  expect(recovery).toHaveTextContent('CLOSE')
  await user.click(screen.getByRole('button', { name: 'New transfer request' }))
  expect(screen.getByRole('button', { name: 'Resolve saved command first' })).toBeDisabled()

  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await waitFor(() => expect(apiMocks.inspectIntent).toHaveBeenCalledTimes(1))
  await act(async () => { firstInspection.resolve(redactedCommittedInspection) })
  await within(recovery).findByRole('status')
  await waitFor(() => expect(within(recovery).getByRole('button', { name: 'Inspect saved outcome' })).toBeEnabled())
  expect(recovery).toHaveTextContent(/request details stay hidden/i)
  expect(recovery).not.toHaveTextContent(privateRequestId)
  expect(recovery).not.toHaveTextContent(privateTransferId)
  expect(recovery).not.toHaveTextContent('CLOSE')
  expect(within(recovery).queryByRole('button', { name: 'Retry exact saved command' })).not.toBeInTheDocument()
  expect(within(recovery).queryByRole('button', { name: 'Resolve without transfer details' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Resolve saved command first' })).toBeDisabled()
  expect(apiMocks.acknowledgeIntent).not.toHaveBeenCalled()
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.prepareIntent).not.toHaveBeenCalled()
  expect(apiMocks.markIntentSubmitted).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
  expect(apiMocks.navigate).not.toHaveBeenCalled()

  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await waitFor(() => expect(apiMocks.acknowledgeIntent).toHaveBeenCalledTimes(1))
  expect(apiMocks.acknowledgeIntent).toHaveBeenCalledWith(orgId, intentId, expect.anything())
  expect(within(recovery).queryByRole('button', { name: 'Retry exact saved command' })).not.toBeInTheDocument()
  expect(within(recovery).queryByRole('button', { name: 'Resolve without transfer details' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: /Saving request/ })).toBeDisabled()
  expect(recovery).not.toHaveTextContent(privateRequestId)
  expect(recovery).not.toHaveTextContent(privateTransferId)
  expect(recovery).not.toHaveTextContent('CLOSE')
  expect(within(recovery).queryByRole('link')).not.toBeInTheDocument()
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.prepareIntent).not.toHaveBeenCalled()
  expect(apiMocks.markIntentSubmitted).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
  expect(apiMocks.navigate).not.toHaveBeenCalled()

  await act(async () => { acknowledgement.resolve({ id: intentId, state: 'ACKNOWLEDGED', redacted: true }) })
  await waitFor(() => expect(pendingCalls).toBe(4))
  expect(recovery).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /Saving request/ })).toBeDisabled()
  expect(apiMocks.acknowledgeIntent).toHaveBeenCalledTimes(1)
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.prepareIntent).not.toHaveBeenCalled()
  expect(apiMocks.markIntentSubmitted).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
  expect(apiMocks.navigate).not.toHaveBeenCalled()

  await act(async () => { reconciliation.resolve({ items: [] }) })
  await waitFor(() => expect(screen.queryByRole('region', { name: 'Recover saved transfer command' })).not.toBeInTheDocument())
  expect(await screen.findByText('The command is acknowledged. Transfer details remain hidden because access changed.')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Resolve saved command first' })).not.toBeInTheDocument()
  expect(apiMocks.acknowledgeIntent).toHaveBeenCalledTimes(1)
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.prepareIntent).not.toHaveBeenCalled()
  expect(apiMocks.markIntentSubmitted).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
  expect(apiMocks.navigate).not.toHaveBeenCalled()
})

it('reconciles a redacted acknowledgement after a pending refresh fails without acknowledging twice', async () => {
  const user = userEvent.setup()
  const intent = { id: 'intent-redacted-ack-refresh', operation: null, transferId: null, requestId: null,
    state: 'SUBMITTED', preparedAt: '2026-11-20T10:00:00Z', subject: 'Saved command', redacted: true }
  const committed = { ...intent, executionStatus: 'COMMITTED_AWAITING_ACKNOWLEDGEMENT', payload: null, committedOutcome: null }
  const acknowledged = { ...intent, state: 'ACKNOWLEDGED', executionStatus: 'ACKNOWLEDGED', payload: null, committedOutcome: null }
  apiMocks.stores.mockResolvedValue({ items: [store('source', ['READ_TRANSFERS', 'REQUEST_TRANSFER', 'READ_INVENTORY'])], hasMore: false })
  apiMocks.pendingIntents.mockResolvedValueOnce({ items: [intent] }).mockResolvedValueOnce({ items: [intent] })
    .mockRejectedValueOnce(new Error('redacted acknowledgement pending refresh unavailable'))
    .mockResolvedValueOnce({ items: [] }).mockResolvedValueOnce({ items: [] })
  apiMocks.inspectIntent.mockResolvedValueOnce(committed).mockResolvedValueOnce(acknowledged)
  apiMocks.acknowledgeIntent.mockResolvedValueOnce({ id: intent.id, state: 'ACKNOWLEDGED', redacted: true })
  renderTransfersWorkspace()

  const recovery = await screen.findByRole('region', { name: 'Recover saved transfer command' })
  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  expect(await within(recovery).findByRole('status')).toHaveTextContent('Keep the command blocked')
  expect(recovery).toBeInTheDocument()
  expect(recovery).toHaveTextContent(/request details stay hidden/i)
  expect(apiMocks.acknowledgeIntent).toHaveBeenCalledTimes(1)
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.prepareIntent).not.toHaveBeenCalled()
  expect(apiMocks.markIntentSubmitted).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()

  await user.click(within(recovery).getByRole('button', { name: 'Inspect saved outcome' }))
  await waitFor(() => expect(screen.queryByRole('region', { name: 'Recover saved transfer command' })).not.toBeInTheDocument())
  expect(screen.getByText('The saved command is acknowledged. Transfer details remain hidden.')).toBeInTheDocument()
  expect(apiMocks.acknowledgeIntent).toHaveBeenCalledTimes(1)
  expect(apiMocks.command).not.toHaveBeenCalled()
  expect(apiMocks.prepareIntent).not.toHaveBeenCalled()
  expect(apiMocks.markIntentSubmitted).not.toHaveBeenCalled()
  expect(apiMocks.resolveIntent).not.toHaveBeenCalled()
  expect(apiMocks.pendingIntents).toHaveBeenCalledTimes(5)
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
    <TenantScope.Provider value={['tenant', orgId, 'actor-1']}><TenantRequests.Provider value={() => new AbortController().signal}><OrganizationContext.Provider value={organization}>
      <TransfersPage />
    </OrganizationContext.Provider></TenantRequests.Provider></TenantScope.Provider>
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
    <TenantScope.Provider value={['tenant', orgId, 'actor-1']}><TenantRequests.Provider value={() => new AbortController().signal}><OrganizationContext.Provider value={organization}>
      <HistoricalTransfersPage />
    </OrganizationContext.Provider></TenantRequests.Provider></TenantScope.Provider>
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
  expect(await screen.findByText('No transfers match the selected queue filters.')).toBeInTheDocument()
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
    <TenantScope.Provider value={['tenant', orgId, 'actor-1']}><TenantRequests.Provider value={() => new AbortController().signal}><OrganizationContext.Provider value={organization}>
      <TransfersPage />
    </OrganizationContext.Provider></TenantRequests.Provider></TenantScope.Provider>
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

it('revalidates exact source and destination UUIDs found after more than one hundred search matches', async () => {
  const onCommand = vi.fn().mockResolvedValue(undefined)
  const sourceStore = store('source-26', ['REQUEST_TRANSFER', 'READ_INVENTORY'])
  const destinationStore = store('destination-26', ['READ_STORE'])
  const sourceMatches = Array.from({ length: 125 }, (_, index) => store(`source-match-${index}`, ['READ_STORE', 'REQUEST_TRANSFER', 'READ_INVENTORY']))
  const destinationMatches = Array.from({ length: 125 }, (_, index) => store(`destination-match-${index}`, ['READ_STORE']))
  apiMocks.stores.mockImplementation(async (_org: string, _state: string, query: string, offset: number) => {
    if (query === 'Warehouse Alpha') return offset < 125
      ? { items: sourceMatches.slice(offset, offset + 25), hasMore: true }
      : { items: [sourceStore], hasMore: false }
    if (query === 'Warehouse Omega') return offset < 125
      ? { items: destinationMatches.slice(offset, offset + 25), hasMore: true }
      : { items: [destinationStore], hasMore: false }
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
  fireEvent.click(screen.getByRole('button', { name: 'New transfer request' }))
  fireEvent.change(screen.getByLabelText('Find a source store'), { target: { value: 'Warehouse Alpha' } })
  for (let page = 1; page <= 5; page++) {
    fireEvent.click(await screen.findByRole('button', { name: 'Load more source stores' }))
    await waitFor(() => expect(apiMocks.stores).toHaveBeenCalledWith(orgId, 'ACTIVE', 'Warehouse Alpha', page * 25, expect.any(AbortSignal)))
  }
  fireEvent.change(await screen.findByLabelText('Source store'), { target: { value: 'source-26' } })
  fireEvent.change(screen.getByLabelText('Find a destination store'), { target: { value: 'Warehouse Omega' } })
  for (let page = 1; page <= 5; page++) {
    fireEvent.click(await screen.findByRole('button', { name: 'Load more destination stores' }))
    await waitFor(() => expect(apiMocks.stores).toHaveBeenCalledWith(orgId, 'ACTIVE', 'Warehouse Omega', page * 25, expect.any(AbortSignal)))
  }
  fireEvent.change(screen.getByLabelText('Destination store'), { target: { value: 'destination-26' } })
  fireEvent.click(await screen.findByRole('button', { name: 'Load more available source positions' }))
  await waitFor(() => expect(apiMocks.available).toHaveBeenCalledWith(orgId, 'source-26', { offset: 100, limit: 100 }, expect.any(AbortSignal)))
  fireEvent.change(await screen.findByLabelText('Source product and lot'), { target: { value: 'position-101' } })
  fireEvent.click(await screen.findByRole('button', { name: 'Load more destination locations' }))
  await waitFor(() => expect(apiMocks.locations).toHaveBeenCalledWith(orgId, 'destination-26', false, '', 25, expect.any(AbortSignal)))
  fireEvent.change(await screen.findByLabelText('Destination receiving location'), { target: { value: 'destination-location-26' } })
  fireEvent.change(screen.getByLabelText('Quantity (EA)'), { target: { value: '1' } })
  fireEvent.change(screen.getByLabelText('Reason for transfer'), { target: { value: 'Reach stock outside the first page' } })
  fireEvent.change(screen.getByLabelText('Evidence reference'), { target: { value: 'selection:page-26' } })

  await waitFor(() => expect(screen.getByRole('button', { name: 'Create transfer request' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: 'Create transfer request' }))
  await waitFor(() => expect(onCommand).toHaveBeenCalledTimes(1))
  expect(onCommand.mock.calls[0]![0]).toMatchObject({ operation: 'CREATE', body: {
    sourceStoreId: 'source-26', destinationStoreId: 'destination-26',
    lines: [{ sourcePositionId: 'position-101', destinationLocationId: 'destination-location-26', quantity: 1, expectedSourceVersion: 101 }],
  } })
  expect(apiMocks.getStore).toHaveBeenCalledWith(orgId, sourceStore.id, expect.any(AbortSignal))
  expect(apiMocks.getStore).toHaveBeenCalledWith(orgId, destinationStore.id, expect.any(AbortSignal))
  expect(apiMocks.stores.mock.calls.every(call => call[5] !== 100)).toBe(true)
})

it('preserves paginated stock and location selections during same-store revalidation with zero query retention', async () => {
  const user = userEvent.setup()
  const onCommand = vi.fn().mockResolvedValue(undefined)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  const pageOnePosition = { ...sourcePositionA, positionId: 'position-1', productId: 'product-1', lotId: 'lot-1', locationId: 'source-location-1' }
  const page101Position = { ...sourcePositionA, positionId: 'position-101', productId: 'product-101', lotId: 'lot-101', locationId: 'source-location-101', positionVersion: 101 }
  const firstLocation = { ...destinationLocation, id: 'destination-location-1', code: 'DST-1' }
  const page26Location = { ...destinationLocation, id: 'destination-location-26', code: 'DST-26' }
  apiMocks.available.mockImplementation(async (_org: string, _store: string, filters: { offset: number }) => filters.offset === 0
    ? { items: [pageOnePosition], hasMore: true } : { items: [page101Position], hasMore: false })
  apiMocks.locations.mockImplementation(async (_org: string, _store: string, _includeArchived: boolean, _query: string, offset: number) => offset === 0
    ? { items: [firstLocation], hasMore: true } : { items: [page26Location], hasMore: false })
  apiMocks.product.mockImplementation(async (_org: string, productId: string) => ({ id: productId, sku: `SKU-${productId}`, name: `Product ${productId}` }))
  apiMocks.lot.mockImplementation(async (_org: string, lotId: string) => ({ id: lotId, lotCode: `LOT-${lotId}` }))
  apiMocks.sourceLocation.mockImplementation(async (_org: string, _store: string, locationId: string) => ({ ...sourceLocationA, id: locationId, zoneId: 'source-zone' }))
  renderWithTenant(<CreateTransferPanel organizationId={orgId} administrator={false} onCommand={onCommand} busy={false} />, client)
  await user.click(screen.getByRole('button', { name: 'New transfer request' }))
  await user.selectOptions(screen.getByLabelText('Source store'), 'source')
  await user.selectOptions(screen.getByLabelText('Destination store'), 'destination')
  await user.click(await screen.findByRole('button', { name: 'Load more available source positions' }))
  await user.selectOptions(await screen.findByLabelText('Source product and lot'), 'position-101')
  await user.click(await screen.findByRole('button', { name: 'Load more destination locations' }))
  await user.selectOptions(await screen.findByLabelText('Destination receiving location'), 'destination-location-26')
  await user.type(screen.getByLabelText('Quantity (EA)'), '1')
  await user.type(screen.getByLabelText('Reason for transfer'), 'Keep the selected stock page through a permission check')
  await user.type(screen.getByLabelText('Evidence reference'), 'selection:revalidation')

  const sourcePending = Promise.withResolvers<Store>()
  const destinationPending = Promise.withResolvers<Store>()
  const sourceStarted = Promise.withResolvers<void>()
  const destinationStarted = Promise.withResolvers<void>()
  apiMocks.getStore.mockImplementation(async (_org: string, id: string) => {
    if (id === 'source') { sourceStarted.resolve(); return sourcePending.promise }
    if (id === 'destination') { destinationStarted.resolve(); return destinationPending.promise }
    return store(id, ['READ_STORE'])
  })
  const sourceSelectionKey = ['tenant', orgId, 'actor-1', 'transfer-create', 'source-store-selection', 'source']
  const destinationSelectionKey = ['tenant', orgId, 'actor-1', 'transfer-create', 'destination-store-selection', 'destination']
  const positionsKey = ['tenant', orgId, 'actor-1', 'transfer-create', 'positions', 'source']
  const locationsKey = ['tenant', orgId, 'actor-1', 'transfer-create', 'destination-locations', 'destination', '']
  void client.invalidateQueries({ queryKey: sourceSelectionKey, exact: true })
  void client.invalidateQueries({ queryKey: destinationSelectionKey, exact: true })
  await Promise.all([sourceStarted.promise, destinationStarted.promise])
  await waitFor(() => expect(screen.getByRole('button', { name: 'Create transfer request' })).toBeDisabled())
  expect(screen.queryByRole('option', { name: /SKU-product-101/ })).not.toBeInTheDocument()
  expect(screen.queryByRole('option', { name: /DST-26/ })).not.toBeInTheDocument()
  expect(client.getQueryData(positionsKey)).toMatchObject({ pages: [{ items: [{ positionId: 'position-1' }] }, { items: [{ positionId: 'position-101' }] }] })
  expect(client.getQueryData(locationsKey)).toMatchObject({ pages: [{ items: [{ id: 'destination-location-1' }] }, { items: [{ id: 'destination-location-26' }] }] })

  await act(async () => {
    sourcePending.resolve(store('source', ['READ_STORE', 'REQUEST_TRANSFER', 'READ_INVENTORY']))
    destinationPending.resolve(store('destination', ['READ_STORE']))
  })
  await waitFor(() => expect(screen.getByRole('button', { name: 'Create transfer request' })).toBeEnabled())
  expect(screen.getByLabelText('Source product and lot')).toHaveValue('position-101')
  expect(screen.getByLabelText('Destination receiving location')).toHaveValue('destination-location-26')
  expect(screen.getByLabelText('Quantity (EA)')).toHaveValue(1)
  await user.click(screen.getByRole('button', { name: 'Create transfer request' }))
  await waitFor(() => expect(onCommand).toHaveBeenCalledTimes(1))
})

it.each(['source', 'destination'] as const)('keeps a %s store fail-closed during and after a cached refetch error, then restores the intact draft on retry', async target => {
  const user = userEvent.setup()
  const onCommand = vi.fn().mockResolvedValue(undefined)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  const details: Record<string, Store> = {
    source: store('source', ['READ_STORE', 'REQUEST_TRANSFER', 'READ_INVENTORY']),
    destination: store('destination', ['READ_STORE']),
  }
  apiMocks.getStore.mockImplementation(async (_org: string, id: string) => details[id]!)
  renderWithTenant(<CreateTransferPanel organizationId={orgId} administrator onCommand={onCommand} busy={false} />, client)
  await user.click(screen.getByRole('button', { name: 'New transfer request' }))
  await user.selectOptions(screen.getByLabelText('Source store'), 'source')
  await user.selectOptions(screen.getByLabelText('Destination store'), 'destination')
  await user.selectOptions(await screen.findByLabelText('Source product and lot'), 'position-a')
  await user.selectOptions(screen.getByLabelText('Destination receiving location'), 'destination-location')
  await user.type(screen.getByLabelText('Quantity (EA)'), '4')
  await user.type(screen.getByLabelText('Reason for transfer'), 'Move reviewed stock')
  await user.type(screen.getByLabelText('Evidence reference'), 'validation:cached-refetch')

  const createButton = screen.getByRole('button', { name: 'Create transfer request' })
  await waitFor(() => expect(createButton).toBeEnabled())
  const pending = Promise.withResolvers<Store>()
  const started = Promise.withResolvers<void>()
  let holdNextRead = true
  apiMocks.getStore.mockImplementation(async (_org: string, id: string) => {
    if (id === target && holdNextRead) {
      holdNextRead = false
      started.resolve()
      return pending.promise
    }
    return details[id]!
  })
  const selectionKey = ['tenant', orgId, 'actor-1', 'transfer-create', `${target}-store-selection`, target]
  void client.invalidateQueries({ queryKey: selectionKey, exact: true })
  await started.promise
  await waitFor(() => expect(createButton).toBeDisabled())
  expect(client.getQueryData(selectionKey)).toMatchObject({ id: target })
  const dependentKey = target === 'source'
    ? ['tenant', orgId, 'actor-1', 'transfer-create', 'positions', target]
    : ['tenant', orgId, 'actor-1', 'transfer-create', 'destination-locations', target, '']
  expect(client.getQueryData(dependentKey)).toBeDefined()

  await act(async () => {
    pending.reject(new ApiError(new Response(null, { status: 403 }), { code: 'FORBIDDEN' } as never))
  })
  expect(await screen.findByRole('alert')).toHaveTextContent(`selected ${target} store could not be revalidated`)
  await waitFor(() => expect(client.getQueryData(dependentKey)).toBeUndefined())
  expect(client.getQueryData(selectionKey)).toMatchObject({ id: target })
  expect(createButton).toBeDisabled()
  fireEvent.submit(createButton.closest('form')!)
  expect(onCommand).not.toHaveBeenCalled()

  await user.click(screen.getByRole('button', { name: `Retry ${target} access` }))
  await waitFor(() => expect(createButton).toBeEnabled())
  expect(screen.getByLabelText('Quantity (EA)')).toHaveValue(4)
  await user.click(createButton)
  await waitFor(() => expect(onCommand).toHaveBeenCalledTimes(1))
})

it.each(['source', 'destination'] as const)('does not let a late %s response authorize a different selected store', async target => {
  const user = userEvent.setup()
  const onCommand = vi.fn()
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const firstId = target
  const secondId = target === 'source' ? 'source-b' : 'destination-b'
  const details: Record<string, Store> = {
    source: store('source', ['READ_STORE', 'REQUEST_TRANSFER', 'READ_INVENTORY']),
    destination: store('destination', ['READ_STORE']),
    'source-b': store('source-b', ['READ_STORE', 'REQUEST_TRANSFER', 'READ_INVENTORY']),
    'destination-b': store('destination-b', ['READ_STORE']),
  }
  apiMocks.stores.mockResolvedValue({ items: Object.values(details), hasMore: false })
  const firstRead = Promise.withResolvers<Store>()
  const secondRead = Promise.withResolvers<Store>()
  const firstStarted = Promise.withResolvers<void>()
  const secondStarted = Promise.withResolvers<void>()
  apiMocks.getStore.mockImplementation(async (_org: string, id: string) => {
    if (id === firstId) { firstStarted.resolve(); return firstRead.promise }
    if (id === secondId) { secondStarted.resolve(); return secondRead.promise }
    return details[id]!
  })
  renderWithTenant(<CreateTransferPanel organizationId={orgId} administrator onCommand={onCommand} busy={false} />, client)
  await user.click(screen.getByRole('button', { name: 'New transfer request' }))
  if (target === 'source') await user.selectOptions(screen.getByLabelText('Destination store'), 'destination')
  else await user.selectOptions(screen.getByLabelText('Source store'), 'source')
  const selectedStore = screen.getByLabelText(target === 'source' ? 'Source store' : 'Destination store')
  await user.selectOptions(selectedStore, firstId)
  await firstStarted.promise
  await user.selectOptions(selectedStore, secondId)
  await secondStarted.promise

  const createButton = screen.getByRole('button', { name: 'Create transfer request' })
  await waitFor(() => expect(createButton).toBeDisabled())
  await act(async () => { firstRead.resolve(details[firstId]!) })
  await waitFor(() => expect(createButton).toBeDisabled())
  expect(selectedStore).toHaveValue(secondId)
  expect(client.getQueryData(['tenant', orgId, 'actor-1', 'transfer-create', `${target}-store-selection`, secondId])).toBeUndefined()

  await act(async () => {
    secondRead.reject(new ApiError(new Response(null, { status: 403 }), { code: 'FORBIDDEN' } as never))
  })
  expect(await screen.findByRole('alert')).toHaveTextContent(`selected ${target} store could not be revalidated`)
  expect(createButton).toBeDisabled()
  fireEvent.submit(createButton.closest('form')!)
  expect(onCommand).not.toHaveBeenCalled()
})

it.each([
  ['source', 'archived', { ...store('source', ['READ_STORE', 'REQUEST_TRANSFER', 'READ_INVENTORY']), active: false }],
  ['source', 'missing transfer action', store('source', ['READ_STORE', 'READ_INVENTORY'])],
  ['source', 'wrong UUID', store('another-source', ['READ_STORE', 'REQUEST_TRANSFER', 'READ_INVENTORY'])],
  ['destination', 'archived', { ...store('destination', ['READ_STORE']), active: false }],
  ['destination', 'missing store read action', store('destination', [])],
  ['destination', 'wrong UUID', store('another-destination', ['READ_STORE'])],
] as const)('blocks a selected %s returned as %s', async (target, _caseName, responseStore) => {
  const user = userEvent.setup()
  const onCommand = vi.fn()
  apiMocks.getStore.mockImplementation(async (_org: string, id: string) => {
    if (id === target) return responseStore
    return id === 'source' ? store(id, ['READ_STORE', 'REQUEST_TRANSFER', 'READ_INVENTORY']) : store(id, ['READ_STORE'])
  })
  renderWithTenant(<CreateTransferPanel organizationId={orgId} administrator onCommand={onCommand} busy={false} />)
  await user.click(screen.getByRole('button', { name: 'New transfer request' }))
  await user.selectOptions(screen.getByLabelText('Source store'), 'source')
  if (target === 'destination') await user.selectOptions(screen.getByLabelText('Destination store'), 'destination')
  const alertText = target === 'source' ? 'selected source store is no longer active' : 'selected destination store is no longer active'
  expect(await screen.findByRole('alert')).toHaveTextContent(alertText)
  expect(screen.getByRole('button', { name: 'Create transfer request' })).toBeDisabled()
  fireEvent.submit(screen.getByRole('button', { name: 'Create transfer request' }).closest('form')!)
  expect(onCommand).not.toHaveBeenCalled()
})

it.each([403, 404])('keeps a source selection unavailable after the detail endpoint returns %i', async status => {
  const user = userEvent.setup()
  const onCommand = vi.fn()
  apiMocks.getStore.mockImplementation(async (_org: string, id: string) => {
    if (id === 'source') throw new ApiError(new Response(null, { status }), { code: status === 403 ? 'FORBIDDEN' : 'NOT_FOUND' } as never)
    return store(id, ['READ_STORE'])
  })
  renderWithTenant(<CreateTransferPanel organizationId={orgId} administrator onCommand={onCommand} busy={false} />)
  await user.click(screen.getByRole('button', { name: 'New transfer request' }))
  await user.selectOptions(screen.getByLabelText('Source store'), 'source')
  expect(await screen.findByRole('alert')).toHaveTextContent('selected source store could not be revalidated')
  expect(screen.getByRole('button', { name: 'Retry source access' })).toBeEnabled()
  expect(screen.getByRole('button', { name: 'Create transfer request' })).toBeDisabled()
  expect(onCommand).not.toHaveBeenCalled()
})

it('keeps the selected stores and complete draft when source search is refined', async () => {
  const user = userEvent.setup()
  const onCommand = vi.fn().mockResolvedValue(undefined)
  const source = { ...store('source-alpha', ['REQUEST_TRANSFER', 'READ_INVENTORY']), code: 'ALPHA', name: 'Warehouse Alpha' }
  const destination = { ...store('destination-omega', ['READ_STORE']), code: 'OMEGA', name: 'Warehouse Omega' }
  apiMocks.stores.mockImplementation(async (_org: string, _state: string, query: string) => {
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
  expect(apiMocks.getStore).toHaveBeenCalledWith(orgId, source.id, expect.any(AbortSignal))
  expect(apiMocks.getStore).toHaveBeenCalledWith(orgId, destination.id, expect.any(AbortSignal))
  expect(apiMocks.stores.mock.calls.every(call => call[5] !== 100)).toBe(true)
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
    evidenceReference: 'dispatch:one', physicalEventAt: null, recordedAt: '2026-11-20T10:00:00Z' }
  const secondMovement = { ...firstMovement, id: 'movement-2', version: 2, type: 'RECEIPT', reason: 'Second custody entry',
    physicalEventAt: null, recordedAt: '2026-11-20T11:00:00Z' }
  const thirdMovement = { ...secondMovement, id: 'movement-3', version: 3, reason: 'Third custody entry',
    physicalEventAt: '2026-11-20T11:30:00Z', recordedAt: '2026-11-20T12:00:00Z' }
  apiMocks.custody.mockImplementation(async (_org: string, _transfer: string, _signal: AbortSignal, cursor: string | null) => ({
    items: cursor === null ? [firstMovement] : [secondMovement, thirdMovement],
    nextCursor: cursor === null ? 'movement-cursor' : null, hasMore: cursor === null,
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
  expect(await screen.findByText(/Third custody entry/)).toBeInTheDocument()
  expect(screen.getByText(/First custody entry/)).toBeInTheDocument()
  expect(screen.getAllByText('Physical receipt time:')).toHaveLength(2)
  expect(screen.getByText('Not available')).toBeInTheDocument()
  expect(document.querySelectorAll('time[dateTime="2026-11-20T11:30:00Z"]')).toHaveLength(1)
  expect(screen.getAllByText('Recorded at:')).toHaveLength(3)

  expect(await screen.findByText(/case:first/)).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Load more reconciliation records' }))
  expect(await screen.findByText(/case:second/)).toBeInTheDocument()
  expect(screen.getByText(/case:first/)).toBeInTheDocument()
})
