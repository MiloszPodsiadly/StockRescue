import { useState } from 'react'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { components } from '@/api/schema'
import { TenantBoundary } from '../organizations/tenant-boundary'
import { DurableForm } from './durable-form'

afterEach(cleanup)
const problem = (status: number, code: components['schemas']['ApiProblem']['code'] = status === 409 ? 'CATALOG_CONFLICT' : 'INVALID_REQUEST') => new ApiError(new Response(null, { status }), { status, code, type: 'about:blank', title: 'Review required', detail: 'Review fields.', correlationId: 'test-correlation' })
function Form({ execute, saved, refresh, version = 1 }: { execute: (body: { requestId: string, name: string, version: number }, signal: AbortSignal) => Promise<{ id: string }>, saved: () => Promise<void>, refresh?: () => Promise<void>, version?: number }) {
  const [name, setName] = useState('Original')
  return <DurableForm title="Reviewed change" submitLabel="Save reviewed change" confirmation="I reviewed the evidence." prepare={() => ({ requestId: crypto.randomUUID(), name, version })} execute={execute} onSaved={saved} onRefresh={refresh ?? (async () => {})}>
    <label>Name<input value={name} onChange={event => { setName(event.target.value) }} /></label>
  </DurableForm>
}
function GtinForm({ execute, precondition }: { execute: (body: { requestId: string, name: string }, signal: AbortSignal) => Promise<{ id: string }>, precondition: (code: string) => void }) {
  const [name, setName] = useState('Separate product')
  return <DurableForm title="Create product" submitLabel="Save product" confirmation="I reviewed this product." prepare={() => ({ requestId: crypto.randomUUID(), name })}
    execute={execute} onSaved={async () => {}} onRefresh={async () => {}} onPreconditionConflict={precondition}>
    <label>Name<input value={name} onChange={event => { setName(event.target.value) }} /></label>
  </DurableForm>
}
function scope(children: React.ReactNode) { return <TenantBoundary actorId="actor" organizationId="org">{children}</TenantBoundary> }
async function submit() { const user = userEvent.setup(); await user.click(screen.getByLabelText('I reviewed the evidence.')); await user.click(screen.getByRole('button', { name: 'Save reviewed change' })); return user }

it('recovers an uncertain command with its captured body despite a newer background version', async () => {
  const execute = vi.fn().mockRejectedValueOnce(new TypeError('Lost acknowledgement')).mockResolvedValueOnce({ id: 'saved' })
  const saved = vi.fn().mockResolvedValue(undefined)
  const view = render(scope(<Form execute={execute} saved={saved} />))
  const user = await submit()
  await screen.findByRole('button', { name: 'Retry same request' })
  expect(screen.getByLabelText('Name')).toBeDisabled()
  expect(execute).toHaveBeenCalledTimes(1)
  view.rerender(scope(<Form execute={execute} saved={saved} version={2} />))
  await user.click(screen.getByRole('button', { name: 'Retry same request' }))
  await waitFor(() => expect(saved).toHaveBeenCalledTimes(1))
  expect(execute.mock.calls[1][0]).toEqual(execute.mock.calls[0][0])
  expect(execute.mock.calls[1][0].version).toBe(1)
})

it('refreshes a confirmed result without submitting the already committed command again', async () => {
  const execute = vi.fn().mockResolvedValue({ id: 'saved' })
  const saved = vi.fn().mockRejectedValueOnce(new Error('View unavailable')).mockResolvedValueOnce(undefined)
  render(scope(<Form execute={execute} saved={saved} />))
  const user = await submit()
  await screen.findByRole('button', { name: 'Refresh confirmed result' })
  expect(screen.getByRole('status')).toHaveTextContent('The server confirmed this change.')
  expect(screen.queryByRole('button', { name: 'Retry same request' })).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Refresh confirmed result' }))
  await waitFor(() => expect(saved).toHaveBeenCalledTimes(2))
  expect(execute).toHaveBeenCalledTimes(1)
})

