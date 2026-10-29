import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { App } from './App'

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  return render(<QueryClientProvider client={client}><App /></QueryClientProvider>)
}

describe('workspace availability', () => {
  it('shows an unavailable backend without a success badge or fictional business data', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('connection refused')))
    renderApp()
    expect(await screen.findByRole('heading', { name: 'Backend unavailable' })).toBeVisible()
    expect(screen.queryByText('Backend available')).not.toBeInTheDocument()
    expect(screen.getByText(/Inventory onboarding and stock operations are not available/)).toBeVisible()
    expect(fetch).toHaveBeenCalledTimes(1)
    const user = userEvent.setup()
    await user.tab()
    expect(screen.getByRole('link', { name: 'Skip to content' })).toHaveFocus()
  })

  it('requires a genuine UP envelope and does not turn malformed HTTP 200 into success', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response('{"status":"UP","components":{"db":{"status":"DOWN"}}}'),
      ),
    )
    renderApp()
    expect(await screen.findByRole('heading', { name: 'Backend unavailable' })).toBeVisible()
    expect(screen.queryByRole('heading', { name: 'Backend available' })).not.toBeInTheDocument()
  })

  it('shows availability after a real-shaped successful health response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"status":"UP"}')))
    renderApp()
    expect(await screen.findByRole('heading', { name: 'Backend available' })).toBeVisible()
    expect(screen.getByRole('button', { name: /Check again/ })).toBeEnabled()
  })
})
