import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { App } from './App'

describe('business landing page', () => {
  it('highlights workspace value without exposing infrastructure status', async () => {
    render(<App />)

    expect(screen.getByRole('heading', { name: 'Make the operating picture clear.' })).toBeVisible()
    expect(screen.getByRole('heading', { name: 'A clear store network' })).toBeVisible()
    expect(screen.getByRole('heading', { name: 'The right access for each team' })).toBeVisible()
    expect(screen.getByRole('heading', { name: 'Traceable transport costs' })).toBeVisible()
    expect(screen.getByRole('link', { name: 'Explore your workspace' })).toHaveAttribute('href', '/account')
    expect(screen.queryByText(/backend|connection|service availability/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()

    const user = userEvent.setup()
    await user.tab()
    expect(screen.getByRole('link', { name: 'Skip to content' })).toHaveFocus()
  })
})
