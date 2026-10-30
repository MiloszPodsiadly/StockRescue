import { useLayoutEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { Leaf, ArrowLeft, LoaderCircle } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { errorMessage, signIn, signUp } from '@/api/auth'
import { sessionKey } from './session'

const inputClass = 'mt-2 w-full rounded-xl border border-border bg-background px-4 py-3 text-base outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:opacity-60'

function AuthPage({ mode }: { mode: 'sign-in' | 'sign-up' }) {
  const registering = mode === 'sign-up'
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [validation, setValidation] = useState('')
  const [registered, setRegistered] = useState(false)
  const passwordInput = useRef<HTMLInputElement>(null)
  const request = useRef<AbortController | null>(null)
  useLayoutEffect(() => () => { request.current?.abort() }, [])
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const action = useMutation({
    mutationKey: ['identity', mode], retry: false, gcTime: 0,
    mutationFn: async () => {
      const controller = new AbortController(); request.current = controller
      if (registering) {
        await signUp({ email: email.trim(), password }, controller.signal)
        controller.signal.throwIfAborted()
        return null
      }
      const account = await signIn({ email: email.trim(), password }, controller.signal)
      controller.signal.throwIfAborted()
      return account
    },
    onSuccess: async account => {
      if (account) {
        await queryClient.cancelQueries()
        queryClient.clear()
        queryClient.setQueryData(sessionKey, account)
        await navigate({ to: '/account', replace: true })
      } else {
        setRegistered(true)
      }
    },
    onSettled: () => { setPassword('') },
  })

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (action.isPending) return
    if (password.length < 12 || new TextEncoder().encode(password).length > 72) {
      setValidation('Use at least 12 characters and at most 72 bytes. Spaces are preserved.')
      passwordInput.current?.focus()
      return
    }
    setValidation('')
    action.mutate()
  }

  return (
    <div className="min-h-svh bg-background text-foreground">
      <a href="#auth-content" className="sr-only z-10 rounded-md bg-primary p-3 text-primary-foreground focus:not-sr-only focus:absolute focus:left-4 focus:top-4">Skip to content</a>
      <header className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-5 py-6 sm:px-8">
        <Link to="/" aria-label="StockRescue home" className="flex items-center gap-3 text-xl font-semibold"><Leaf aria-hidden="true" className="size-7 text-primary" />StockRescue<span className="text-primary">.</span></Link>
        <Link to="/" className="inline-flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground"><ArrowLeft aria-hidden="true" className="size-4" />Service status</Link>
      </header>
      <main id="auth-content" tabIndex={-1} className="mx-auto grid max-w-6xl gap-10 px-5 py-10 outline-none sm:px-8 sm:py-16 lg:grid-cols-2 lg:gap-20">
        <section className="self-center" aria-labelledby="auth-heading">
          <p className="text-xs font-semibold uppercase tracking-[0.18em] text-primary">Your StockRescue workspace</p>
          <h1 id="auth-heading" className="mt-5 text-4xl font-semibold tracking-tight sm:text-5xl">{registering ? 'A place for your team to grow.' : 'Welcome back.'}</h1>
          <p className="mt-5 max-w-md leading-relaxed text-muted-foreground">{registering ? 'Start with your own account. Organization and store access are assigned separately.' : 'Sign in to your account. Access to each organization and store is checked when you use it.'}</p>
        </section>
        <section className="rounded-2xl border border-border bg-card p-6 shadow-sm sm:p-9" aria-labelledby="form-heading">
          <h2 id="form-heading" className="text-2xl font-semibold">{registering ? 'Create your account' : 'Sign in'}</h2>
          {registered ? (
            <div role="status" className="mt-6 space-y-5">
              <p className="leading-relaxed text-muted-foreground">Your registration request was processed. Sign in with your details. If you already had an account, use your existing password.</p>
              <Link to="/sign-in" className="inline-flex rounded-xl bg-primary px-5 py-3 font-medium text-primary-foreground">Continue to sign in</Link>
            </div>
          ) : (
            <form className="mt-7 space-y-5" onSubmit={submit}>
              <div><label htmlFor="email" className="text-sm font-medium">Email address</label><input id="email" name="email" type="email" autoComplete="email" required maxLength={254} value={email} onChange={event => { setEmail(event.target.value) }} disabled={action.isPending} className={inputClass} /></div>
              <div><label htmlFor="password" className="text-sm font-medium">Password</label><input ref={passwordInput} id="password" name="password" type="password" autoComplete={registering ? 'new-password' : 'current-password'} required minLength={12} maxLength={72} value={password} onChange={event => { setPassword(event.target.value) }} disabled={action.isPending} aria-describedby="password-help" className={inputClass} /><p id="password-help" className="mt-2 text-xs leading-relaxed text-muted-foreground">12–72 bytes. Spaces are preserved. A longer passphrase is easier to remember.</p></div>
              {(validation || action.isError) && <p role="alert" className="rounded-xl border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive">{validation || errorMessage(action.error)}</p>}
              <Button type="submit" disabled={action.isPending} className="w-full py-6">{action.isPending && <LoaderCircle aria-hidden="true" className="size-4 animate-spin" />}{action.isPending ? 'Please wait…' : registering ? 'Create account' : 'Sign in'}</Button>
              <p className="text-center text-sm text-muted-foreground">{registering ? 'Already have an account? ' : 'New to StockRescue? '}<Link to={registering ? '/sign-in' : '/sign-up'} className="font-medium text-primary underline-offset-4 hover:underline">{registering ? 'Sign in' : 'Create an account'}</Link></p>
            </form>
          )}
        </section>
      </main>
    </div>
  )
}

export function SignInPage() { return <AuthPage mode="sign-in" /> }
export function SignUpPage() { return <AuthPage mode="sign-up" /> }
