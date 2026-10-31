import { Button } from '@/components/ui/button'
import { Field, inputClass } from '@/components/form-field'
import { panelClass } from './settings-fields'
import type { ScheduleReview } from './scheduled-cost-history'

export function ScheduleCancellationReview({
  review,
  reason,
  confirmed,
  saving,
  onReasonChange,
  onConfirmedChange,
  onCancel,
  onClose,
}: {
  review: ScheduleReview
  reason: string
  confirmed: boolean
  saving: boolean
  onReasonChange: (value: string) => void
  onConfirmedChange: (value: boolean) => void
  onCancel: () => void
  onClose: () => void
}) {
  const independent = review.kind === 'independent'
  const count = review.revisions.length

  return <section role="region" aria-labelledby="schedule-resolution-heading" className={`${panelClass} border-primary/40`}>
    <h3 id="schedule-resolution-heading" className="text-xl font-semibold">
      {independent ? 'Review and cancel scheduled costs' : 'Review future costs before changing lane zones'}
    </h3>
    <p className="text-sm">
      {independent
        ? 'Confirming cancels every future schedule currently attached to this lane. The lane configuration and storage zones will not change.'
        : 'Saving the new zone pair is blocked while these scheduled costs remain. Confirming below cancels every future schedule currently attached to this lane. The zone change will remain a separate action after the lane and history are reloaded.'}
    </p>
    <ul className="space-y-3" aria-label="Scheduled cost revisions to cancel">{review.revisions.map(revision => <li key={revision.id} className="rounded-xl border border-border p-4">
      <p className="font-semibold">Revision {revision.revision} · {revision.id}</p>
      <p className="break-words">{revision.costAmount} {revision.currencyCode} · {revision.durationMinutes} minutes</p>
      <p className="break-words text-sm text-muted-foreground">Effective time (UTC): {revision.effectiveAt}</p>
    </li>)}</ul>
    <Field label="Reason for cancelling scheduled costs" help="Enter at least 10 characters describing why every listed future cost should be cancelled.">{(id, description) => <textarea id={id} aria-describedby={description} required minLength={10} maxLength={500} value={reason} disabled={saving} onChange={event => { onReasonChange(event.target.value) }} className={inputClass} />}</Field>
    <label className="flex gap-3 text-sm"><input type="checkbox" checked={confirmed} disabled={saving} onChange={event => { onConfirmedChange(event.target.checked) }} />I reviewed the listed revisions and confirm cancelling all of them.</label>
    <div className="flex flex-wrap gap-3">
      <Button type="button" disabled={saving || reason.trim().length < 10 || !confirmed} onClick={onCancel}>{saving ? 'Reconciling scheduled costs…' : `Cancel all ${count} scheduled revision${count === 1 ? '' : 's'}`}</Button>
      <Button type="button" variant="outline" disabled={saving} onClick={onClose}>{independent ? 'Close review without cancelling' : 'Keep schedule and review lane'}</Button>
    </div>
  </section>
}
