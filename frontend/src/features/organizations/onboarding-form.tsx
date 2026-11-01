import { useLayoutEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { ApiError, errorMessage } from '@/api/client'
import { onboardOrganization } from '@/api/organizations'
import type { OnboardingRequest, Organization } from '@/api/organizations'

export function OnboardingForm({ actorId, onCreated, onReload }: { actorId: string, onCreated: (organization: Organization) => Promise<void>, onReload: () => void }) {
  const [name, setName] = useState('')
  const [submitted, setSubmitted] = useState<OnboardingRequest | null>(null)
  const request = useRef<AbortController | null>(null)
  useLayoutEffect(() => () => { request.current?.abort() }, [])
  const creation = useMutation({
    mutationKey: ['identity', actorId, 'onboarding'], retry: false,
    gcTime: 0,
    mutationFn: async (body: OnboardingRequest) => {
      request.current?.abort()
      const controller = new AbortController()
      request.current = controller
      const organization = await onboardOrganization(body, controller.signal)
      controller.signal.throwIfAborted()
      await onCreated(organization)
    },
    onError: error => {
      if (error instanceof ApiError && error.status === 400 && error.code === 'INVALID_REQUEST') setSubmitted(null)
    },
  })
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (creation.isPending) return
    const body = submitted ?? { requestId: crypto.randomUUID(), name: name.trim() }
    setSubmitted(body)
    creation.mutate(body)
  }
  const conflict = creation.error instanceof ApiError && creation.error.code === 'ONBOARDING_CONFLICT'
  return (
    <section aria-labelledby="onboarding-heading" className="rounded-2xl border border-border bg-card p-6 sm:p-8">
      <h2 id="onboarding-heading" className="text-2xl font-semibold">Create your first organization</h2>
      <p className="mt-3 leading-relaxed text-muted-foreground">Create a workspace for your team. You will become its initial Org Admin. You can also join other organizations when invited.</p>
      <form onSubmit={submit} className="mt-7 space-y-5">
        <div><label htmlFor="organization-name" className="text-sm font-medium">Organization name</label><input id="organization-name" name="organization-name" required maxLength={160} autoComplete="organization" value={name} onChange={event => { setName(event.target.value) }} disabled={submitted !== null} className="mt-2 w-full rounded-xl border border-border bg-background px-4 py-3 text-base outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:opacity-60" aria-describedby="organization-help" /><p id="organization-help" className="mt-2 text-xs leading-relaxed text-muted-foreground">Each account can complete this first-organization setup once. Organization names may be shared by different teams.</p></div>
        {creation.isError && <p role="alert" className="rounded-xl border border-destructive/30 p-4 text-sm text-destructive">{errorMessage(creation.error)}{submitted && !conflict && ' Retry the same request to confirm the result safely.'}</p>}
        {conflict ? <Button type="button" onClick={onReload}>Refresh organizations</Button> : <Button type="submit" disabled={creation.isPending || !name.trim()}>{creation.isPending ? 'Creating organization…' : submitted ? 'Retry organization setup' : 'Create organization'}</Button>}
      </form>
    </section>
  )
}
