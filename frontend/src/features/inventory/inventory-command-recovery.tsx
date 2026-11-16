import { useState } from 'react'
import { ApiError, errorMessage } from '@/api/client'
import {
  acknowledgeInventoryCommandIntent, executeInventoryCommandIntent, reviewRejectedInventoryCommandIntent,
} from '@/api/inventory'
import type { InventoryCommandIntent, InventoryCommandIntentExecution } from '@/api/inventory'
import { Button } from '@/components/ui/button'
import { useTenantRequests } from '../organizations/tenant-requests'

type Props = {
  organizationId: string
  storeId: string
  intents: InventoryCommandIntent[]
  offset: number
  hasMore: boolean
  onPageChange: (offset: number) => void
  onUpdated: () => Promise<void>
  onResolved: () => Promise<void>
}

export function InventoryCommandRecovery({ organizationId, storeId, intents, offset, hasMore, onPageChange, onUpdated, onResolved }: Props) {
  const tenantSignal = useTenantRequests()
  const [workingId, setWorkingId] = useState<string>()
  const [outcomes, setOutcomes] = useState<Record<string, InventoryCommandIntentExecution>>({})
  const [messages, setMessages] = useState<Record<string, string>>({})
  const [rejected, setRejected] = useState<Record<string, boolean>>({})

  if (intents.length === 0 && offset === 0 && !hasMore) return null

  async function recover(intent: InventoryCommandIntent) {
    setWorkingId(intent.id)
    setMessages(current => ({ ...current, [intent.id]: '' }))
    setRejected(current => ({ ...current, [intent.id]: false }))
    try {
      const result = await executeInventoryCommandIntent(organizationId, storeId, intent.id, tenantSignal())
      setOutcomes(current => ({ ...current, [intent.id]: result }))
      await onUpdated()
    } catch (error) {
      setMessages(current => ({ ...current, [intent.id]: errorMessage(error) }))
      setRejected(current => ({ ...current, [intent.id]: error instanceof ApiError && [400, 409, 422].includes(error.status) }))
    } finally {
      setWorkingId(undefined)
    }
  }

  async function acknowledge(intent: InventoryCommandIntent) {
    setWorkingId(intent.id)
    try {
      await acknowledgeInventoryCommandIntent(organizationId, storeId, intent.id, tenantSignal())
      setOutcomes(current => { const next = { ...current }; delete next[intent.id]; return next })
      setMessages(current => { const next = { ...current }; delete next[intent.id]; return next })
      setRejected(current => { const next = { ...current }; delete next[intent.id]; return next })
      await onResolved()
    } catch (error) {
      setMessages(current => ({ ...current, [intent.id]: errorMessage(error) }))
    } finally {
      setWorkingId(undefined)
    }
  }

  async function reviewRejected(intent: InventoryCommandIntent) {
    setWorkingId(intent.id)
    try {
      await reviewRejectedInventoryCommandIntent(organizationId, storeId, intent.id, tenantSignal())
      setOutcomes(current => { const next = { ...current }; delete next[intent.id]; return next })
      setMessages(current => { const next = { ...current }; delete next[intent.id]; return next })
      setRejected(current => { const next = { ...current }; delete next[intent.id]; return next })
      await onResolved()
    } catch (error) {
      setMessages(current => ({ ...current, [intent.id]: errorMessage(error) }))
      setRejected(current => ({ ...current, [intent.id]: false }))
    } finally {
      setWorkingId(undefined)
    }
  }

  return <section aria-label="Recover pending operation" className="space-y-4 rounded-2xl border border-amber-500/40 bg-amber-50/50 p-5">
    <header><h3 className="text-lg font-semibold">Recover pending operations</h3><p className="mt-1 text-sm text-muted-foreground">Recover or review each saved request before starting another operation for the same subject.</p>{hasMore && <p role="status" className="mt-2 text-sm">More saved operations are available on the next page.</p>}</header>
    {intents.length === 0 ? <p role="status" className="text-sm">No saved operations are on this page. Return to the previous page or refresh the list.</p> : <ul className="space-y-3">{intents.map(intent => {
      const outcome = outcomes[intent.id]?.outcome
      const committed = Boolean(outcome)
      const receiptOutcome = outcome && 'lines' in outcome ? outcome : undefined
      const stockOutcome = outcome && 'positionVersion' in outcome ? outcome : undefined
      const movementId = outcome?.movementId
      return <li key={intent.id} className="space-y-3 rounded-xl border border-border bg-background p-4">
        <p className="font-medium">{intent.operation.replaceAll('_', ' ').toLowerCase()} · request {intent.requestId}</p>
        <p className="break-all text-sm text-muted-foreground">{intent.subject === 'receipt-document' ? 'Supplier receipt document' : `Position ${intent.subject.slice('position:'.length)}`} · prepared {new Date(intent.preparedAt).toLocaleString()}</p>
        {committed && <div role="status" className="space-y-2 rounded-lg bg-muted/50 p-3 text-sm"><p>The saved command committed successfully. Its original request ID and result were recovered.</p>{movementId && <p>Movement {movementId}{stockOutcome && ` · position version ${stockOutcome.positionVersion}`}</p>}{stockOutcome && <p>Position {stockOutcome.positionId}: on hand {stockOutcome.onHand}, reserved {stockOutcome.reserved}, quarantined {stockOutcome.quarantined}, other blocked {stockOutcome.otherBlocked}, available {stockOutcome.available}.</p>}{receiptOutcome && <><p>Receipt {receiptOutcome.receiptId} recorded {receiptOutcome.lines.length} supplier line(s). Each source line remains listed separately; stock totals and quarantine allocations apply to the grouped position.</p><ul className="space-y-1">{receiptOutcome.lines.map(line => <li key={`${line.positionId}:${line.documentLineReference}`}>Line {line.documentLineReference}: {line.quantity} EA · position {line.positionId}: on hand {line.onHand}, reserved {line.reserved}, quarantined {line.quarantined}, other blocked {line.otherBlocked}, available {line.available}, version {line.version}.{line.allocationId && ` Quarantine allocation ${line.allocationId} is shared across this position's source lines.`}</li>)}</ul></>}</div>}
        {messages[intent.id] && <p role={rejected[intent.id] ? 'alert' : 'status'} className="text-sm">{messages[intent.id]}</p>}
        <div className="flex flex-wrap gap-3">
          {committed
            ? <Button type="button" disabled={workingId === intent.id} onClick={() => { void acknowledge(intent) }}>Acknowledge saved result</Button>
            : <Button type="button" variant="outline" disabled={Boolean(workingId)} onClick={() => { void recover(intent) }}>{workingId === intent.id ? 'Recovering exact request…' : 'Recover exact request'}</Button>}
          {rejected[intent.id] && <Button type="button" variant="outline" disabled={Boolean(workingId)} onClick={() => { void reviewRejected(intent) }}>Review rejected request</Button>}
        </div>
      </li>
    })}</ul>}
    {(offset > 0 || hasMore) && <nav aria-label="Saved inventory operations pages" className="flex flex-wrap items-center gap-3">
      <Button type="button" variant="outline" disabled={Boolean(workingId) || offset === 0} onClick={() => onPageChange(Math.max(0, offset - 100))}>Previous operations</Button>
      <span className="text-sm text-muted-foreground">Page {Math.floor(offset / 100) + 1}</span>
      <Button type="button" variant="outline" disabled={Boolean(workingId) || !hasMore} onClick={() => onPageChange(offset + 100)}>Next operations</Button>
    </nav>}
  </section>
}
