import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { Zone, ZoneArchiveImpact } from '@/api/configuration'
import { ZonesPage } from './zones-page'

const api = vi.hoisted(() => ({
  getStore: vi.fn(), listZones: vi.fn(), createZone: vi.fn(), renameZone: vi.fn(),
  getZoneArchiveImpact: vi.fn(), archiveZone: vi.fn(),
}))

vi.mock('@/api/stores', () => ({ getStore: api.getStore }))
vi.mock('@/api/configuration', () => api)
vi.mock('@tanstack/react-router', () => ({ useParams: () => ({ organizationId: 'org', storeId: 'store' }) }))
vi.mock('@/components/app-link', () => ({ AppLink: ({ href, children, ...props }: React.ComponentProps<'a'> & { href: string }) => <a href={href} {...props}>{children}</a> }))
vi.mock('../organizations/organization-page', () => ({ OrganizationPage: ({ children }: React.PropsWithChildren) => <>{children}</> }))
vi.mock('../organizations/tenant-requests', () => ({
  useTenantKey: () => ['tenant', 'actor', 'org'],
  useTenantRequests: () => () => new AbortController().signal,
}))

const zone: Zone = {
  id: 'zone', name: 'Ambient', temperatureClass: 'AMBIENT', active: true, version: 4,
  createdAt: '2026-10-31T12:00:00Z', updatedAt: '2026-10-31T12:00:00Z',
}
const preview: ZoneArchiveImpact = {
  activeLaneCount: 2, inactiveLaneCount: 1, futureScheduledRevisionCount: 3, zoneVersion: 4,
  impactFingerprint: 'A'.repeat(43), asOf: '2026-10-31T12:00:00Z',
}

afterEach(cleanup)
beforeEach(() => {
  vi.resetAllMocks()
  api.getStore.mockResolvedValue({ id: 'store', name: 'North shop' })
  api.listZones.mockResolvedValue({ items: [zone], hasMore: false, canManage: true })
  api.getZoneArchiveImpact.mockResolvedValue(preview)
  api.archiveZone.mockResolvedValue({ ...zone, active: false, version: 5 })
})
function renderZones() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } })
  render(<QueryClientProvider client={client}><ZonesPage /></QueryClientProvider>)
  return userEvent.setup()
}

it('requires a fresh impact preview, reason and explicit confirmation before zone archival', async () => {
  const user = renderZones()
  await screen.findByRole('heading', { name: 'Ambient' })
  await user.click(screen.getByRole('button', { name: 'Edit Ambient' }))
  await user.click(screen.getByRole('button', { name: 'Review impact before deactivation' }))

  const impact = await screen.findByRole('region', { name: 'Zone archive impact' })
  expect(impact).toHaveTextContent(/Active lanes affected\s*2/)
  expect(impact).toHaveTextContent(/Inactive lanes affected\s*1/)
  expect(impact).toHaveTextContent(/Future scheduled revisions\s*3/)
  expect(impact).toHaveTextContent('Future revisions remain in history and are not cancelled automatically.')
  expect(impact).not.toHaveTextContent('costAmount')
  const confirm = screen.getByRole('button', { name: 'Confirm zone deactivation' })
  expect(confirm).toBeDisabled()
  await user.click(screen.getByLabelText('I reviewed this impact and confirm permanent deactivation.'))
  expect(confirm).toBeDisabled()
  await user.type(screen.getByLabelText('Reason for deactivation'), 'Reviewed route impact and carrier plans')
  expect(confirm).toBeEnabled()

  await user.click(confirm)

  await waitFor(() => expect(api.archiveZone).toHaveBeenCalledTimes(1))
  expect(api.archiveZone).toHaveBeenCalledWith('org', 'store', zone, 'Reviewed route impact and carrier plans', preview, expect.any(AbortSignal))
})

it('requires another confirmation after PostgreSQL reports that the preview impact changed', async () => {
  const refreshedPreview = { ...preview, activeLaneCount: 3, zoneVersion: 5, impactFingerprint: 'B'.repeat(43) }
  api.getZoneArchiveImpact.mockResolvedValueOnce(preview).mockResolvedValueOnce(refreshedPreview)
  api.archiveZone.mockRejectedValueOnce(new ApiError(new Response(null, { status: 409 }), {
    type: 'about:blank', title: 'Conflict', status: 409, detail: 'Review the latest impact.',
    code: 'ZONE_IMPACT_CHANGED', correlationId: 'zone-impact-test',
  }))
  const user = renderZones()
  await screen.findByRole('heading', { name: 'Ambient' })
  await user.click(screen.getByRole('button', { name: 'Edit Ambient' }))
  await user.type(screen.getByLabelText('Reason for deactivation'), 'Reviewed route impact and carrier plans')
  await user.click(screen.getByRole('button', { name: 'Review impact before deactivation' }))
  await screen.findByRole('region', { name: 'Zone archive impact' })
  await user.click(screen.getByLabelText('I reviewed this impact and confirm permanent deactivation.'))
  await user.click(screen.getByRole('button', { name: 'Confirm zone deactivation' }))

  await screen.findByRole('alert')
  await waitFor(() => expect(screen.getByRole('region', { name: 'Zone archive impact' })).toHaveTextContent(/Active lanes affected\s*3/))
  expect(screen.getByLabelText('I reviewed this impact and confirm permanent deactivation.')).not.toBeChecked()
  expect(screen.getByRole('button', { name: 'Confirm zone deactivation' })).toBeDisabled()
  expect(api.archiveZone).toHaveBeenCalledTimes(1)

  await user.click(screen.getByLabelText('I reviewed this impact and confirm permanent deactivation.'))
  await user.click(screen.getByRole('button', { name: 'Confirm zone deactivation' }))
  await waitFor(() => expect(api.archiveZone).toHaveBeenCalledTimes(2))
  expect(api.archiveZone).toHaveBeenLastCalledWith('org', 'store', zone, 'Reviewed route impact and carrier plans', refreshedPreview, expect.any(AbortSignal))
})
