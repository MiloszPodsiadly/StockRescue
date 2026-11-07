import { useLayoutEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useMutation } from '@tanstack/react-query'
import { ApiError, errorMessage } from '@/api/client'
import { Button } from '@/components/ui/button'
import { useTenantKey, useTenantRequests } from '../organizations/tenant-requests'

/** One captured command stays recoverable until its durable outcome is confirmed. */
export function DurableForm<Body, Result>({ title, submitLabel, prepare, execute, onSaved, onRefresh, confirmation, onLock, onCancel, canSubmit = true, children }: {
  title: string, submitLabel: string, prepare: () => Body, execute: (body: Body, signal: AbortSignal) => Promise<Result>,
  onSaved: (result: Result) => Promise<void>, onRefresh: () => Promise<void>, confirmation?: string,
  onLock?: (locked: boolean) => void, onCancel?: () => void, canSubmit?: boolean, children: ReactNode,
}) {
  const scope = useTenantKey()
  const tenant = useTenantRequests()
  const local = useRef<AbortController | null>(null)
  const [submitted, setSubmitted] = useState<Body | null>(null)
  const [confirmed, setConfirmed] = useState<{ result: Result } | null>(null)
  const [refreshFailed, setRefreshFailed] = useState(false)
  const [reviewed, setReviewed] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [refreshError, setRefreshError] = useState<unknown>(null)
  useLayoutEffect(() => () => { local.current?.abort(); onLock?.(false) }, [onLock])
  const action = useMutation({
    mutationKey: [...scope, 'catalog', 'command', title], retry: false, gcTime: 0,
    mutationFn: async (body: Body) => {
      const controller = new AbortController()
      local.current = controller
      const signal = AbortSignal.any([tenant(), controller.signal])
      const result = await execute(body, signal)
      signal.throwIfAborted()
      return result
    },
    onSuccess: async result => {
      if (tenant().aborted || local.current?.signal.aborted) return
      setConfirmed({ result })
      try { await onSaved(result); onLock?.(false) }
      catch { if (!tenant().aborted) setRefreshFailed(true) }
    },
    onError: error => {
      setReviewed(false)
      if (error instanceof ApiError && (error.status === 400 || error.status === 422)) { setSubmitted(null); onLock?.(false) }
    },
  })
  const conflict = action.error instanceof ApiError && action.error.status === 409
  const denied = action.error instanceof ApiError && (action.error.status === 401 || action.error.status === 403 || action.error.status === 404)
    && action.error.code !== 'CSRF_INVALID'
  const frozen = submitted !== null || action.isPending || refreshing || confirmed !== null
  async function refresh() {
    setRefreshing(true); setRefreshError(null)
    try {
      if (confirmed) await onSaved(confirmed.result)
      else await onRefresh()
      if (tenant().aborted || local.current?.signal.aborted) return
      setSubmitted(null); setConfirmed(null); setRefreshFailed(false); setReviewed(false); action.reset(); onLock?.(false)
    } catch (error) { if (!tenant().aborted) setRefreshError(error) }
    finally { setRefreshing(false) }
  }
  return <form aria-label={title} className="space-y-5 rounded-2xl border border-border bg-card p-5 sm:p-7" onSubmit={event => {
    event.preventDefault()
    if (frozen || !canSubmit || (confirmation && !reviewed)) return
    const body = prepare(); setSubmitted(body); onLock?.(true); action.mutate(body)
  }}>
    <h3 className="text-xl font-semibold">{title}</h3>
    <fieldset disabled={frozen} className="min-w-0 space-y-5" onChange={() => { setReviewed(false) }}>{children}</fieldset>
    {confirmation && <label className="flex items-start gap-3 text-sm"><input type="checkbox" required checked={reviewed} disabled={frozen} onChange={event => { setReviewed(event.target.checked) }} />{confirmation}</label>}
    {action.isError && !confirmed && <p role="alert" className="break-words rounded-xl border border-destructive/30 p-4 text-sm">{errorMessage(action.error)} {conflict ? 'Refresh the saved values and review them before submitting a new command.' : submitted && !denied ? 'The outcome is unconfirmed. Retry this captured request to recover its recorded result.' : ''}</p>}
    {confirmed && <p role="status">The server confirmed this change.{refreshFailed && ' The view could not refresh. Refresh the confirmed result without submitting again.'}</p>}
    {refreshError !== null && <p role="alert">{errorMessage(refreshError)}</p>}
    <div className="flex flex-wrap gap-3">
      {!submitted && !confirmed && <Button type="submit" disabled={!canSubmit || action.isPending || refreshing || Boolean(confirmation && !reviewed)}>{action.isPending ? 'Saving…' : submitLabel}</Button>}
      {!frozen && onCancel && <Button type="button" variant="outline" onClick={onCancel}>Cancel editing</Button>}
      {submitted !== null && !confirmed && !conflict && !denied && <Button type="button" disabled={action.isPending || refreshing} onClick={() => { action.mutate(submitted) }}>{action.isPending ? 'Confirming change…' : 'Retry same request'}</Button>}
      {(conflict || denied || refreshFailed) && <Button type="button" variant="outline" disabled={action.isPending || refreshing} onClick={() => { void refresh() }}>{refreshing ? 'Refreshing saved values…' : confirmed ? 'Refresh confirmed result' : 'Refresh saved values'}</Button>}
    </div>
  </form>
}
