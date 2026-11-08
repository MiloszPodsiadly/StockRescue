import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { TenantBoundary } from '../organizations/tenant-boundary'
import { productFixture } from '@/test/catalog-fixtures'
import { OperatorClassificationForm } from './operator-classification-form'

const api = vi.hoisted(() => ({ confirmOperatorClassification: vi.fn() }))
vi.mock('@/api/products', async importOriginal => ({ ...await importOriginal<typeof import('@/api/products')>(), ...api }))
afterEach(() => { cleanup(); vi.resetAllMocks() })

it('requires an explicit source type and reference before confirming a known category', async () => {
  api.confirmOperatorClassification.mockResolvedValue({ product: productFixture, confirmation: null, status: 'NOT_RECORDED' })
  const user = userEvent.setup()
  render(<TenantBoundary actorId="actor" organizationId="org"><OperatorClassificationForm organizationId="org" product={productFixture}
    onSaved={async () => {}} onRefresh={async () => {}} onCancel={() => {}} /></TenantBoundary>)
  const save = screen.getByRole('button', { name: 'Save classification evidence' })
  expect(save).toBeDisabled()
  await user.type(screen.getByLabelText('Source reference'), 'supplier:category-sheet')
  await user.type(screen.getByLabelText('Reason for this confirmation'), 'Reviewed supplier category evidence')
  expect(save).toBeDisabled()
  await user.selectOptions(screen.getByLabelText('Source type'), 'SUPPLIER_DOCUMENT')
  await user.click(screen.getByLabelText(/I inspected the cited source/))
  await user.click(save)
  await waitFor(() => expect(api.confirmOperatorClassification).toHaveBeenCalledTimes(1))
  expect(api.confirmOperatorClassification.mock.calls[0]?.[2]).toMatchObject({
    expectedProductVersion: productFixture.version,
    category: productFixture.category,
    sourceType: 'SUPPLIER_DOCUMENT',
    evidenceReference: 'supplier:category-sheet',
    reason: 'Reviewed supplier category evidence',
  })
})
