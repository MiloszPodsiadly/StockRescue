import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import { TenantBoundary } from '../organizations/tenant-boundary'
import { StoreForm } from './store-form'

const api = vi.hoisted(() => ({ createStore: vi.fn(), updateStore: vi.fn() }))
vi.mock('@/api/stores', () => api)
beforeEach(() => { vi.resetAllMocks() })
afterEach(cleanup)

async function fillStore(user: ReturnType<typeof userEvent.setup>, code: string, name: string, zone: string) {
  for (const [label, value] of [['Store code', code], ['Store name', name], ['IANA timezone', zone]]) {
    await user.click(screen.getByLabelText(label))
    await user.paste(value)
  }
}

it('retries an uncertain store creation with the original key and payload and starts a new intent only on request', async () => {
  api.createStore.mockRejectedValueOnce(new TypeError('Lost response')).mockResolvedValue({ id: 'store', code: 'NORTH' })
  render(<TenantBoundary actorId="actor" organizationId="org"><StoreForm organizationId="org" onSaved={async () => {}} /></TenantBoundary>)
  const user = userEvent.setup()
  await fillStore(user, 'NORTH', 'North store', 'Europe/Warsaw')
  await user.click(screen.getByRole('button', { name: 'Create store' }))
  await screen.findByRole('alert')
  expect(api.createStore).toHaveBeenCalledTimes(1)
  expect(screen.getByLabelText('Store name')).toBeDisabled()
  await user.click(screen.getByRole('button', { name: 'Retry same store request' }))
  await screen.findByRole('button', { name: 'Create another store' })
  expect(api.createStore.mock.calls[1]?.[1]).toEqual(api.createStore.mock.calls[0]?.[1])
  await user.click(screen.getByRole('button', { name: 'Create another store' }))
  await fillStore(user, 'SOUTH', 'South store', 'UTC')
  await user.click(screen.getByRole('button', { name: 'Create store' }))
  await screen.findByRole('button', { name: 'Create another store' })
  expect(api.createStore.mock.calls[2]?.[1].requestId).not.toEqual(api.createStore.mock.calls[0]?.[1].requestId)
})

it('allows correction after a definite code conflict without automatically submitting another creation', async () => {
  api.createStore.mockRejectedValue(new ApiError(new Response(null, { status: 409 })))
  render(<TenantBoundary actorId="actor" organizationId="org"><StoreForm organizationId="org" onSaved={async () => {}} /></TenantBoundary>)
  const user = userEvent.setup()
  await fillStore(user, 'NORTH', 'North store', 'UTC')
  await user.click(screen.getByRole('button', { name: 'Create store' }))
  await screen.findByRole('alert')
  expect(screen.getByLabelText('Store code')).toBeEnabled()
  expect(api.createStore).toHaveBeenCalledTimes(1)
})
