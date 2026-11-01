import { useId } from 'react'
import type { ReactNode } from 'react'

export const inputClass = 'mt-2 w-full min-w-0 rounded-xl border border-border bg-background px-4 py-3 text-base focus-visible:outline-2 focus-visible:outline-primary disabled:opacity-60'
export function Field({ label, children, help }: { label: string, children: (id: string, description: string | undefined) => ReactNode, help?: string }) {
  const id = useId()
  return <div className="min-w-0"><label htmlFor={id} className="block text-sm font-medium">{label}</label>{children(id, help ? `${id}-help` : undefined)}{help && <p id={`${id}-help`} className="mt-2 text-sm text-muted-foreground">{help}</p>}</div>
}
