export async function checkBackendReadiness(): Promise<'UP'> {
  const response = await fetch('/api/health/readiness', {
    signal: AbortSignal.timeout(4000),
    headers: { Accept: 'application/json' },
    cache: 'no-store',
  })
  if (!response.ok) throw new Error('Backend is unavailable.')
  const body: unknown = await response.json()
  if (
    typeof body !== 'object' ||
    body === null ||
    Array.isArray(body) ||
    Object.keys(body).length !== 1 ||
    !('status' in body) ||
    body.status !== 'UP'
  ) {
    throw new Error('Backend readiness could not be verified.')
  }
  return 'UP'
}
