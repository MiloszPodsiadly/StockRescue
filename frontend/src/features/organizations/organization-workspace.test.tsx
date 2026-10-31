import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { components } from '@/api/schema'
import type { Organization } from '@/api/organizations'
import { OrganizationWorkspace } from './organization-workspace'

const api = vi.hoisted(() => ({ getActiveOrganization: vi.fn(), listOrganizations: vi.fn(), getEffectivePermissions: vi.fn(), selectOrganization: vi.fn(), onboardOrganization: vi.fn() }))
vi.mock('@/api/organizations', () => api)

const a: Organization = { id: 'org-a', name: 'Cooperative A', membershipId: 'member-a', role: 'ORG_ADMIN', createdAt: '2026-10-30T00:00:00Z' }
const b: Organization = { id: 'org-b', name: 'Cooperative B', membershipId: 'member-b', role: 'MEMBER', createdAt: a.createdAt }
const admin: components['schemas']['PermissionsResponse'] = { organization: a, organizationActions: ['READ_ORGANIZATION', 'MANAGE_ACCESS'], allStoreActions: ['READ_STORE'], storeGrants: [] }
const member: components['schemas']['PermissionsResponse'] = { organization: b, organizationActions: ['READ_ORGANIZATION'], allStoreActions: [], storeGrants: [] }

beforeEach(() => {
  vi.resetAllMocks()
  api.getActiveOrganization.mockResolvedValue(a)
  api.listOrganizations.mockResolvedValue({ items: [a, b], offset: 0, limit: 25, hasMore: false })
})
afterEach(cleanup)

function workspace() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(<QueryClientProvider client={client}><OrganizationWorkspace actorId="actor" /></QueryClientProvider>)
  return client
}

it('aborts the previous tenant read, hides its page immediately and ignores a late response after switching', async () => {
  const oldRead = Promise.withResolvers<components['schemas']['PermissionsResponse']>()
  const selection = Promise.withResolvers<Organization>()
  let previousSignal: AbortSignal | undefined
  api.getEffectivePermissions.mockResolvedValueOnce(admin).mockImplementationOnce((signal: AbortSignal) => {
    previousSignal = signal
    return oldRead.promise
  }).mockResolvedValueOnce(member)
  api.selectOrganization.mockReturnValue(selection.promise)
  const client = workspace()
  const user = userEvent.setup()
  await screen.findByRole('heading', { name: a.name })
  await user.click(screen.getByRole('button', { name: 'Check access again' }))
  await waitFor(() => { expect(previousSignal).toBeDefined() })
  await user.selectOptions(screen.getByLabelText('Organization', { exact: true }), b.id)
  expect(screen.queryByRole('heading', { name: a.name })).not.toBeInTheDocument()
  expect(previousSignal?.aborted).toBe(true)
  expect(screen.getByRole('heading', { name: 'Checking organization selection' })).toBeInTheDocument()
  await act(async () => { selection.resolve(b) })
  await screen.findByRole('heading', { name: b.name })
  expect(screen.getByText('Member', { exact: true })).toBeInTheDocument()
  expect(screen.queryByText('Org Admin', { exact: true })).not.toBeInTheDocument()
  await act(async () => { oldRead.resolve(admin) })
  expect(screen.queryByRole('heading', { name: a.name })).not.toBeInTheDocument()
  expect(client.getQueryCache().findAll({ queryKey: ['tenant'] })).toHaveLength(0)
})

it('discards the previous workspace after a denied selection and never renders another tab’s mismatched permissions', async () => {
  api.getEffectivePermissions.mockResolvedValue(admin)
  api.selectOrganization.mockRejectedValue(new ApiError(new Response(null, { status: 404 })))
  workspace()
  const user = userEvent.setup()
  await screen.findByRole('heading', { name: a.name })
  await user.selectOptions(screen.getByLabelText('Organization', { exact: true }), b.id)
  await screen.findByRole('alert')
  expect(screen.queryByRole('heading', { name: a.name })).not.toBeInTheDocument()
  expect(screen.getByLabelText('Organization', { exact: true })).toHaveValue('')
  cleanup()
  api.getEffectivePermissions.mockResolvedValue(member)
  workspace()
  await screen.findByRole('heading', { name: 'Organization unavailable' })
  expect(screen.queryByRole('heading', { name: a.name })).not.toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: b.name })).not.toBeInTheDocument()
})

it('retries uncertain onboarding with the same key and payload without sending an automatic retry', async () => {
  api.getActiveOrganization.mockResolvedValue(null)
  api.listOrganizations.mockResolvedValue({ items: [], offset: 0, limit: 25, hasMore: false })
  api.onboardOrganization.mockRejectedValueOnce(new TypeError('Connection lost')).mockImplementationOnce(async () => {
    api.listOrganizations.mockResolvedValue({ items: [a], offset: 0, limit: 25, hasMore: false })
    return a
  })
  api.selectOrganization.mockResolvedValue(a)
  api.getEffectivePermissions.mockResolvedValue(admin)
  workspace()
  const user = userEvent.setup()
  const name = await screen.findByLabelText('Organization name')
  await user.type(name, a.name)
  await user.click(screen.getByRole('button', { name: 'Create organization' }))
  await screen.findByRole('alert')
  expect(api.onboardOrganization).toHaveBeenCalledTimes(1)
  expect(name).toBeDisabled()
  await user.click(screen.getByRole('button', { name: 'Retry organization setup' }))
  await screen.findByRole('heading', { name: a.name })
  const first = api.onboardOrganization.mock.calls[0]?.[0] as { requestId: string }
  expect(first.requestId).toMatch(/^[0-9a-f-]{36}$/)
  expect(api.onboardOrganization.mock.calls[1]?.[0]).toEqual(first)
})

it('allows correcting a rejected organization name and sends a new request only after explicit resubmission', async () => {
  api.getActiveOrganization.mockResolvedValue(null)
  api.listOrganizations.mockResolvedValue({ items: [], offset: 0, limit: 25, hasMore: false })
  api.onboardOrganization.mockRejectedValueOnce(new ApiError(new Response(null, { status: 400 }), { type: 'about:blank', title: 'Bad Request', status: 400, code: 'INVALID_REQUEST', detail: 'Review organization fields.', correlationId: 'validation-check' })).mockImplementationOnce(async () => {
    api.listOrganizations.mockResolvedValue({ items: [a], offset: 0, limit: 25, hasMore: false })
    return a
  })
  api.selectOrganization.mockResolvedValue(a)
  api.getEffectivePermissions.mockResolvedValue(admin)
  workspace()
  const user = userEvent.setup()
  const name = await screen.findByLabelText('Organization name')
  await user.type(name, 'Rejected name')
  await user.click(screen.getByRole('button', { name: 'Create organization' }))
  await screen.findByRole('alert')
  expect(name).toBeEnabled()
  expect(api.onboardOrganization).toHaveBeenCalledTimes(1)
  await user.clear(name)
  await user.type(name, a.name)
  await user.click(screen.getByRole('button', { name: 'Create organization' }))
  await screen.findByRole('heading', { name: a.name })
  expect(api.onboardOrganization).toHaveBeenCalledTimes(2)
  expect(api.onboardOrganization.mock.calls[1]?.[0].name).toBe(a.name)
  expect(api.onboardOrganization.mock.calls[1]?.[0].requestId).not.toEqual(api.onboardOrganization.mock.calls[0]?.[0].requestId)
})
