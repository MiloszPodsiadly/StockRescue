import { useQuery } from '@tanstack/react-query'
import { getSession } from '@/api/auth'

export const sessionKey = ['identity', 'session'] as const

export function useSession() {
  return useQuery({
    queryKey: sessionKey,
    queryFn: ({ signal }) => getSession(signal),
    retry: false,
    staleTime: 0,
    gcTime: 0,
    refetchOnWindowFocus: true,
  })
}