it('allows corrected fields after validation rejection and requires a new explicit review', async () => {
  const execute = vi.fn().mockRejectedValueOnce(problem(422)).mockResolvedValueOnce({ id: 'saved' })
  render(scope(<Form execute={execute} saved={async () => {}} />))
  const user = await submit()
  await screen.findByRole('alert')
  expect(screen.getByLabelText('Name')).toBeEnabled()
  await user.clear(screen.getByLabelText('Name')); await user.type(screen.getByLabelText('Name'), 'Corrected')
  expect(screen.getByRole('button', { name: 'Save reviewed change' })).toBeDisabled()
  await user.click(screen.getByLabelText('I reviewed the evidence.')); await user.click(screen.getByRole('button', { name: 'Save reviewed change' }))
  await waitFor(() => expect(execute).toHaveBeenCalledTimes(2))
  expect(execute.mock.calls[0][0].requestId).not.toBe(execute.mock.calls[1][0].requestId)
  expect(execute.mock.calls[1][0].name).toBe('Corrected')
})

it('requires a successful conflict refresh before another reviewed command', async () => {
  const execute = vi.fn().mockRejectedValueOnce(problem(409))
  const refresh = vi.fn().mockRejectedValueOnce(new Error('Still offline')).mockResolvedValueOnce(undefined)
  render(scope(<Form execute={execute} saved={async () => {}} refresh={refresh} />))
  const user = await submit()
  await screen.findByRole('button', { name: 'Refresh saved values' })
  expect(screen.getByLabelText('Name')).toBeDisabled()
  expect(screen.queryByRole('button', { name: 'Retry same request' })).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Refresh saved values' }))
  await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))
  expect(screen.getByLabelText('Name')).toBeDisabled()
  await user.click(screen.getByRole('button', { name: 'Refresh saved values' }))
  await waitFor(() => expect(screen.getByLabelText('Name')).toBeEnabled())
  expect(screen.getByLabelText('I reviewed the evidence.')).not.toBeChecked()
  expect(execute).toHaveBeenCalledTimes(1)
})

it('releases only a proven GTIN precondition conflict for a new reviewed command', async () => {
  const execute = vi.fn().mockRejectedValueOnce(problem(409, 'GTIN_REVIEW_REQUIRED')).mockResolvedValueOnce({ id: 'created' })
  const precondition = vi.fn()
  render(scope(<GtinForm execute={execute} precondition={precondition} />))
  const user = userEvent.setup()
  await user.click(screen.getByLabelText('I reviewed this product.'))
  await user.click(screen.getByRole('button', { name: 'Save product' }))
  await screen.findByRole('alert')
  await waitFor(() => expect(precondition).toHaveBeenCalledWith('GTIN_REVIEW_REQUIRED'))
  expect(screen.getByLabelText('Name')).toBeEnabled()
  expect(screen.queryByRole('button', { name: 'Retry same request' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Refresh saved values' })).not.toBeInTheDocument()
  await user.click(screen.getByLabelText('I reviewed this product.'))
  await user.click(screen.getByRole('button', { name: 'Save product' }))
  await waitFor(() => expect(execute).toHaveBeenCalledTimes(2))
  expect(execute.mock.calls[0][0].requestId).not.toBe(execute.mock.calls[1][0].requestId)
})

it('aborts a departing tenant command and ignores a late private result', async () => {
  const pending = Promise.withResolvers<{ id: string }>()
  const saved = vi.fn().mockResolvedValue(undefined)
  let signal: AbortSignal | undefined
  const execute = vi.fn((_body, requestSignal: AbortSignal) => { signal = requestSignal; return pending.promise })
  const view = render(<TenantBoundary key="a" actorId="actor" organizationId="org-a"><Form execute={execute} saved={saved} /></TenantBoundary>)
  await submit()
  await waitFor(() => expect(signal).toBeDefined())
  view.rerender(<TenantBoundary key="b" actorId="actor" organizationId="org-b"><p>Different organization</p></TenantBoundary>)
  expect(signal?.aborted).toBe(true)
  await act(async () => { pending.resolve({ id: 'private-original-result' }) })
  expect(saved).not.toHaveBeenCalled()
  expect(screen.queryByText('private-original-result')).not.toBeInTheDocument()
})
