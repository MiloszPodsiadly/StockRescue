import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { activateInventoryTenant, clearInventoryCommandsForTenant, savePendingInventoryCommand, usePendingInventoryCommand } from './inventory-pending-commands'

afterEach(() => { cleanup(); clearInventoryCommandsForTenant() })

function PendingState({ tenant, storeId, subject }: { tenant: readonly ['tenant', string, string], storeId: string, subject: string }) {
  const pending = usePendingInventoryCommand<{ requestId: string, expectedVersion: number, quantity: number }>(tenant, storeId, subject)
  return <output>{pending ? `${pending.phase}:${pending.payload.requestId}:${pending.payload.expectedVersion}:${pending.payload.quantity}` : 'empty'}</output>
}

it('restores the exact in-memory command after its position form remounts', () => {
  const tenant = ['tenant', 'actor', 'org'] as const
  const command = { requestId: 'saved-key', expectedVersion: 12, quantity: 7 }
  activateInventoryTenant(tenant)
  const first = render(<PendingState tenant={tenant} storeId="store" subject="position:position-a" />)
  act(() => savePendingInventoryCommand(tenant, 'store', 'position:position-a', { payload: command, phase: 'unknown' }))
  expect(screen.getByRole('status')).toHaveTextContent('unknown:saved-key:12:7')
  first.unmount()
  render(<PendingState tenant={tenant} storeId="store" subject="position:position-a" />)
  expect(screen.getByRole('status')).toHaveTextContent('unknown:saved-key:12:7')
})

it('keeps commands isolated by tenant and purges only the tenant that changed', () => {
  const tenantA = ['tenant', 'actor', 'org-a'] as const
  const tenantB = ['tenant', 'actor', 'org-b'] as const
  activateInventoryTenant(tenantA)
  savePendingInventoryCommand(tenantA, 'store', 'receipt-document', { payload: { requestId: 'a' }, phase: 'unknown' })
  activateInventoryTenant(tenantB)
  savePendingInventoryCommand(tenantB, 'store', 'receipt-document', { payload: { requestId: 'b' }, phase: 'unknown' })
  act(() => clearInventoryCommandsForTenant(tenantA))
  const view = render(<><PendingState tenant={tenantA} storeId="store" subject="receipt-document" /><PendingState tenant={tenantB} storeId="store" subject="receipt-document" /></>)
  expect(screen.getAllByRole('status')[0]).toHaveTextContent('empty')
  expect(screen.getAllByRole('status')[1]).toHaveTextContent('unknown:b:undefined:undefined')
  view.unmount()
})
