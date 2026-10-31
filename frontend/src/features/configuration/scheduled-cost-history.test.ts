import { expect, it } from 'vitest'
import { isAfterInstant } from './scheduled-cost-history'

it('compares scheduled instants at database microsecond precision', () => {
  expect(isAfterInstant('2030-01-01T00:00:00.000001Z', '2030-01-01T00:00:00.000000Z')).toBe(true)
  expect(isAfterInstant('2030-01-01T00:00:00.000999Z', '2030-01-01T00:00:00.001000Z')).toBe(false)
  expect(isAfterInstant('2030-01-01T01:00:00.000000+01:00', '2030-01-01T00:00:00.000000Z')).toBe(false)
})
