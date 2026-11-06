import type { ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { errorMessage } from '@/api/client'
import type { ManufacturerLot } from '@/api/lots'
import type { components } from '@/api/schema'

export function QueryNotice({ title, error, reload }: { title: string, error?: unknown, reload: () => void }) {
  return <div className="space-y-3 rounded-xl border border-border p-5"><p role={error ? 'alert' : 'status'}>{title}{error ? ` ${errorMessage(error)}` : ''}</p><Button variant="outline" onClick={reload}>Reload saved data</Button></div>
}
export function Pager({ offset, hasMore, onChange, label, size = 25 }: { offset: number, hasMore: boolean, onChange: (offset: number) => void, label: string, size?: number }) {
  return <nav aria-label={`${label} pages`} className="flex flex-wrap gap-3"><Button type="button" variant="outline" disabled={offset === 0} onClick={() => { onChange(Math.max(0, offset - size)) }}>Previous {label}</Button><Button type="button" variant="outline" disabled={!hasMore} onClick={() => { onChange(offset + size) }}>Next {label}</Button></nav>
}
export function Facts({ values }: { values: [string, ReactNode][] }) {
  return <dl className="grid min-w-0 gap-5 rounded-xl border border-border p-5 sm:grid-cols-2">{values.map(([label, value]) => <div key={label} className="min-w-0"><dt className="text-sm text-muted-foreground">{label}</dt><dd className="mt-2 break-words [overflow-wrap:anywhere]">{value ?? 'Unknown'}</dd></div>)}</dl>
}
export function Provenance({ value }: { value: components['schemas']['CatalogProvenanceResponse'] | null }) {
  return value ? <span className="break-words [overflow-wrap:anywhere]">{value.source} · {value.reference ?? 'Reference not supplied'}</span> : <span>Source not supplied</span>
}
export function DateMarking({ value }: { value: ManufacturerLot['dateMarking'] }) {
  return <section aria-label="Original date marking" className="space-y-4"><h3 className="text-xl font-semibold">Original date marking</h3><Facts values={[
    ['Label exactly as recorded', value.labeledValue === null ? 'Not supplied' : <span className="whitespace-pre-wrap">{value.labeledValue === '' ? '(empty label)' : value.labeledValue}</span>],
    ['Date marking type', value.type], ['Label precision', value.precision], ['Label evidence', <Provenance value={value.provenance} />],
    ['Label validation', value.validationStatus], ['Policy-derived cutoff', value.normalizedCutoff ?? 'No verified normalization'],
  ]} />{value.validationReasons.length > 0 && <ul className="list-inside list-disc text-sm">{value.validationReasons.map(reason => <li key={reason}>{reason.replaceAll('_', ' ').toLowerCase()}</li>)}</ul>}<p className="text-sm text-muted-foreground">Label validation describes the recorded evidence. Food-safety approval requires a separate review.</p></section>
}
