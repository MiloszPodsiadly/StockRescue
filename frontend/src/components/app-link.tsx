import type { ComponentProps } from 'react'
import { useRouter } from '@tanstack/react-router'

/** Internal links keep the session and its CSRF value in memory during navigation. */
export function AppLink({ href, onClick, ...props }: ComponentProps<'a'> & { href: string }) {
  const router = useRouter()
  return <a {...props} href={href} onClick={event => {
    onClick?.(event)
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || props.target || props.download) return
    event.preventDefault()
    void router.navigate({ href })
  }} />
}
