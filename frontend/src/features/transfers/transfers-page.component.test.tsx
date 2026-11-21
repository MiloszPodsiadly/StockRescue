import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { Store } from '@/api/stores'
import type { Transfer, TransferDiscrepancyRecord } from '@/api/transfers'
import { TenantScope } from '../organizations/tenant-requests'
import { CreateTransferPanel, TransferDetailView } from './transfers-page'

const apiMocks = vi.hoisted(() => ({
  available: vi.fn(), locations: vi.fn(), products: vi.fn(), lots: vi.fn(), lane: vi.fn(), positions: vi.fn(), sourcePosition: vi.fn(),
  custody: vi.fn(), transit: vi.fn(), discrepancies: vi.fn(), command: vi.fn(),
}))

vi.mock('@/api/inventory', () => ({
  getInventorySnapshot: apiMocks.sourcePosition,
  listInventoryAvailableByLot: apiMocks.available,
  listInventoryPositions: apiMocks.positions,
}))
vi.mock('@/api/storage-locations', () => ({ listStorageLocations: apiMocks.locations }))
vi.mock('@/api/products', () => ({ listProducts: apiMocks.products }))
vi.mock('@/api/lots', () => ({ listLots: apiMocks.lots }))
vi.mock('@/api/configuration', () => ({ lookupLane: apiMocks.lane }))
vi.mock('@/components/app-link', () => ({ AppLink: ({ href, children, ...props }: React.ComponentProps<'a'>) => <a href={href} {...props}>{children}</a> }))
vi.mock('@/api/transfers', () => ({
  executeTransferCommand: apiMocks.command,
  getTransfer: vi.fn(),
  listTransferCustody: apiMocks.custody,
  listTransferDiscrepancies: apiMocks.discrepancies,
  listTransfers: vi.fn(),
  listTransfersInTransit: apiMocks.transit,
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
  apiMocks.available.mockResolvedValue({ items: [sourcePositionA, sourcePositionB], hasMore: false })
  apiMocks.locations.mockImplementation(async (_org: string, storeId: string) => ({
    items: storeId === 'source' ? [sourceLocationA, sourceLocationB] : [destinationLocation], hasMore: false,
  }))
  apiMocks.products.mockResolvedValue({ items: [{ id: 'product-1', sku: 'SKU-1', name: 'Product one' }, { id: 'product-2', sku: 'SKU-2', name: 'Product two' }] })
  apiMocks.lots.mockImplementation(async (_org: string, productId: string) => ({ items: [{ id: productId === 'product-1' ? 'lot-1' : 'lot-2', lotCode: productId === 'product-1' ? 'LOT-1' : 'LOT-2' }] }))
  apiMocks.lane.mockResolvedValue({ status: 'CONFIGURED', lane: { originZoneId: 'source-zone', destinationZoneId: 'destination-zone' } })
  apiMocks.positions.mockResolvedValue({ items: [{ id: 'destination-position', productId: 'product-1', lotId: 'lot-1', locationId: 'destination-location', version: 2 }] })
  apiMocks.sourcePosition.mockResolvedValue({ version: 9, onHand: 12, reserved: 0, available: 12 })
  apiMocks.custody.mockResolvedValue({ items: [], hasMore: false })
  apiMocks.transit.mockResolvedValue({ items: [], hasMore: false })
  apiMocks.discrepancies.mockResolvedValue({ items: [], hasMore: false })
  apiMocks.command.mockResolvedValue({ transferId: 'transfer-1', status: 'REQUESTED', version: 1 })
})

it('edits two transfer lines, blocks a duplicate source position and submits each observed version', async () => {
  const user = userEvent.setup()
  const onCommand = vi.fn().mockResolvedValue(undefined)
  const source = store('source', ['REQUEST_TRANSFER', 'READ_INVENTORY'])
  const destination = store('destination', ['READ_STORE'])
  renderWithTenant(<CreateTransferPanel organizationId={orgId} stores={[source, destination]} administrator onCommand={onCommand} busy={false} />)

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

it('surfaces a stale destination position and requires a fresh version before receipt', async () => {
  apiMocks.positions.mockRejectedValueOnce(new ApiError(new Response(null, { status: 409 }), { code: 'INVENTORY_CONFLICT' } as never))
  const transfer = transferFixture()
  const source = store('source', ['READ_TRANSFERS'])
  const destination = store('destination', ['READ_TRANSFERS', 'READ_INVENTORY', 'RECEIVE_STOCK'])
  renderWithTenant(<TransferDetailView organizationId={orgId} transfer={transfer} stores={[source, destination]} queueStores={[source, destination]} discrepancies={[]} onCommand={vi.fn()} busy={false} onRefresh={async () => undefined} />)

  expect(await screen.findByRole('alert')).toHaveTextContent('stock position changed since it was reviewed')
  expect(screen.getByRole('button', { name: 'Record 2 physical units received' })).toBeDisabled()
  expect(screen.queryByRole('form', { name: 'Request physical return' })).not.toBeInTheDocument()
})

it('hides return actions from an unauthorized destination and disables an authorized pending submission', async () => {
  const transfer = transferFixture()
  const source = store('source', ['READ_TRANSFERS'])
  const unauthorizedDestination = store('destination', ['READ_TRANSFERS'])
  const { unmount } = renderWithTenant(<TransferDetailView organizationId={orgId} transfer={transfer} stores={[source, unauthorizedDestination]} queueStores={[source, unauthorizedDestination]} discrepancies={[]} onCommand={vi.fn()} busy={false} onRefresh={async () => undefined} />)
  await waitFor(() => expect(apiMocks.discrepancies).toHaveBeenCalled())
  expect(screen.queryByRole('form', { name: 'Request physical return' })).not.toBeInTheDocument()
  unmount()

  const destination = store('destination', ['READ_TRANSFERS', 'REQUEST_TRANSFER_RETURN'])
  renderWithTenant(<TransferDetailView organizationId={orgId} transfer={transfer} stores={[source, destination]} queueStores={[source, destination]} discrepancies={[]} onCommand={vi.fn()} busy={true} onRefresh={async () => undefined} />)
  const request = await screen.findByRole('form', { name: 'Request physical return' })
  expect(within(request).getByText(/2 EA outstanding/)).toBeInTheDocument()
  expect(within(request).getByRole('button', { name: 'Saving return request…' })).toBeDisabled()
})

it('shows a pending return to the source receiver and blocks changes while a command is pending', async () => {
  const transfer = transferFixture('RECONCILIATION_REQUIRED')
  const source = store('source', ['READ_TRANSFERS', 'READ_INVENTORY', 'RECEIVE_STOCK'])
  const destination = store('destination', ['READ_TRANSFERS'])
  const request: TransferDiscrepancyRecord = {
    id: 'return-evidence-1', transferId: transfer.id, lineId: 'line-1', type: 'RETURN_REQUEST',
    expectedState: JSON.stringify({ outstanding: 2 }), observedState: JSON.stringify({ returnReference: 'RETURN-1', requestedQuantity: 2 }),
    reason: 'Return outstanding cases', evidenceReference: 'return-request:1', recordedAt: '2026-11-20T10:00:00Z',
  }
  apiMocks.discrepancies.mockResolvedValue({ items: [request], hasMore: false })
  renderWithTenant(<TransferDetailView organizationId={orgId} transfer={transfer} stores={[source, destination]} queueStores={[source, destination]} discrepancies={[request]} onCommand={vi.fn()} busy={true} onRefresh={async () => undefined} />)

  const physicalReturn = await screen.findByRole('form', { name: 'Confirm physical return RETURN-1' })
  expect(screen.queryByRole('form', { name: 'Request physical return' })).not.toBeInTheDocument()
  expect(screen.queryByRole('form', { name: 'Receive line 1' })).not.toBeInTheDocument()
  expect(within(physicalReturn).getByText(/Current source position version: 9/)).toBeInTheDocument()
  expect(within(physicalReturn).getByRole('button', { name: 'Saving physical return…' })).toBeDisabled()
})
