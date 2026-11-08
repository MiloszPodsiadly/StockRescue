import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { TenantBoundary } from '../organizations/tenant-boundary'
import { productFixture } from '@/test/catalog-fixtures'
import { ProductForm } from './catalog-page'

const api = vi.hoisted(() => ({ createProduct: vi.fn() }))
vi.mock('@/api/products', async importOriginal => ({ ...await importOriginal<typeof import('@/api/products')>(), ...api }))
afterEach(() => { cleanup(); vi.resetAllMocks() })

it('requires an explicit temperature declaration supported by product evidence before creation', async () => {
  api.createProduct.mockResolvedValue(productFixture)
  const user = userEvent.setup()
  render(<TenantBoundary actorId="actor" organizationId="org"><ProductForm organizationId="org" onSaved={async () => {}}
    onRefresh={async () => {}} /></TenantBoundary>)
  const save = screen.getByRole('button', { name: 'Save product' })
  expect(save).toBeDisabled()
  await user.type(screen.getByLabelText('SKU'), 'BREAD-01')
  await user.type(screen.getByLabelText('Product name'), 'Packaged bread')
  expect(save).toBeDisabled()
  expect(screen.getByText(/initial category and declared storage class are provisional/i)).toBeVisible()
  await user.selectOptions(screen.getByLabelText('Declared temperature class'), 'AMBIENT')
  await user.click(save)
  await waitFor(() => expect(api.createProduct).toHaveBeenCalledTimes(1))
  expect(api.createProduct.mock.calls[0]?.[1]).toMatchObject({ category: 'UNCLASSIFIED', temperatureClass: 'AMBIENT' })
})
