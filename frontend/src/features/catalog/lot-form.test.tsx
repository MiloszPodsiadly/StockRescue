import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { TenantBoundary } from '../organizations/tenant-boundary'
import { LotForm } from './lot-form'
import { DateMarking } from './catalog-ui'
import { lotFixture } from '@/test/catalog-fixtures'

const api = vi.hoisted(() => ({ registerLot: vi.fn(), correctLotMetadata: vi.fn(), navigate: vi.fn() }))
vi.mock('@/api/lots', () => ({ registerLot: api.registerLot, correctLotMetadata: api.correctLotMetadata }))
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => api.navigate }))
afterEach(cleanup)
beforeEach(() => { vi.resetAllMocks(); api.registerLot.mockResolvedValue(lotFixture); api.correctLotMetadata.mockResolvedValue({ lot: lotFixture }) })

it('records a partial label exactly without inventing a day or normalized cutoff', async () => {
  render(<TenantBoundary actorId="actor" organizationId="org"><LotForm organizationId="org" productId="product" onRefresh={async () => {}} /></TenantBoundary>)
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Manufacturer lot code'), 'PARTIAL')
  await user.type(screen.getByLabelText('Manufacturer evidence source'), 'Supplier statement')
  await user.type(screen.getByLabelText('Original date label'), ' 2027-04 ')
  await user.selectOptions(screen.getByLabelText('Date marking type'), 'BEST_BEFORE_END')
  await user.selectOptions(screen.getByLabelText('Date precision'), 'MONTH')
  await user.click(screen.getByRole('button', { name: 'Save manufacturer lot' }))
  await waitFor(() => expect(api.registerLot).toHaveBeenCalledTimes(1))
  const body = api.registerLot.mock.calls[0][1]
  expect(body.dateMarking).toEqual({ labeledValue: ' 2027-04 ', type: 'BEST_BEFORE_END', precision: 'MONTH' })
  expect(body.manufacturerVerified).toBe(false)
  expect(body.requestId).toBeTruthy()
})

it('keeps the reviewed original version across a background refresh and requires explicit confirmation', async () => {
  const view = render(<TenantBoundary actorId="actor" organizationId="org"><LotForm organizationId="org" productId="product" lot={lotFixture} onRefresh={async () => {}} onSaved={async () => {}} /></TenantBoundary>)
  const user = userEvent.setup()
  expect(screen.getByLabelText('Manufacturer evidence source')).toHaveAttribute('readonly')
  expect(screen.getByLabelText('Manufacturer identity reference')).toHaveAttribute('readonly')
  await user.clear(screen.getByLabelText('Original date label')); await user.type(screen.getByLabelText('Original date label'), ' 2027-05 ')
  await user.type(screen.getByLabelText('Reason for lot correction'), 'Reviewed replacement original label')
  await user.click(screen.getByLabelText('I checked the manufacturer identity against this source. This does not approve food safety.'))
  expect(screen.getByRole('button', { name: 'Save reviewed correction' })).toBeDisabled()
  view.rerender(<TenantBoundary actorId="actor" organizationId="org"><LotForm organizationId="org" productId="product" lot={{ ...lotFixture, version: 2 }} onRefresh={async () => {}} onSaved={async () => {}} /></TenantBoundary>)
  await user.click(screen.getByLabelText('I reviewed the replacement evidence and confirm this lot correction.'))
  await user.click(screen.getByRole('button', { name: 'Save reviewed correction' }))
  await waitFor(() => expect(api.correctLotMetadata).toHaveBeenCalledTimes(1))
  expect(api.correctLotMetadata.mock.calls[0][2]).toMatchObject({ expectedVersion: 1, evidenceReviewed: true, dateMarking: { labeledValue: ' 2027-05 ', precision: 'MONTH' } })
})

it('shows raw label text and partial precision separately from policy approval', () => {
  render(<DateMarking value={lotFixture.dateMarking} />)
  expect(screen.getByText('2027-04').textContent).toBe(' 2027-04 ')
  expect(screen.getByText('MONTH')).toBeVisible()
  expect(screen.getByText('No verified normalization')).toBeVisible()
  expect(screen.queryByText('2027-04-30')).not.toBeInTheDocument()
})

it('explains an incomplete best-before-end day without changing the observed label', () => {
  render(<DateMarking value={{ ...lotFixture.dateMarking, labeledValue: ' 2026-11-04 ', type: 'BEST_BEFORE_END', precision: 'DAY',
    validationStatus: 'INCOMPLETE', validationReasons: ['BEST_BEFORE_END_REQUIRES_PERIOD'] }} />)
  expect(screen.getByText('2026-11-04').textContent).toBe(' 2026-11-04 ')
  expect(screen.getByText('INCOMPLETE')).toBeVisible()
  expect(screen.getByText('A best-before-end label uses month or year precision; a recorded day is incomplete.')).toBeVisible()
  expect(screen.getByText('No verified normalization')).toBeVisible()
  expect(screen.queryByText('2026-11-30')).not.toBeInTheDocument()
})

it('explains a nonexistent date as invalid instead of showing an inferred cutoff', () => {
  render(<DateMarking value={{ ...lotFixture.dateMarking, labeledValue: '2026-02-31', type: 'USE_BY', precision: 'DAY',
    validationStatus: 'INVALID', validationReasons: ['NONEXISTENT_DATE'], normalizedCutoff: null }} />)
  expect(screen.getByText('2026-02-31')).toBeVisible()
  expect(screen.getByText('INVALID')).toBeVisible()
  expect(screen.getByText('The recorded date is not a real calendar date.')).toBeVisible()
  expect(screen.getByText('No verified normalization')).toBeVisible()
})
