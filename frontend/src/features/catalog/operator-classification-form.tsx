import { useState } from 'react'
import { confirmOperatorClassification } from '@/api/products'
import type { ClassificationEvidenceSource, OperatorClassificationConfirmationRequest, Product } from '@/api/products'
import { Field, inputClass } from '@/components/form-field'
import { DurableForm } from './durable-form'

export function OperatorClassificationForm({ organizationId, product, onSaved, onRefresh, onCancel }: {
  organizationId: string, product: Product, onSaved: (result: Awaited<ReturnType<typeof confirmOperatorClassification>>) => Promise<void>,
  onRefresh: () => Promise<void>, onCancel: () => void,
}) {
  const [category, setCategory] = useState<Product['category']>(product.category)
  const [sourceType, setSourceType] = useState<ClassificationEvidenceSource | ''>('')
  const [evidenceReference, setEvidenceReference] = useState('')
  const [reason, setReason] = useState('')
  const hasSource = sourceType !== '' && evidenceReference.trim().length > 0
  const sourcePairValid = category === 'UNCLASSIFIED' ? hasSource || (sourceType === '' && evidenceReference.trim() === '') : hasSource
  const canSubmit = sourcePairValid && reason.trim().length > 0
  return <DurableForm title="Confirm product classification" submitLabel="Save classification evidence" canSubmit={canSubmit}
    confirmation={category === 'UNCLASSIFIED' ? 'I reviewed the available source and leave the product category unclassified.' : 'I inspected the cited source and confirm only this descriptive product category; this is not independent verification or food-safety approval.'}
    prepare={() => ({ requestId: crypto.randomUUID(), expectedProductVersion: product.version, category,
      sourceType: sourceType || null, evidenceReference: evidenceReference.trim() || null, reason: reason.trim() } satisfies OperatorClassificationConfirmationRequest)}
    execute={(body, signal) => confirmOperatorClassification(organizationId, product.id, body, signal)} onSaved={onSaved} onRefresh={onRefresh} onCancel={onCancel}>
    <p className="text-sm text-muted-foreground">The source reference is retained with your account and the resulting product version. An unknown category may be recorded without a source, but remains under review.</p>
    <Field label="Product category from the reviewed source" help="Choose only what the package or supplier evidence supports.">{id => <select id={id} value={category} onChange={event => { setCategory(event.target.value as Product['category']) }} className={inputClass}><option value="UNCLASSIFIED">Unclassified — category unknown</option><option value="DAIRY">Dairy</option><option value="BAKERY">Bakery</option></select>}</Field>
    <Field label="Source type" help="For a known category, cite an original package, supplier document or another identified source.">{id => <select id={id} value={sourceType} onChange={event => { setSourceType(event.target.value as ClassificationEvidenceSource | '') }} className={inputClass}><option value="">No source recorded</option><option value="ORIGINAL_PACKAGE">Original package</option><option value="SUPPLIER_DOCUMENT">Supplier document</option><option value="OTHER">Other identified evidence</option></select>}</Field>
    <Field label="Source reference" help="Enter a short document, label or evidence reference. Do not enter confidential document contents.">{id => <input id={id} maxLength={256} value={evidenceReference} onChange={event => { setEvidenceReference(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Reason for this confirmation">{id => <textarea id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
  </DurableForm>
}
