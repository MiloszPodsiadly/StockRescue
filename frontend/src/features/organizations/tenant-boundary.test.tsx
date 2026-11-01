import { StrictMode } from 'react'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { afterEach, expect, it, vi } from 'vitest'
import { inviteStaff } from '@/api/access'
import { clearCsrf } from '@/api/client'
import { InviteForm } from '../access/invite-form'
import { TenantBoundary } from './tenant-boundary'
import { useTenantRequests } from './tenant-requests'

const json = (body: unknown) => new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })
afterEach(() => { cleanup(); clearCsrf(); vi.unstubAllGlobals() })

it('aborts a pending tenant command and ignores its late result after the workspace is replaced', async () => {
  const pending = Promise.withResolvers<Response>()
  const completed = vi.fn()
  let commandSignal: AbortSignal | undefined
  let previousClient: QueryClient | undefined
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ token: 'csrf-value' })).mockImplementationOnce((request: Request) => {
    commandSignal = request.signal
    return pending.promise
  }))
  function PendingCommand() {
    previousClient = useQueryClient()
    const getSignal = useTenantRequests()
    const command = useMutation({ mutationFn: async () => { await inviteStaff('org-a', { email: 'operator@example.test', storeGrants: [], reason: 'Staff onboarding' }, getSignal()) }, onSuccess: completed })
    return <button onClick={() => { command.mutate() }}>Invite staff</button>
  }
  const view = render(<StrictMode><TenantBoundary key="a" actorId="actor" organizationId="org-a"><PendingCommand /></TenantBoundary></StrictMode>)
  await userEvent.click(screen.getByRole('button', { name: 'Invite staff' }))
  await waitFor(() => { expect(commandSignal).toBeDefined() })
  expect(commandSignal?.aborted).toBe(false)
  view.rerender(<StrictMode><TenantBoundary key="b" actorId="actor" organizationId="org-b"><p>Workspace B</p></TenantBoundary></StrictMode>)
  expect(commandSignal?.aborted).toBe(true)
  await act(async () => { pending.resolve(json({ id: 'invite-a', token: 'never-returned-to-the-next-workspace', expiresAt: '2026-11-01T00:00:00Z' })) })
  expect(completed).not.toHaveBeenCalled()
  expect(previousClient?.getQueryCache().getAll()).toHaveLength(0)
  expect(previousClient?.getMutationCache().getAll()).toHaveLength(0)
  expect(screen.queryByText('never-returned-to-the-next-workspace')).not.toBeInTheDocument()
})

it('keeps the one-time invitation secret out of mutation data and removes it when the form unmounts', async () => {
  const secret = 'a'.repeat(43)
  let client: QueryClient | undefined
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ token: 'csrf-value' })).mockResolvedValueOnce(json({ id: 'invite', token: secret, expiresAt: '2026-11-01T00:00:00Z' })))
  function Form() {
    client = useQueryClient()
    const signal = useTenantRequests()
    return <InviteForm organizationId="org" stores={[]} signal={signal} onCreated={async () => {}} />
  }
  const view = render(<TenantBoundary actorId="actor" organizationId="org"><Form /></TenantBoundary>)
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Recipient email'), 'operator@example.test')
  await user.type(screen.getByLabelText('Reason for invitation'), 'Staff onboarding')
  await user.click(screen.getByRole('button', { name: 'Create private invitation' }))
  const link = await screen.findByLabelText('Private invitation link')
  expect(link).toHaveAttribute('type', 'password')
  expect(client?.getMutationCache().getAll().map(mutation => mutation.state.data)).toEqual([undefined])
  expect(localStorage.length).toBe(0)
  expect(sessionStorage.length).toBe(0)
  view.unmount()
  expect(client?.getMutationCache().getAll()).toHaveLength(0)
  expect(screen.queryByLabelText('Private invitation link')).not.toBeInTheDocument()
})
