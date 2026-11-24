import type { AnchorHTMLAttributes, ReactNode } from 'react'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { WorkspaceFrame } from './workspace-frame'

const routerState = vi.hoisted(() => ({ pathname: '' }))

vi.mock('@tanstack/react-router', () => ({
  useRouterState: ({ select }: { select: (state: { location: { pathname: string } }) => unknown }) =>
    select({ location: { pathname: routerState.pathname } }),
}))

vi.mock('./app-link', () => ({
  AppLink: ({ href, children, ...props }: { href: string, children: ReactNode } & Omit<AnchorHTMLAttributes<HTMLAnchorElement>, 'href'>) =>
    <a href={href} {...props}>{children}</a>,
}))

afterEach(() => {
  cleanup()
  routerState.pathname = ''
})

function renderAt(pathname: string, administrator = true) {
  routerState.pathname = pathname
  return render(<WorkspaceFrame organizationId="org" administrator={administrator}><span>{String(administrator)}</span></WorkspaceFrame>)
}

function currentNavigationLabels() {
  return screen.getAllByRole('link').filter(link => link.getAttribute('aria-current') === 'page').map(link => link.textContent)
}

it.each([
  ['/organizations/org/transfers', 'Stock transfers'],
  ['/organizations/org/transfers/transfer-1', 'Stock transfers'],
  ['/organizations/org/transfers/historical', 'Historical transfers'],
] as const)('marks only the most specific navigation link for %s', (pathname, label) => {
  renderAt(pathname)
  expect(currentNavigationLabels()).toEqual([expect.stringContaining(label)])
})

it('does not treat a sibling path as a transfer navigation match', () => {
  renderAt('/organizations/org/transfers-other')
  expect(currentNavigationLabels()).toEqual([])
})

it('keeps historical navigation hidden from non-administrators', () => {
  renderAt('/organizations/org/transfers', false)
  expect(screen.queryByRole('link', { name: 'Historical transfers' })).not.toBeInTheDocument()
  expect(currentNavigationLabels()).toEqual([expect.stringContaining('Stock transfers')])
})
