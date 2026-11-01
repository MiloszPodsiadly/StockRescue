import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import type { Lane } from '@/api/configuration'
import { CostRevisionForm } from './lane-forms'

afterEach(cleanup)
const lane: Lane = { id: 'lane', originStoreId: 'north', originStoreName: 'North', destinationStoreId: 'south', destinationStoreName: 'South', originZoneId: 'ambient-north', destinationZoneId: 'ambient-south', active: true, version: 8, latestRevision: 3, latestEffectiveAt: '2030-01-01T00:00:00Z', status: 'UNCONFIGURED', missing: ['NO_EFFECTIVE_COST'], currentCost: null, asOf: '2030-01-01T00:00:00Z' }

it('submits an exact maximum precision cost with explicit currency and current evidence versions', async () => {
  const save = vi.fn()
  render(<CostRevisionForm lane={lane} codes={['EUR', 'PLN']} saving={false} onSubmit={save} />)
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Cost amount'), '999999999999999.9999')
  await user.selectOptions(screen.getByLabelText('Currency'), 'PLN')
  await user.type(screen.getByLabelText('Journey duration (minutes)'), '45')
  await user.type(screen.getByLabelText('Reason for cost revision'), 'Approved carrier price')
  await user.click(screen.getByRole('button', { name: 'Approve cost revision' }))
  expect(save).toHaveBeenCalledExactlyOnceWith({ laneVersion: 8, expectedRevision: 3, costAmount: '999999999999999.9999', currencyCode: 'PLN', durationMinutes: 45, reason: 'Approved carrier price' })
})

it('requires explicit values and interprets a scheduled effective time as UTC regardless of the browser timezone', async () => {
  const save = vi.fn()
  render(<CostRevisionForm lane={lane} codes={['EUR']} saving={false} onSubmit={save} />)
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Approve cost revision' }))
  expect(save).not.toHaveBeenCalled()
  await user.type(screen.getByLabelText('Cost amount'), '0')
  await user.selectOptions(screen.getByLabelText('Currency'), 'EUR')
  await user.type(screen.getByLabelText('Journey duration (minutes)'), '1')
  await user.type(screen.getByLabelText('Reason for cost revision'), 'Approved explicit free journey')
  await user.click(screen.getByLabelText('Schedule a future revision in UTC'))
  const input = screen.getByLabelText('Effective time (UTC)')
  // Native datetime-local editing differs between browsers; set its real input value.
  const { fireEvent } = await import('@testing-library/react')
  fireEvent.change(input, { target: { value: '2030-06-01T10:30:00' } })
  await user.click(screen.getByRole('button', { name: 'Approve cost revision' }))
  expect(save).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ costAmount: '0', durationMinutes: 1, effectiveAt: '2030-06-01T10:30:00.000Z' }))
})
