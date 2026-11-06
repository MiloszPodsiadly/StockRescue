import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { listFoodSafetyPolicyVersions, recordFoodSafetyPolicyReview, recordLotSafetyEvidence } from '@/api/food-safety'
import type { FoodSafetyPolicyVersion, RecordPolicyReview, RecordSafetyEvidence } from '@/api/food-safety'
import type { ManufacturerLot } from '@/api/lots'
import { Button } from '@/components/ui/button'
import { Field, inputClass } from '@/components/form-field'
import { useOrganization } from '../organizations/organization-context'
import { catalogRefresh, useCatalogScope } from './catalog-query'
import { Facts, Pager, Provenance, QueryNotice } from './catalog-ui'
import { DurableForm } from './durable-form'

export function PolicyPanel({ organizationId, temperature }: { organizationId: string, temperature: FoodSafetyPolicyVersion['temperatureClass'] }) {
  const scope = useCatalogScope()
  const client = useQueryClient()
  const organization = useOrganization()
  const [offset, setOffset] = useState(0)
  const [editing, setEditing] = useState<{ previous: FoodSafetyPolicyVersion | null } | null>(null)
  const history = useQuery({ queryKey: [...scope, 'policy', temperature, offset], queryFn: ({ signal }) => listFoodSafetyPolicyVersions(organizationId, temperature, offset, signal), ...catalogRefresh })
  async function reload() { const result = await history.refetch(); if (result.error) throw result.error; setEditing(null) }
  return <section className="space-y-5" aria-label="Food-safety policy evidence"><h3 className="text-2xl font-semibold">Policy review · {temperature}</h3><p className="text-sm text-muted-foreground">Recorded review evidence remains unverified. Independent class-specific approval is required before executable food-safety permission.</p>
    {!history.data || history.isError || history.isFetching ? <QueryNotice title={history.isFetching ? 'Refreshing policy history…' : 'Policy history unavailable'} error={history.error} reload={() => { void history.refetch() }} /> : <>
      {history.data.current === null && <p>No class-specific policy review is recorded.</p>}
      {history.data.items.map(policy => <article key={policy.id} className="space-y-3"><h4 className="text-lg font-semibold">Policy version {policy.version}</h4><Facts values={[
        ['Approval verification', 'Unverified — independent review required'], ['Review evidence', <Provenance value={policy.reviewProvenance} />], ['Requested partial-date rule', policy.partialDateRule.replaceAll('_', ' ').toLowerCase()], ['Reason', policy.reason], ['Recorded at', policy.recordedAt], ['Policy version reference', policy.id],
      ]} /></article>)}<Pager label="policy versions" offset={offset} hasMore={history.data.hasMore} onChange={setOffset} />
      {organization.role === 'ORG_ADMIN' && !editing && <Button variant="outline" onClick={() => { setEditing({ previous: history.data.current }) }}>Record policy review</Button>}
    </>}
    {editing && <PolicyReviewForm organizationId={organizationId} temperature={temperature} previous={editing.previous} onRefresh={reload} onSaved={async () => {
      await client.invalidateQueries({ queryKey: [...scope, 'policy', temperature] }, { throwOnError: true }); await client.invalidateQueries({ queryKey: [...scope, 'eligibility'] }); setEditing(null)
    }} />}
  </section>
}
function PolicyReviewForm({ organizationId, temperature, previous, onRefresh, onSaved }: { organizationId: string, temperature: FoodSafetyPolicyVersion['temperatureClass'], previous: FoodSafetyPolicyVersion | null, onRefresh: () => Promise<void>, onSaved: () => Promise<void> }) {
  const [rule, setRule] = useState<RecordPolicyReview['partialDateRule']>('NO_NORMALIZATION')
  const [source, setSource] = useState('')
  const [reference, setReference] = useState('')
  const [reason, setReason] = useState('')
  return <DurableForm title="Record policy review evidence" submitLabel="Save policy review" confirmation="I confirm this records review evidence, not independent policy approval."
    prepare={() => ({ requestId: crypto.randomUUID(), expectedVersion: previous?.version ?? 0, reason, partialDateRule: rule, reviewProvenance: { source, reference } })}
    execute={(body, signal) => recordFoodSafetyPolicyReview(organizationId, temperature, body, signal)} onSaved={onSaved} onRefresh={onRefresh}>
    <Field label="Requested partial-date rule">{id => <select id={id} value={rule} onChange={event => { setRule(event.target.value as RecordPolicyReview['partialDateRule']) }} className={inputClass}><option value="NO_NORMALIZATION">No normalization</option><option value="END_OF_LABELED_PERIOD">Period end — requested, unverified</option></select>}</Field>
    <Field label="Policy review source">{id => <input id={id} required maxLength={256} value={source} onChange={event => { setSource(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Policy review document reference">{id => <input id={id} required maxLength={256} value={reference} onChange={event => { setReference(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Reason for policy review">{id => <textarea id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
  </DurableForm>
}
export function SafetyEvidenceForm({ organizationId, lot, onRefresh, onSaved }: { organizationId: string, lot: ManufacturerLot, onRefresh: () => Promise<void>, onSaved: () => Promise<void> }) {
  const [recall, setRecall] = useState(lot.recallState)
  const [damage, setDamage] = useState(lot.damageState)
  const [source, setSource] = useState('')
  const [reference, setReference] = useState('')
  const [reason, setReason] = useState('')
  return <DurableForm title="Record safety status evidence" submitLabel="Save safety status evidence" confirmation="I reviewed the notice and confirm this safety evidence. Recall and damage blocks remain recorded."
    prepare={() => ({ requestId: crypto.randomUUID(), expectedVersion: lot.version, recallState: recall, damageState: damage, reason, provenance: { source, reference } })}
    execute={(body, signal) => recordLotSafetyEvidence(organizationId, lot.id, body, signal)} onSaved={onSaved} onRefresh={onRefresh}>
    <p className="text-sm text-muted-foreground">A report of no known issue is source evidence, not food-safety approval.</p>
    <Field label="Recall evidence">{id => <select id={id} value={recall} onChange={event => { setRecall(event.target.value as RecordSafetyEvidence['recallState']) }} className={inputClass}>{lot.recallState !== 'RECALLED' && <><option value="UNKNOWN">Unknown</option><option value="NOT_REPORTED">Not reported in this source</option></>}<option value="RECALLED">Recall recorded</option></select>}</Field>
    <Field label="Damage evidence">{id => <select id={id} value={damage} onChange={event => { setDamage(event.target.value as RecordSafetyEvidence['damageState']) }} className={inputClass}>{lot.damageState !== 'DAMAGED' && <><option value="UNKNOWN">Unknown</option><option value="NOT_REPORTED">Not reported in this source</option></>}<option value="DAMAGED">Damage recorded</option></select>}</Field>
    <Field label="Safety notice source">{id => <input id={id} required maxLength={256} value={source} onChange={event => { setSource(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Safety notice reference">{id => <input id={id} required maxLength={256} value={reference} onChange={event => { setReference(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Reason for safety evidence">{id => <textarea id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
  </DurableForm>
}
