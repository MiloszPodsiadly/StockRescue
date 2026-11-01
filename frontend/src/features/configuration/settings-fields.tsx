import { errorMessage } from '@/api/client'

export const panelClass = 'space-y-5 rounded-2xl border border-border bg-card p-5 sm:p-7'
export function CommandError({ error }: { error: unknown }) {
  return <p role="alert" className="rounded-xl border border-destructive/30 p-4 text-sm text-destructive">{errorMessage(error)} Reload saved configuration to confirm an uncertain result or resolve a conflict before trying again.</p>
}
