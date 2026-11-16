import { useState } from 'react'
import { ApiError, errorMessage } from '@/api/client'
import {
  acknowledgeInventoryCommandIntent, executeInventoryCommandIntent, inspectInventoryCommandIntent,
  reviewRejectedInventoryCommandIntent,
} from '@/api/inventory'
import type { InventoryCommandIntent, InventoryCommandIntentInspection } from '@/api/inventory'
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

type AttemptFailure = { kind: 'DEFINITIVELY_REJECTED' | 'STALE_OR_CONFLICTING' | 'UNKNOWN', message: string }

export function InventoryCommandRecovery({ organizationId, storeId, intents, offset, hasMore, onPageChange, onUpdated, onResolved }: Props) {
  const tenantSignal = useTenantRequests()
  const [workingId, setWorkingId] = useState<string>()
  const [inspections, setInspections] = useState<Record<string, InventoryCommandIntentInspection>>({})
  const [readyToExecute, setReadyToExecute] = useState<Record<string, boolean>>({})
  const [confirmed, setConfirmed] = useState<Record<string, boolean>>({})
  const [messages, setMessages] = useState<Record<string, string>>({})
  const [failures, setFailures] = useState<Record<string, AttemptFailure>>({})
  const [rejected, setRejected] = useState<Record<string, boolean>>({})

  if (intents.length === 0 && offset === 0 && !hasMore) return null

  function clearConfirmation(id: string) {
    setConfirmed(current => ({ ...current, [id]: false }))
  }

  async function inspect(intent: InventoryCommandIntent) {
    const priorFailure = failures[intent.id]
    setWorkingId(intent.id)
    setMessages(current => ({ ...current, [intent.id]: '' }))
    setReadyToExecute(current => ({ ...current, [intent.id]: false }))
    clearConfirmation(intent.id)
    try {
      const result = await inspectInventoryCommandIntent(organizationId, storeId, intent.id, tenantSignal())
      setInspections(current => ({ ...current, [intent.id]: result }))
      setReadyToExecute(current => ({ ...current, [intent.id]: result.executionStatus === 'PREPARED'
        && (!priorFailure || priorFailure.kind === 'UNKNOWN') }))
      if (result.executionStatus !== 'PREPARED') {
        setFailures(current => { const next = { ...current }; delete next[intent.id]; return next })
        setRejected(current => ({ ...current, [intent.id]: false }))
      }
      await onUpdated()
    } catch (error) {
      setFailures(current => ({ ...current, [intent.id]: { kind: 'UNKNOWN', message: errorMessage(error) } }))
      setRejected(current => ({ ...current, [intent.id]: false }))
    } finally {
      setWorkingId(undefined)
    }
  }

  async function executeReviewed(intent: InventoryCommandIntent) {
    setWorkingId(intent.id)
    setMessages(current => ({ ...current, [intent.id]: '' }))
    setFailures(current => { const next = { ...current }; delete next[intent.id]; return next })
    setReadyToExecute(current => ({ ...current, [intent.id]: false }))
    clearConfirmation(intent.id)
    try {
      await executeInventoryCommandIntent(organizationId, storeId, intent.id, tenantSignal())
      const result = await inspectInventoryCommandIntent(organizationId, storeId, intent.id, tenantSignal())
      setInspections(current => ({ ...current, [intent.id]: result }))
      setReadyToExecute(current => ({ ...current, [intent.id]: result.executionStatus === 'PREPARED' }))
      setRejected(current => ({ ...current, [intent.id]: false }))
      await onUpdated()
    } catch (error) {
      const kind = error instanceof ApiError && [400, 422].includes(error.status)
        ? 'DEFINITIVELY_REJECTED'
        : error instanceof ApiError && error.status === 409 ? 'STALE_OR_CONFLICTING' : 'UNKNOWN'
      setFailures(current => ({ ...current, [intent.id]: { kind, message: errorMessage(error) } }))
      setRejected(current => ({ ...current, [intent.id]: kind !== 'UNKNOWN' }))
    } finally {
      setWorkingId(undefined)
    }
  }

  async function acknowledge(intent: InventoryCommandIntent) {
    setWorkingId(intent.id)
    try {
      await acknowledgeInventoryCommandIntent(organizationId, storeId, intent.id, tenantSignal())
      setInspections(current => { const next = { ...current }; delete next[intent.id]; return next })
      setMessages(current => { const next = { ...current }; delete next[intent.id]; return next })
      setFailures(current => { const next = { ...current }; delete next[intent.id]; return next })
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
      setInspections(current => { const next = { ...current }; delete next[intent.id]; return next })
      setMessages(current => { const next = { ...current }; delete next[intent.id]; return next })
      setFailures(current => { const next = { ...current }; delete next[intent.id]; return next })
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
    <header><h3 className="text-lg font-semibold">Recover pending operations</h3><p className="mt-1 text-sm text-muted-foreground">Inspect each saved request before you choose whether to execute it.</p>{hasMore && <p role="status" className="mt-2 text-sm">More saved operations are available on the next page.</p>}</header>
    {intents.length === 0 ? <p role="status" className="text-sm">No saved operations are on this page. Return to the previous page or refresh the list.</p> : <ul className="space-y-3">{intents.map(intent => {
      const inspection = inspections[intent.id]
      const status = inspection?.executionStatus
      const outcome = inspection?.committedOutcome
      const receiptOutcome = inspection?.committedReceiptOutcome
      const committed = status === 'COMMITTED_AWAITING_ACKNOWLEDGEMENT' || status === 'ACKNOWLEDGED'
      const resultAvailable = Boolean(receiptOutcome || outcome)
      return <li key={intent.id} className="space-y-3 rounded-xl border border-border bg-background p-4">
        <p className="font-medium">{intent.operation.replaceAll('_', ' ').toLowerCase()} · request {intent.requestId}</p>
        <p className="break-all text-sm text-muted-foreground">{intent.subject === 'receipt-document' ? 'Supplier receipt document' : `Position ${intent.subject.slice('position:'.length)}`} · prepared {new Date(intent.preparedAt).toLocaleString()}</p>
        {!inspection && <Button type="button" variant="outline" disabled={Boolean(workingId)} onClick={() => { void inspect(intent) }}>{workingId === intent.id ? 'Inspecting saved request…' : 'Inspect saved request'}</Button>}
        {inspection && <>
          <div role="status" className="space-y-2 rounded-lg bg-muted/50 p-3 text-sm">
            <p className="font-medium">{inspectionStatusLabel(status)}</p>
            {inspection.positionPreview && <PositionPreview payload={inspection.positionPreview} />}
            {inspection.receiptDocumentPreview && <ReceiptPreview payload={inspection.receiptDocumentPreview} />}
            {committed && resultAvailable && <CommittedOutcome outcome={outcome} receiptOutcome={receiptOutcome} />}
            {committed && !resultAvailable && <p>The command is recorded, but its original result could not be loaded. Check the saved result again before acknowledging it.</p>}
            {status === 'CONFLICT' && <p>The saved intent and command receipt disagree. Do not execute it. Ask an administrator to review this inventory record.</p>}
            {status === 'REVIEWED' && <p>This saved request was already reviewed and cannot be executed.</p>}
          </div>
          {status === 'PREPARED' && <div className="space-y-3 rounded-lg border border-border p-3">
            <label className="flex items-start gap-3 text-sm">
              <input type="checkbox" checked={confirmed[intent.id] ?? false} disabled={Boolean(workingId) || !readyToExecute[intent.id]}
                onChange={event => setConfirmed(current => ({ ...current, [intent.id]: event.target.checked }))} />
              <span>I reviewed this exact saved request and confirm it may be executed.</span>
            </label>
            <Button type="button" disabled={Boolean(workingId) || !readyToExecute[intent.id] || !confirmed[intent.id]}
              onClick={() => { void executeReviewed(intent) }}>{workingId === intent.id ? 'Checking saved result…' : 'Execute reviewed command'}</Button>
          </div>}
          {committed && resultAvailable && <Button type="button" disabled={Boolean(workingId)} onClick={() => { void acknowledge(intent) }}>Acknowledge saved result</Button>}
          {status === 'ACKNOWLEDGED' && <p className="text-sm text-muted-foreground">This result has already been acknowledged.</p>}
          {status === 'REVIEWED' && <p className="text-sm text-muted-foreground">This intent is closed.</p>}
          {status === 'CONFLICT' && <Button type="button" variant="outline" disabled={Boolean(workingId)} onClick={() => { void inspect(intent) }}>Check saved result</Button>}
        </>}
        {failures[intent.id] && <div role={failures[intent.id].kind === 'DEFINITIVELY_REJECTED' ? 'alert' : 'status'} className="space-y-2 text-sm">
          <p>{failureLabel(failures[intent.id])}: {failures[intent.id].message}</p>
          <p>The request was not sent again. Check its saved result before deciding what to do next.</p>
          <Button type="button" variant="outline" disabled={Boolean(workingId)} onClick={() => { void inspect(intent) }}>Check saved result</Button>
        </div>}
        {messages[intent.id] && <p role="status" className="text-sm">{messages[intent.id]}</p>}
        {rejected[intent.id] && <Button type="button" variant="outline" disabled={Boolean(workingId)} onClick={() => { void reviewRejected(intent) }}>Review rejected request</Button>}
      </li>
    })}</ul>}
    {(offset > 0 || hasMore) && <nav aria-label="Saved inventory operations pages" className="flex flex-wrap items-center gap-3">
      <Button type="button" variant="outline" disabled={Boolean(workingId) || offset === 0} onClick={() => onPageChange(Math.max(0, offset - 100))}>Previous operations</Button>
      <span className="text-sm text-muted-foreground">Page {Math.floor(offset / 100) + 1}</span>
      <Button type="button" variant="outline" disabled={Boolean(workingId) || !hasMore} onClick={() => onPageChange(offset + 100)}>Next operations</Button>
    </nav>}
  </section>
}

function inspectionStatusLabel(status: InventoryCommandIntentInspection['executionStatus'] | undefined) {
  switch (status) {
    case 'PREPARED': return 'Saved request is ready for review.'
    case 'COMMITTED_AWAITING_ACKNOWLEDGEMENT': return 'The command committed successfully. Its original result is shown below.'
    case 'ACKNOWLEDGED': return 'The command committed and was acknowledged.'
    case 'REVIEWED': return 'This request was reviewed.'
    case 'CONFLICT': return 'Saved request requires conflict review.'
    default: return 'Saved request status is unavailable.'
  }
}

function failureLabel(failure: AttemptFailure) {
  switch (failure.kind) {
    case 'DEFINITIVELY_REJECTED': return 'The command was rejected.'
    case 'STALE_OR_CONFLICTING': return 'The command conflicted with current inventory state.'
    case 'UNKNOWN': return 'The command result is uncertain.'
  }
}

function PositionPreview({ payload }: { payload: NonNullable<InventoryCommandIntentInspection['positionPreview']> }) {
  return <dl className="grid gap-x-4 gap-y-1 sm:grid-cols-[max-content_1fr]">
    <dt>Request ID</dt><dd className="break-all">{payload.requestId}</dd>
    <dt>Position</dt><dd className="break-all">{payload.positionId}</dd>
    <dt>Expected version</dt><dd>{payload.expectedVersion}</dd>
    <dt>Signed quantity (EA)</dt><dd>{payload.quantity}</dd>
    <dt>Allocation</dt><dd className="break-all">{payload.allocationId ?? 'None'}</dd>
    <dt>Reason</dt><dd>{payload.reason}</dd>
    <dt>Evidence reference</dt><dd className="break-all">{payload.evidenceReference}</dd>
  </dl>
}

function ReceiptPreview({ payload }: { payload: NonNullable<InventoryCommandIntentInspection['receiptDocumentPreview']> }) {
  return <div className="space-y-2">
    <dl className="grid gap-x-4 gap-y-1 sm:grid-cols-[max-content_1fr]">
      <dt>Request ID</dt><dd className="break-all">{payload.requestId}</dd>
      <dt>Supplier key</dt><dd>{payload.supplierKey}</dd>
      <dt>Document type</dt><dd>{payload.documentType}</dd>
      <dt>Document reference</dt><dd>{payload.documentReference}</dd>
      <dt>Physically received at</dt><dd>{new Date(payload.physicallyReceivedAt).toLocaleString()}</dd>
      <dt>Reason</dt><dd>{payload.reason}</dd>
      <dt>Evidence reference</dt><dd className="break-all">{payload.evidenceReference}</dd>
    </dl>
    <p className="font-medium">Supplier source lines</p>
    <ul className="space-y-1">{payload.lines.map(line => <li key={line.documentLineReference} className="break-all">
      Line {line.documentLineReference}: {line.quantity} EA · product {line.productId} · lot {line.lotId} · location {line.locationId} · expected version {line.expectedVersion}
    </li>)}</ul>
  </div>
}

function CommittedOutcome({ outcome, receiptOutcome }: {
  outcome: InventoryCommandIntentInspection['committedOutcome']
  receiptOutcome: InventoryCommandIntentInspection['committedReceiptOutcome']
}) {
  if (outcome) return <div className="space-y-1">
    <p>Movement {outcome.movementId} · position {outcome.positionId} · version {outcome.positionVersion}</p>
    <p>On hand {outcome.onHand}, reserved {outcome.reserved}, quarantined {outcome.quarantined}, other blocked {outcome.otherBlocked}, available {outcome.available}.</p>
  </div>
  if (receiptOutcome) return <div className="space-y-1">
    <p>Receipt {receiptOutcome.receiptId} · movement {receiptOutcome.movementId}</p>
    <ul>{receiptOutcome.lines.map(line => <li key={`${line.positionId}:${line.documentLineReference}`}>
      Line {line.documentLineReference}: {line.quantity} EA · position {line.positionId}, version {line.version}, on hand {line.onHand}, reserved {line.reserved}, quarantined {line.quarantined}, other blocked {line.otherBlocked}, available {line.available}.{line.allocationId && ` Quarantine allocation ${line.allocationId} is shared across this position's source lines.`}
    </li>)}</ul>
  </div>
  return null
}
