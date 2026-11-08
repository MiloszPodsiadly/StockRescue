import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { TenantBoundary } from '../organizations/tenant-boundary'
import { productFixture } from '@/test/catalog-fixtures'
import { OperatorClassificationForm } from './operator-classification-form'

const api = vi.hoisted(() => ({ confirmOperatorClassification: vi.fn() }))
vi.mock('@/api/products', async importOriginal => ({ ...await importOriginal<typeof import('@/api/products')>(), ...api }))
afterEach(() => { cleanup(); vi.resetAllMocks() })

it('requires cited evidence and records the current category, temperature and product profile together', async () => {
  api.confirmOperatorClassification.mockResolvedValue({ product: productFixture, confirmation: null, status: 'NOT_RECORDED' })
  const user = userEvent.setup()
  render(<TenantBoundary actorId="actor" organizationId="org"><OperatorClassificationForm organizationId="org" product={productFixture}
    onSaved={async () => {}} onRefresh={async () => {}} onCancel={() => {}} /></TenantBoundary>)
  const save = screen.getByRole('button', { name: 'Save product evidence' })
  expect(save).toBeDisabled()
  await user.selectOptions(screen.getByLabelText('Product category from the reviewed source'), 'BAKERY')
  await user.selectOptions(screen.getByLabelText('Declared temperature class from the reviewed source'), 'AMBIENT')
  await user.selectOptions(screen.getByLabelText('Product profile from the reviewed source'), 'PACKAGED_SHELF_STABLE_DRY_BAKERY')
  await user.type(screen.getByLabelText('Source reference'), 'supplier:category-sheet')
  await user.type(screen.getByLabelText('Reason for this confirmation'), 'Reviewed supplier category evidence')
  expect(save).toBeDisabled()
  await user.selectOptions(screen.getByLabelText('Source type'), 'SUPPLIER_DOCUMENT')
  await user.click(screen.getByLabelText(/I inspected the cited source/))
  await user.click(save)
  await waitFor(() => expect(api.confirmOperatorClassification).toHaveBeenCalledTimes(1))
  expect(api.confirmOperatorClassification.mock.calls[0]?.[2]).toMatchObject({
    expectedProductVersion: productFixture.version,
    category: 'BAKERY',
    temperatureClass: 'AMBIENT',
    productProfile: 'PACKAGED_SHELF_STABLE_DRY_BAKERY',
    sourceType: 'SUPPLIER_DOCUMENT',
    evidenceReference: 'supplier:category-sheet',
    reason: 'Reviewed supplier category evidence',
  })
})

it('blocks the limited bakery profile when the cited source is not an original package or supplier document', async () => {
  api.confirmOperatorClassification.mockResolvedValue({ product: productFixture, confirmation: null, status: 'NOT_RECORDED' })
  const user = userEvent.setup()
  render(<TenantBoundary actorId="actor" organizationId="org"><OperatorClassificationForm organizationId="org" product={productFixture}
    onSaved={async () => {}} onRefresh={async () => {}} onCancel={() => {}} /></TenantBoundary>)
  await user.selectOptions(screen.getByLabelText('Product profile from the reviewed source'), 'PACKAGED_SHELF_STABLE_DRY_BAKERY')
  await user.selectOptions(screen.getByLabelText('Source type'), 'OTHER')
  await user.type(screen.getByLabelText('Source reference'), 'inspection:note')
  await user.type(screen.getByLabelText('Reason for this confirmation'), 'Reviewed descriptive source')
  expect(screen.getByRole('alert')).toHaveTextContent('requires an original package or supplier document')
  expect(screen.getByRole('button', { name: 'Save product evidence' })).toBeDisabled()
  expect(api.confirmOperatorClassification).not.toHaveBeenCalled()
})
