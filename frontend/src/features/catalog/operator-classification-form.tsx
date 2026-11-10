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
  const [temperatureClass, setTemperatureClass] = useState<Product['temperatureClass']>(product.temperatureClass)
  const [productProfile, setProductProfile] = useState<OperatorClassificationConfirmationRequest['productProfile']>('NOT_ASSESSED')
  const [sourceType, setSourceType] = useState<ClassificationEvidenceSource | ''>('')
  const [evidenceReference, setEvidenceReference] = useState('')
  const [reason, setReason] = useState('')
  const hasSource = sourceType !== '' && evidenceReference.trim().length > 0
  const profileSourceValid = productProfile !== 'PACKAGED_SHELF_STABLE_DRY_BAKERY' || sourceType === 'ORIGINAL_PACKAGE' || sourceType === 'SUPPLIER_DOCUMENT'
  const canSubmit = hasSource && profileSourceValid && reason.trim().length > 0
  return <DurableForm title="Confirm product safety attributes" submitLabel="Save product evidence" canSubmit={canSubmit}
    confirmation="I inspected the cited source and confirm these descriptive attributes as operator-reported evidence. This is not independent verification or food-safety approval."
    prepare={() => ({ requestId: crypto.randomUUID(), expectedProductVersion: product.version, category, temperatureClass, productProfile,
      sourceType: sourceType as ClassificationEvidenceSource, evidenceReference: evidenceReference.trim(), reason: reason.trim() } satisfies OperatorClassificationConfirmationRequest)}
    execute={(body, signal) => confirmOperatorClassification(organizationId, product.id, body, signal)} onSaved={onSaved} onRefresh={onRefresh} onCancel={onCancel}>
    <p className="text-sm text-muted-foreground">Cite the package or supplier document you inspected. The reference is retained with the account and resulting product version; document contents are not uploaded.</p>
    <Field label="Product category from the reviewed source" help="Choose only what the package or supplier evidence supports.">{id => <select id={id} value={category} onChange={event => { setCategory(event.target.value as Product['category']) }} className={inputClass}><option value="UNCLASSIFIED">Unclassified — category unknown</option><option value="DAIRY">Dairy</option><option value="BAKERY">Bakery</option></select>}</Field>
    <Field label="Declared temperature class from the reviewed source" help="This corrects the current value in place and advances the product version.">{id => <select id={id} required value={temperatureClass} onChange={event => { setTemperatureClass(event.target.value as Product['temperatureClass']) }} className={inputClass}><option value="AMBIENT">Ambient</option><option value="CHILLED">Chilled</option><option value="FROZEN">Frozen</option></select>}</Field>
    <Field label="Product profile from the reviewed source" help="Only choose the limited profile when the inspected original package or supplier document supports a sealed, shelf-stable dry bakery product with ambient storage instructions. Record refrigeration, perishable, non-shelf-stable, missing, ambiguous or contradictory evidence under its matching review profile; these remain outside this pilot.">{id => <select id={id} required value={productProfile} onChange={event => { setProductProfile(event.target.value as OperatorClassificationConfirmationRequest['productProfile']) }} className={inputClass}><option value="NOT_ASSESSED">Not assessed — requires review</option><option value="PACKAGED_SHELF_STABLE_DRY_BAKERY">Packaged, shelf-stable dry bakery</option><option value="REFRIGERATION_REQUIRED">Refrigeration required</option><option value="CREAM_FILLED_OR_PERISHABLE">Cream-filled or perishable</option><option value="NOT_SHELF_STABLE">Not shelf-stable</option><option value="STORAGE_INSTRUCTION_MISSING_OR_AMBIGUOUS">Storage instruction missing or ambiguous</option><option value="CONTRADICTORY_STORAGE_EVIDENCE">Contradictory storage evidence</option><option value="OUTSIDE_CURRENT_PILOT">Outside the current limited pilot</option></select>}</Field>
    <Field label="Source type" help="Use an original package or supplier document for the limited bakery profile. Other identified sources remain under review.">{id => <select id={id} required value={sourceType} onChange={event => { setSourceType(event.target.value as ClassificationEvidenceSource | '') }} className={inputClass}><option value="">Choose the inspected source</option><option value="ORIGINAL_PACKAGE">Original package</option><option value="SUPPLIER_DOCUMENT">Supplier document</option><option value="OTHER">Other identified evidence</option></select>}</Field>
    <Field label="Source reference" help="Enter a short document, label or evidence reference. Do not enter confidential document contents.">{id => <input id={id} maxLength={256} value={evidenceReference} onChange={event => { setEvidenceReference(event.target.value) }} className={inputClass} />}</Field>
    <Field label="Reason for this confirmation">{id => <textarea id={id} required maxLength={500} value={reason} onChange={event => { setReason(event.target.value) }} className={inputClass} />}</Field>
    {!profileSourceValid && <p role="alert">The limited bakery profile requires an original package or supplier document reference.</p>}
  </DurableForm>
}
