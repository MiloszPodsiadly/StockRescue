import { render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import type { StockPosition } from '@/api/inventory'
import { PositionTable } from './inventory-page'

const row = (initialization: StockPosition['initialization'], quantity: number | null): StockPosition => ({
  id: 'position', productId: 'product', lotId: 'lot', locationId: 'location', unit: 'EA', initialization,
  trust: 'UNVERIFIED', onHand: quantity, reserved: initialization === 'INITIALIZED' ? 0 : null,
  quarantined: initialization === 'INITIALIZED' ? 0 : null, otherBlocked: initialization === 'INITIALIZED' ? 0 : null,
  available: quantity, version: 3, observedAt: '2026-11-12T10:00:00Z',
})

const names = { productNames: new Map([['product', 'SKU-1 · Product']]), lotNames: new Map([['lot', 'LOT-1']]), locationNames: new Map([['location', 'A-01 · Shelf']]) }

it('keeps an uninitialized physical count distinct from an initialized zero balance', () => {
  const view = render(<PositionTable items={[row('NOT_INITIALIZED', null)]} {...names} selectedId="" onSelect={vi.fn()} />)
  expect(screen.getAllByText('Unknown')).toHaveLength(5)
  expect(screen.getByText('SKU-1 · Product')).toBeInTheDocument()
  view.rerender(<PositionTable items={[row('INITIALIZED', 0)]} {...names} selectedId="" onSelect={vi.fn()} />)
  expect(screen.getAllByText('0 EA')).toHaveLength(5)
})

it('lets keyboard users focus and activate the position review action', async () => {
  const user = userEvent.setup()
  const onSelect = vi.fn()
  render(<PositionTable items={[row('INITIALIZED', 0)]} {...names} selectedId="" onSelect={onSelect} />)
  const review = screen.getByRole('button', { name: 'Review position' })
  await user.tab()
  expect(review).toHaveFocus()
  await user.keyboard('{Enter}')
  expect(onSelect).toHaveBeenCalledWith('position')
})
