import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { evaluateLotEligibility } from '@/api/food-safety'
import type { Eligibility, EligibilityContext } from '@/api/food-safety'
import type { ManufacturerLot } from '@/api/lots'
import type { Store } from '@/api/stores'
import { listStorageLocations } from '@/api/storage-locations'
import { listZones } from '@/api/configuration'
import { Button } from '@/components/ui/button'
import { Field, inputClass } from '@/components/form-field'
import { catalogRefresh, useCatalogScope } from './catalog-query'
import { Facts, Pager, QueryNotice } from './catalog-ui'
import { reasonMessages, nextActions } from './eligibility-messages'
import { allowedAssessmentNotice, platformRulesetDigest, platformRulesetId } from './eligibility-ruleset'

type ApprovalOutcome = NonNullable<Eligibility['approvalVerification']>['outcome']
type ClassificationOutcome = NonNullable<Eligibility['classificationVerification']>['outcome']

export function EligibilityPanel({ organizationId, lot, store }: { organizationId: string, lot: ManufacturerLot, store: Store }) {
  const scope = useCatalogScope()
  const [mode, setMode] = useState('UNKNOWN')
  const [choice, setChoice] = useState('')
  const [operation, setOperation] = useState<Eligibility['operation']>('SALE')
  const [requested, setRequested] = useState<{ context: EligibilityContext, operation: Eligibility['operation'], attempt: number } | null>(null)
  const result = useQuery({ queryKey: [...scope, 'eligibility', lot.id, lot.version, store.id, store.version, requested], enabled: requested !== null,
    queryFn: ({ signal }) => { if (!requested) throw new Error('Select assessment context'); return evaluateLotEligibility(organizationId, lot.id, requested.context, requested.operation, signal) }, ...catalogRefresh })
  return <section aria-label="Food-safety assessment" className="space-y-5"><h3 className="text-2xl font-semibold">Food-safety assessment</h3><p className="text-sm text-muted-foreground">A current assessment for {store.name}, using {store.zoneId}. Review never grants permission to execute an operation.</p>
    <form className="space-y-5 rounded-2xl border border-border p-5" onSubmit={event => { event.preventDefault(); setRequested(previous => ({ context: { storeId: store.id, ...(mode === 'LOCATION' && choice ? { locationId: choice } : {}), ...(mode === 'ZONE' && choice ? { zoneId: choice } : {}) }, operation, attempt: (previous?.attempt ?? 0) + 1 })) }}>
      <Field label="Operation being assessed">{id => <select id={id} value={operation} onChange={event => { setOperation(event.target.value as Eligibility['operation']); setRequested(null) }} className={inputClass}><option value="SALE">Sale</option><option value="TRANSFER">Transfer</option></select>}</Field>
      <Field label="Storage evidence context">{id => <select id={id} value={mode} onChange={event => { setMode(event.target.value); setChoice(''); setRequested(null) }} className={inputClass}><option value="UNKNOWN">No declared storage context</option><option value="LOCATION">Storage location</option><option value="ZONE">Storage zone</option></select>}</Field>
      {mode !== 'UNKNOWN' && <StorageChoice key={mode} organizationId={organizationId} storeId={store.id} location={mode === 'LOCATION'} value={choice} onChange={value => { setChoice(value); setRequested(null) }} />}
      <Button type="submit" disabled={mode !== 'UNKNOWN' && !choice}>Assess current evidence</Button>
    </form>
    {requested && (result.isFetching || !result.data || result.isError ? <QueryNotice title={result.isFetching ? 'Recomputing assessment…' : 'Assessment unavailable'} error={result.error} reload={() => { void result.refetch() }} /> : <EligibilityResult value={result.data} />)}
  </section>
}
function StorageChoice({ organizationId, storeId, location, value, onChange }: { organizationId: string, storeId: string, location: boolean, value: string, onChange: (value: string) => void }) {
  const scope = useCatalogScope()
  const [offset, setOffset] = useState(0)
  const options = useQuery({ queryKey: [...scope, 'storage-choices', storeId, location, offset], queryFn: async ({ signal }) => {
    if (location) { const page = await listStorageLocations(organizationId, storeId, true, '', offset, signal); return { items: page.items.map(item => ({ id: item.id, label: `${item.code} · ${item.name} · ${item.usable ? item.temperatureClass : 'Unusable configuration'}` })), hasMore: page.hasMore } }
    const page = await listZones(organizationId, storeId, true, offset, signal); return { items: page.items.map(item => ({ id: item.id, label: `${item.name} · ${item.temperatureClass}${item.active ? '' : ' · Inactive'}` })), hasMore: page.hasMore }
  }, ...catalogRefresh })
  return <div className="space-y-3"><Field label={location ? 'Assessment location' : 'Assessment zone'}>{id => <select id={id} required value={value} onChange={event => { onChange(event.target.value) }} className={inputClass}><option value="">Choose configuration</option>{value && !options.data?.items.some(item => item.id === value) && <option value={value}>Previous selection — current status will be checked</option>}{options.data?.items.map(item => <option key={item.id} value={item.id}>{item.label}</option>)}</select>}</Field>{options.isError && <p role="alert">Storage choices could not be refreshed.</p>}{options.data && <Pager label="storage choices" offset={offset} hasMore={options.data.hasMore} onChange={setOffset} size={location ? 25 : 100} />}</div>
}
export function EligibilityResult({ value }: { value: Eligibility }) {
  const signedPolicyVerified = value.approvalVerification?.outcome === 'VERIFIED'
    && (value.approvalVerification.allowedOperations ?? []).includes(value.operation)
    && value.evidence.policy === 'INDEPENDENTLY_VERIFIED'
    && value.approvalVerification.signedRulesetId === platformRulesetId
    && value.approvalVerification.signedRulesetDigest === platformRulesetDigest
    && value.approvalVerification.activeRulesetId === platformRulesetId
    && value.approvalVerification.activeRulesetDigest === platformRulesetDigest
    && value.classificationVerification?.outcome === 'VERIFIED'
    && value.evidence.classification === 'INDEPENDENTLY_VERIFIED'
  const operatorConfirmationCurrent = value.operatorClassificationStatus === 'CURRENT_OPERATOR_CONFIRMED'
    && value.operatorClassificationConfirmation?.productId === value.productId
    && value.operatorClassificationConfirmation.sku === value.classificationVerification?.currentSku
    && value.operatorClassificationConfirmation.productVersion === value.productVersion
    && value.operatorClassificationConfirmation.confirmedCategory === value.productCategory
    && value.operatorClassificationConfirmation.confirmedCategory === value.classificationVerification?.currentCategory
    && value.operatorClassificationConfirmation.confirmedTemperatureClass === value.productTemperatureClass
    && value.operatorClassificationConfirmation.sourceType !== null
    && Boolean(value.operatorClassificationConfirmation.evidenceReference?.trim())
  const signedClassificationCurrent = value.classificationVerification?.outcome === 'VERIFIED'
    && value.classificationVerification.attestationId !== null
    && value.classificationVerification.productId === value.productId
    && value.classificationVerification.currentProductVersion === value.productVersion
    && value.classificationVerification.signedProductVersion === value.productVersion
    && value.classificationVerification.currentCategory === value.productCategory
    && value.classificationVerification.signedCategory === value.productCategory
  const profileConfirmation = value.operatorClassificationConfirmation
  const profileEvidenceSource = profileConfirmation?.sourceType === 'ORIGINAL_PACKAGE' || profileConfirmation?.sourceType === 'SUPPLIER_DOCUMENT'
  const platformScopeSupported = value.productCategory === 'BAKERY' && value.productTemperatureClass === 'AMBIENT'
    && operatorConfirmationCurrent && profileConfirmation?.productProfile === 'PACKAGED_SHELF_STABLE_DRY_BAKERY' && profileEvidenceSource
  const recordedEvidenceComplete = value.evidence.date === 'COMPLETE_LABEL'
    && value.evidence.recall === 'NOT_REPORTED_IN_REVIEWED_EVIDENCE'
    && value.evidence.damage === 'NOT_REPORTED_IN_REVIEWED_EVIDENCE'
    && value.evidence.storage === 'DECLARED_COMPATIBLE'
    && hasEvidenceSource(value.evidence.manufacturerSource)
    && hasEvidenceSource(value.evidence.dateLabelSource)
    && hasEvidenceSource(value.evidence.safetyStatusSource)
  const classificationEvidenceCurrent = value.evidence.classification === 'OPERATOR_CONFIRMED' ? operatorConfirmationCurrent
    : value.evidence.classification === 'INDEPENDENTLY_VERIFIED' && operatorConfirmationCurrent && signedClassificationCurrent
  const platformEvidenceComplete = platformScopeSupported && classificationEvidenceCurrent
  const basisVerified = value.assessmentBasis === 'SIGNED_EXTERNAL_POLICY' ? signedPolicyVerified && signedClassificationCurrent && operatorConfirmationCurrent
    : value.assessmentBasis === 'PLATFORM_RULESET' ? platformEvidenceComplete : false
  const rulesetIdentified = value.rulesetId === platformRulesetId && value.evaluatorVersion === platformRulesetId
    && value.rulesetDigest === platformRulesetDigest
  const verifiedForOperation = platformScopeSupported && recordedEvidenceComplete && basisVerified && rulesetIdentified
  const allowed = value.decision === 'ALLOWED' && verifiedForOperation && value.reasonCodes.length === 0 && value.assessmentNotice === allowedAssessmentNotice
  const unexpectedApproval = value.decision === 'ALLOWED' && !allowed
  const invalidSignedBasis = value.assessmentBasis === 'SIGNED_EXTERNAL_POLICY' && !basisVerified
  return <section aria-label="Assessment result" className={`space-y-4 rounded-2xl border p-5 ${value.decision === 'DENIED' ? 'border-destructive/50' : 'border-border'}`}>
    <h4 className="text-xl font-semibold">{value.decision === 'DENIED' ? 'Denied' : allowed ? 'Passed limited checks' : 'Requires review'}</h4>
    {unexpectedApproval && <p role="alert">The returned allowed decision does not satisfy every approval and evidence check. Do not execute an operation from this result.</p>}
    {invalidSignedBasis && <p role="alert">The assessment names a signed external policy, but the required signatures are not verified for this product revision, operation and active ruleset. Do not rely on that assessment.</p>}
    {allowed && <p className="rounded-xl border border-border p-4 text-sm font-medium">{value.assessmentNotice}</p>}
    <section aria-label="Optional external evidence" className="space-y-2 rounded-xl border border-border p-4 text-sm">
      <h5 className="font-medium">Optional external evidence</h5>
      <p><span className="font-medium">Policy signature:</span> {policyOutcomeMessage(value.approvalVerification?.outcome ?? 'NOT_SUBMITTED')}</p>
      <p><span className="font-medium">Signed product category:</span> {classificationOutcomeMessage(value.classificationVerification?.outcome ?? 'NOT_SUBMITTED')}</p>
      <p className="text-muted-foreground">A signed category is verified only for its organization, product, SKU and exact revision. It does not independently verify declared temperature, product profile, label contents or a physical lot. Temperature and product profile remain operator-confirmed from the cited source.</p>
      <p className="text-muted-foreground">These signatures are supplementary evidence; the active platform ruleset determines a platform assessment.</p>
    </section>
    <p className="text-sm text-muted-foreground">{value.operation} identifies the assessment context. This screen does not authorize or execute that operation.</p>
    <p className="text-sm text-muted-foreground">“Not reported in reviewed evidence” describes the source reviewed for this lot; it does not certify that no recall exists.</p>
    <p className="font-medium">{nextActions[value.nextAction]}</p><ul className="list-inside list-disc space-y-2 text-sm">{value.reasonCodes.map(reason => <li key={reason}>{reasonMessages[reason]}</li>)}</ul>
    <Facts values={[["Assessment time", value.evaluatedAt], ['Store local date', value.storeLocalDate], ['Store time zone', value.storeTimeZone], ['Recall evidence', value.evidence.recall.replaceAll('_', ' ').toLowerCase()], ['Recall source', evidenceSource(value.evidence.safetyStatusSource)], ['Damage evidence', value.evidence.damage.replaceAll('_', ' ').toLowerCase()], ['Damage source', evidenceSource(value.evidence.safetyStatusSource)], ['Lot version assessed', value.lotVersion], ['Manufacturer source', evidenceSource(value.evidence.manufacturerSource)], ['Date label source', evidenceSource(value.evidence.dateLabelSource)], ['Product category', value.productCategory.replaceAll('_', ' ')], ['Product temperature class', value.productTemperatureClass], ['Product profile', value.operatorClassificationConfirmation?.productProfile.replaceAll('_', ' ').toLowerCase() ?? 'Not assessed'], ['Ruleset basis', value.assessmentBasis.replaceAll('_', ' ').toLowerCase()], ['Ruleset identity', `${value.rulesetId} · ${value.rulesetDigest}`], ['Policy version', value.policy ? `${value.policy.version} · ${value.policy.id}` : 'No recorded policy'], ['Policy evidence', value.evidence.policy === 'MISSING' ? 'Missing' : value.evidence.policy === 'INDEPENDENTLY_VERIFIED' ? 'Cryptographically verified' : value.evidence.policy === 'PLATFORM_RULESET' ? 'Platform ruleset' : 'Recorded, unverified'], ['Approval verification', value.approvalVerification?.outcome ?? 'Not available'], ['Category evidence status', classificationEvidenceLabel(value)], ['Operator attributes status', value.operatorClassificationStatus.replaceAll('_', ' ').toLowerCase()], ['Operator source type', value.operatorClassificationConfirmation?.sourceType?.replaceAll('_', ' ') ?? 'Not supplied'], ['Operator source reference', value.operatorClassificationConfirmation?.evidenceReference ?? 'Not supplied'], ['Classification verification', value.classificationVerification?.outcome ?? 'Not available'], ['Signed category and version', value.classificationVerification?.signedCategory && value.classificationVerification.signedProductVersion !== null ? `${value.classificationVerification.signedCategory} · ${value.classificationVerification.signedProductVersion}` : 'Not available'], ['Date evidence', value.evidence.date.replaceAll('_', ' ').toLowerCase()], ['Storage evidence', value.evidence.storage.replaceAll('_', ' ').toLowerCase()]]} />
    <p className="text-sm text-muted-foreground">This is evidence at the displayed time, not a reusable permission or a physical stock hold.</p>
  </section>
}
function policyOutcomeMessage(outcome: ApprovalOutcome) {
  switch (outcome) {
    case 'NOT_SUBMITTED': return 'No external policy approval was submitted.'
    case 'VERIFIED': return 'The optional policy signature is verified for this policy and operation.'
    case 'RULESET_MISMATCH': return 'The signature is for a different ruleset and is not verified for the active one.'
    case 'LEGACY_ATTESTATION': return 'The signature predates ruleset binding and is not independently verified.'
    case 'REVOKED': return 'The policy signature has been revoked.'
    case 'EXPIRED': return 'The policy signature has expired.'
    case 'TRUST_REGISTRY_DISABLED': return 'The trust registry is disabled, so the policy signature cannot be verified.'
    case 'TRUSTED_KEY_DISABLED': return 'The signing key is disabled, so the policy signature is not independently verified.'
    case 'OPERATION_NOT_APPROVED': return 'The signature does not include this operation.'
    case 'TRUSTED_ISSUER_UNKNOWN': return 'The signing issuer is not trusted by this deployment.'
    case 'SIGNATURE_INVALID': return 'The policy signature is invalid.'
    case 'NOT_YET_VALID': return 'The policy signature is not yet valid.'
    case 'POLICY_SCOPE_MISMATCH': return 'The signature does not match the current policy scope.'
  }
}
function classificationOutcomeMessage(outcome: ClassificationOutcome) {
  switch (outcome) {
    case 'NOT_SUBMITTED': return 'No independent signed category evidence was submitted.'
    case 'VERIFIED': return 'Signed product category verified for this revision; temperature and product profile are operator-confirmed, not independently checked.'
    case 'PRODUCT_SCOPE_MISMATCH': return 'The signed category belongs to a different product revision and is not current.'
    case 'REVOKED': return 'The signed product category evidence has been revoked.'
    case 'EXPIRED': return 'The signed product category evidence has expired.'
    case 'TRUST_REGISTRY_DISABLED': return 'The trust registry is disabled, so the signed category cannot be verified.'
    case 'TRUSTED_KEY_DISABLED': return 'The signing key is disabled, so the signed category is not independently verified.'
    case 'TRUSTED_ISSUER_UNKNOWN': return 'The signing issuer is not trusted by this deployment.'
    case 'SIGNATURE_INVALID': return 'The signed category evidence has an invalid signature.'
    case 'NOT_YET_VALID': return 'The signed category evidence is not yet valid.'
  }
}
function evidenceSource(value: Eligibility['evidence']['manufacturerSource']) {
  return value ? `${value.source}${value.reference ? ` · ${value.reference}` : ''} (operator-supplied)` : 'Not supplied'
}
function classificationEvidenceLabel(value: Eligibility) {
  if (value.evidence.classification === 'INDEPENDENTLY_VERIFIED') return 'Signed product category verified for the current product revision only'
  if (value.evidence.classification === 'OPERATOR_CONFIRMED') return 'Operator-confirmed from cited source; not independently verified'
  if (value.evidence.classification === 'RECORDED_UNVERIFIED') return 'Recorded, but not independently verified'
  return 'Missing'
}
function hasEvidenceSource(value: Eligibility['evidence']['manufacturerSource']) {
  return Boolean(value?.source.trim() && value.reference?.trim())
}
