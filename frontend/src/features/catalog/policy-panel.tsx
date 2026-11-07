import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { getFoodSafetyPolicyApprovalEvidence, listFoodSafetyPolicyVersions, recordFoodSafetyPolicyReview, recordLotSafetyEvidence } from '@/api/food-safety'
import type { FoodSafetyPolicyVersion, RecordPolicyReview, RecordSafetyEvidence } from '@/api/food-safety'
import type { ManufacturerLot } from '@/api/lots'
import { Button } from '@/components/ui/button'
import { Field, inputClass } from '@/components/form-field'
import { useOrganization } from '../organizations/organization-context'
import { catalogRefresh, useCatalogScope } from './catalog-query'
import { Facts, Pager, Provenance, QueryNotice } from './catalog-ui'
import { DurableForm } from './durable-form'

export function PolicyPanel({ organizationId, category, temperature }: { organizationId: string, category: FoodSafetyPolicyVersion['productCategory'], temperature: FoodSafetyPolicyVersion['temperatureClass'] }) {
  const scope = useCatalogScope()
  const client = useQueryClient()
  const organization = useOrganization()
  const [offset, setOffset] = useState(0)
  const [editing, setEditing] = useState<{ previous: FoodSafetyPolicyVersion | null } | null>(null)
  const history = useQuery({ queryKey: [...scope, 'policy', category, temperature, offset], queryFn: ({ signal }) => listFoodSafetyPolicyVersions(organizationId, category, temperature, offset, signal), ...catalogRefresh })
  const currentPolicyId = history.data?.current?.id
  const approval = useQuery({ queryKey: [...scope, 'policy-approval', category, temperature, currentPolicyId], queryFn: ({ signal }) => getFoodSafetyPolicyApprovalEvidence(organizationId, category, temperature, currentPolicyId!, signal), enabled: currentPolicyId !== undefined, ...catalogRefresh })
  async function reload() { const result = await history.refetch(); if (result.error) throw result.error; setEditing(null) }
  return <section className="space-y-5" aria-label="Food-safety policy evidence"><h3 className="text-2xl font-semibold">Policy review · {category.replaceAll('_', ' ')} · {temperature}</h3><p className="text-sm text-muted-foreground">Policy review and independent approval are separate records. Scope uses both product category and declared temperature class; category is not safety approval.</p>
    {!history.data || history.isError || history.isFetching ? <QueryNotice title={history.isFetching ? 'Refreshing policy history…' : 'Policy history unavailable'} error={history.error} reload={() => { void history.refetch() }} /> : <>
      {history.data.current === null && <p>No policy review is recorded for this category and temperature scope.</p>}
      {history.data.items.map(policy => <article key={policy.id} className="space-y-3"><h4 className="text-lg font-semibold">Policy version {policy.version}</h4><Facts values={[
        ['Policy review status', `${policy.approvalStatus} — review does not grant operational approval`], ['Review evidence', <Provenance value={policy.reviewProvenance} />], ['Requested partial-date rule', policy.partialDateRule.replaceAll('_', ' ').toLowerCase()], ['Reason', policy.reason], ['Recorded at', policy.recordedAt], ['Policy version reference', policy.id],
      ]} /></article>)}<Pager label="policy versions" offset={offset} hasMore={history.data.hasMore} onChange={setOffset} />
      {history.data.current && <section className="space-y-3" aria-label="Current policy approval verification"><h4 className="text-lg font-semibold">Current policy approval evidence</h4>
        {!approval.data || approval.isError || approval.isFetching ? <QueryNotice title={approval.isFetching ? 'Refreshing approval verification…' : 'Approval verification unavailable'} error={approval.error} reload={() => { void approval.refetch() }} /> : <>
          <p role={approval.data.verification.outcome === 'VERIFIED' ? 'status' : 'note'}>{verificationMessage(approval.data.verification.outcome)}</p>
          <Facts values={[
            ['Verification outcome', approval.data.verification.outcome], ['Issuer', approval.data.verification.issuerId ?? 'Not submitted'], ['Signing key', approval.data.verification.keyId ?? 'Not submitted'],
            ['Allowed operations', approval.data.verification.allowedOperations?.join(', ') || 'None'], ['Validity', approval.data.verification.validFrom && approval.data.verification.validUntil ? `${approval.data.verification.validFrom} – ${approval.data.verification.validUntil}` : 'Not available'],
            ['Evidence reference', approval.data.verification.evidenceReference ?? 'Not submitted'], ['Revocation', approval.data.verification.revoked ? `${approval.data.verification.revokedAt ?? 'Recorded'} · ${approval.data.verification.revocationReason ?? ''}` : 'Not revoked'],
          ]} />
        </>}
      </section>}
      {organization.role === 'ORG_ADMIN' && !editing && <Button variant="outline" onClick={() => { setEditing({ previous: history.data.current }) }}>Record policy review</Button>}
    </>}
    {editing && <PolicyReviewForm organizationId={organizationId} category={category} temperature={temperature} previous={editing.previous} onRefresh={reload} onSaved={async () => {
      await client.invalidateQueries({ queryKey: [...scope, 'policy', category, temperature] }, { throwOnError: true }); await client.invalidateQueries({ queryKey: [...scope, 'eligibility'] }); setEditing(null)
    }} />}
  </section>
}
function verificationMessage(outcome: string | undefined) {
  switch (outcome) {
    case 'VERIFIED': return 'Cryptographic approval evidence is verified for this exact policy version. The signature does not establish the issuer’s professional qualifications.'
    case 'NOT_SUBMITTED': return 'No signed approval evidence is recorded for this policy version.'
    case 'TRUST_REGISTRY_DISABLED': return 'The deployment trust registry is disabled. This evidence cannot authorize an operation.'
    case 'TRUSTED_ISSUER_UNKNOWN': return 'The signer is not currently trusted by the deployment registry.'
    case 'TRUSTED_KEY_DISABLED': return 'The signing key has been disabled by the deployment trust registry.'
    case 'SIGNATURE_INVALID': return 'The signature or policy scope did not verify. This evidence cannot authorize an operation.'
    case 'NOT_YET_VALID': return 'This evidence is not yet valid.'
    case 'EXPIRED': return 'This evidence has expired.'
    case 'REVOKED': return 'This evidence has been revoked.'
    case 'POLICY_SCOPE_MISMATCH': return 'This evidence does not match the exact policy scope.'
    case 'OPERATION_NOT_APPROVED': return 'This evidence does not approve the requested operation.'
    default: return 'Approval verification is not available. Do not execute an operation from this evidence.'
  }
}
function PolicyReviewForm({ organizationId, category, temperature, previous, onRefresh, onSaved }: { organizationId: string, category: FoodSafetyPolicyVersion['productCategory'], temperature: FoodSafetyPolicyVersion['temperatureClass'], previous: FoodSafetyPolicyVersion | null, onRefresh: () => Promise<void>, onSaved: () => Promise<void> }) {
  const [rule, setRule] = useState<RecordPolicyReview['partialDateRule']>('NO_NORMALIZATION')
  const [source, setSource] = useState('')
  const [reference, setReference] = useState('')
  const [reason, setReason] = useState('')
  return <DurableForm title="Record policy review evidence" submitLabel="Save policy review" confirmation="I confirm this records review evidence, not independent policy approval."
    prepare={() => ({ requestId: crypto.randomUUID(), expectedVersion: previous?.version ?? 0, reason, partialDateRule: rule, reviewProvenance: { source, reference } })}
    execute={(body, signal) => recordFoodSafetyPolicyReview(organizationId, category, temperature, body, signal)} onSaved={onSaved} onRefresh={onRefresh}>
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
