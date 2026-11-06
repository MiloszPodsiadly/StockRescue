import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { eligibilityFixture } from '@/test/catalog-fixtures'
import { EligibilityResult } from './eligibility-panel'

afterEach(cleanup)
it('keeps a successful label check visibly separate from unverified policy approval', () => {
  render(<EligibilityResult value={eligibilityFixture} />)
  expect(screen.getByRole('heading', { name: 'Requires review' })).toBeVisible()
  expect(screen.getByText('Independent class-specific policy approval has not been verified.')).toBeVisible()
  expect(screen.getByText('Recorded, unverified')).toBeVisible()
  expect(screen.getByText('2 · policy-version')).toBeVisible()
  expect(screen.queryByRole('heading', { name: 'Allowed' })).not.toBeInTheDocument()
})
it('explains hard denial before incomplete evidence and shows the store local date', () => {
  render(<EligibilityResult value={{ ...eligibilityFixture, decision: 'DENIED', reasonCodes: ['LOT_RECALLED', 'DATE_LABEL_MISSING'], nextAction: 'DO_NOT_EXECUTE' }} />)
  expect(screen.getByRole('heading', { name: 'Denied' })).toBeVisible()
  expect(screen.getAllByRole('listitem').map(item => item.textContent)).toEqual(['A recall is recorded for this lot.', 'The original date label is missing.'])
  expect(screen.getByText('Europe/Warsaw')).toBeVisible()
  expect(screen.getByText('2026-11-06')).toBeVisible()
})
it('refuses to present an unexpected approval as verified permission', () => {
  render(<EligibilityResult value={{ ...eligibilityFixture, decision: 'ALLOWED' }} />)
  expect(screen.getByRole('alert')).toHaveTextContent('Returned approval cannot be independently verified')
  expect(screen.getByRole('heading', { name: 'Requires review' })).toBeVisible()
})
