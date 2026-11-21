import { expect, it } from 'vitest'
import { formatTransferAge, transferStatusLabel } from './transfer-format'

it('keeps a completed physical pick distinct from dispatched custody', () => {
  expect(transferStatusLabel('PICKED')).toBe('Picked, not dispatched')
  expect(transferStatusLabel('IN_TRANSIT')).toBe('In transit')
})

it('shows outstanding custody age in readable day, hour and minute units', () => {
  expect(formatTransferAge(0)).toBe('0m')
  expect(formatTransferAge(90 * 60)).toBe('1h 30m')
  expect(formatTransferAge(26 * 60 * 60)).toBe('1d 2h')
})
