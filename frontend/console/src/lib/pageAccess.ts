/**
 * Per-login page access (the adjustable, subtractive console gate).
 *
 * The owner can restrict which pages a login opens. This is UI-level only — the API authz boundary
 * stays the gateway's role check (see the ADR); a stale/absent grant never widens access, it only
 * hides pages the console would otherwise show. Grants are runtime data (not a JWT claim), so they
 * refresh on window focus and apply on the next load.
 *
 * Semantics: mode ALL (no grant rows) → the full role surface; mode RESTRICTED → only the granted
 * keys (still intersected with the role surface). Owner/manager bypass grants entirely — an owner
 * always sees the full dashboard.
 */

import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/lib/api'
import { hasAnyRole, useAuth } from '@/lib/authContext'

/** The grantable console page keys (mirrors the org-service whitelist). */
export type PageKey = 'pos' | 'kitchen' | 'menu' | 'me'

interface MyPages {
  mode: 'ALL' | 'RESTRICTED'
  pageKeys: PageKey[]
}

/**
 * The signed-in login's page access. Owner/manager bypass (mode ALL, no fetch needed). Everyone
 * else fetches GET /api/v1/users/me/pages; a load failure fails OPEN to ALL (never lock a user out
 * of their own surface because a read hiccuped — the gateway still enforces the real boundary).
 */
export function usePageAccess(): {
  ready: boolean
  isAllowed: (page: PageKey) => boolean
} {
  const auth = useAuth()
  const bypass = hasAnyRole(auth.roles, 'owner', 'manager')

  const query = useQuery<MyPages>({
    enabled: auth.authenticated && !bypass,
    queryKey: ['myPages', auth.companyId ?? 'me'],
    retry: false,
    staleTime: 30_000,
    refetchOnWindowFocus: true,
    queryFn: async () => {
      const result = await apiFetch<MyPages>('/api/v1/users/me/pages', {
        tenant: { companyId: auth.companyId ?? 'me', actor: auth.actor },
      })
      return result ?? { mode: 'ALL', pageKeys: [] }
    },
  })

  if (bypass) {
    return { ready: true, isAllowed: () => true }
  }
  if (query.isLoading) {
    return { ready: false, isAllowed: () => false }
  }
  // Fail open on error, and on mode ALL.
  const data = query.data
  if (!data || query.isError || data.mode === 'ALL') {
    return { ready: true, isAllowed: () => true }
  }
  const granted = new Set(data.pageKeys)
  return { ready: true, isAllowed: (page) => granted.has(page) }
}
