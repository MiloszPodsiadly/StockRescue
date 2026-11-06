import { useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { Field, inputClass } from '@/components/form-field'
import { correctLotMetadata, registerLot } from '@/api/lots'
import type { ManufacturerLot, RegisterLot } from '@/api/lots'
import { DurableForm } from './durable-form'

type Marking = RegisterLot['dateMarking']
export function LotForm({ organizationId, productId, lot, onRefresh, onSaved, onCancel }: {
  organizationId: string, productId: string, lot?: ManufacturerLot, onRefresh: () => Promise<void>, onSaved?: () => Promise<void>, onCancel?: () => void,
}) {
  const navigate = useNavigate()
  // The reviewed snapshot stays fixed while a request may be committed but unacknowledged.
  const [original] = useState(lot)
  const [code, setCode] = useState(lot?.lotCode ?? '')
  const [identity, setIdentity] = useState<RegisterLot['identityState']>(lot?.identityState ?? 'CANDIDATE')
  const [manufacturer, setManufacturer] = useState(lot?.manufacturerIdentityKey ?? '')
  const [manufacturerSource, setManufacturerSource] = useState(lot?.manufacturerProvenance?.source ?? '')
  const [manufacturerReference, setManufacturerReference] = useState(lot?.manufacturerProvenance?.reference ?? '')
  const [verified, setVerified] = useState(false)
  const [label, setLabel] = useState(lot?.dateMarking.labeledValue ?? '')
  const [type, setType] = useState<Marking['type']>(lot?.dateMarking.type ?? 'UNKNOWN')
  const [precision, setPrecision] = useState<Marking['precision']>(lot?.dateMarking.precision ?? 'UNKNOWN')
  const [labelSource, setLabelSource] = useState(lot?.dateMarking.provenance?.source ?? '')
  const [labelReference, setLabelReference] = useState(lot?.dateMarking.provenance?.reference ?? '')
  const [reason, setReason] = useState('')
  const correction = Boolean(original)
  function data() {
    return {
      identityState: identity, ...(manufacturer ? { manufacturerIdentityKey: manufacturer } : {}),
      manufacturerProvenance: { source: manufacturerSource, ...(manufacturerReference ? { reference: manufacturerReference } : {}) },
      manufacturerVerified: identity === 'CONFIRMED' && verified,
      dateMarking: { ...(label !== '' ? { labeledValue: label } : {}), type, precision,
        ...(labelSource ? { provenance: { source: labelSource, ...(labelReference ? { reference: labelReference } : {}) } } : {}) },
    }
  }
  return <DurableForm title={correction ? 'Review lot correction' : 'Register manufacturer lot'} submitLabel={correction ? 'Save reviewed correction' : 'Save manufacturer lot'}
    confirmation={correction ? 'I reviewed the replacement evidence and confirm this lot correction.' : undefined}
    prepare={() => ({ requestId: crypto.randomUUID(), metadata: data(), lotCode: code, expectedVersion: original?.version ?? 0, reason })}
    execute={async (body, signal) => original
      ? (await correctLotMetadata(organizationId, original.id, { requestId: body.requestId, ...body.metadata, expectedVersion: body.expectedVersion, reason: body.reason, evidenceReviewed: true }, signal)).lot
      : registerLot(organizationId, { requestId: body.requestId, ...body.metadata, productId, lotCode: body.lotCode }, signal)}
    onCancel={onCancel} onRefresh={onRefresh} onSaved={async result => { if (onSaved) await onSaved(); else await navigate({ to: '/organizations/$organizationId/catalog/lots/$lotId', params: { organizationId, lotId: result.id } }) }}>
    <Field label="Manufacturer lot code">{id => <input id={id} required maxLength={128} value={code} readOnly={correction} onChange={event => { setCode(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Manufacturer identity status">{id => <select id={id} value={identity} onChange={event => { setIdentity(event.target.value as RegisterLot['identityState']); setVerified(false) }} className={inputClass}>
      {original?.identityState !== 'CONFIRMED' && <option value="CANDIDATE">Candidate — identity unverified</option>}<option value="CONFIRMED">Confirmed manufacturer identity</option>
    </select>}</Field>
    <Field label="Manufacturer identity reference" help="An exact manufacturer reference, not a supplier's display name. Known identity references stay fixed.">{(id, description) => <input id={id} maxLength={200} required={identity === 'CONFIRMED'} readOnly={Boolean(original?.manufacturerIdentityKey) || Boolean(original && identity === 'CANDIDATE')} aria-describedby={description} value={manufacturer} onChange={event => { setManufacturer(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Manufacturer evidence source">{id => <input id={id} required maxLength={256} readOnly={Boolean(original?.manufacturerProvenance && (original.identityState === 'CONFIRMED' || identity === 'CANDIDATE'))} value={manufacturerSource} onChange={event => { setManufacturerSource(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Manufacturer evidence reference">{id => <input id={id} required={identity === 'CONFIRMED'} maxLength={256} value={manufacturerReference} onChange={event => { setManufacturerReference(event.target.value) }} className={inputClass} />}</Field>
    {identity === 'CONFIRMED' && <label className="flex items-start gap-3 text-sm"><input type="checkbox" required checked={verified} onChange={event => { setVerified(event.target.checked) }} />I checked the manufacturer identity against this source. This does not approve food safety.</label>}
    <Field label="Original date label" help="Copy the label exactly. Partial month or year markings keep their original precision.">{(id, description) => <input id={id} maxLength={256} value={label} aria-describedby={description} onChange={event => { setLabel(event.target.value) }} className={inputClass} />}</Field>
    <div className="grid gap-5 sm:grid-cols-2"><Field label="Date marking type">{id => <select id={id} value={type} onChange={event => { setType(event.target.value as Marking['type']) }} className={inputClass}><option value="UNKNOWN">Unknown</option><option value="USE_BY">Use by</option><option value="BEST_BEFORE">Best before</option><option value="BEST_BEFORE_END">Best before end</option></select>}</Field>
      <Field label="Date precision">{id => <select id={id} value={precision} onChange={event => { setPrecision(event.target.value as Marking['precision']) }} className={inputClass}><option value="UNKNOWN">Unknown</option><option value="DAY">Day</option><option value="MONTH">Month</option><option value="YEAR">Year</option></select>}</Field></div>
    <Field label="Date label source">{id => <input id={id} required={Boolean(labelReference)} maxLength={256} value={labelSource} onChange={event => { setLabelSource(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Date label reference">{id => <input id={id} maxLength={256} value={labelReference} onChange={event => { setLabelReference(event.target.value) }} className={inputClass} />}</Field>
    {correction && <Field label="Reason for lot correction">{id => <textarea id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>}
  </DurableForm>
}
