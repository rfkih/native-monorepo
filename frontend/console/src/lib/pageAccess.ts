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

/**
 * The grantable console page keys. Mirrors the org-service whitelist for every key EXCEPT
 * `'expenses'` (ADR 0030, Phase E7) — a CLIENT-ONLY addition: `/expenses` has no per-page role
 * beyond the existing `canDashboard` (owner/manager) gate, so this key only ever NARROWS what an
 * already-dashboard-eligible login sees; it never needs server-side whitelist changes because the
 * gateway's real authorization boundary is the role check on `/api/v1/expense-claims/**`, unchanged
 * by this UI-level grant.
 */
export type PageKey =
  | 'pos'
  | 'kitchen'
  | 'menu'
  | 'me'
  | 'dashboard'
  | 'reports'
  | 'org'
  | 'groups'
  | 'close'
  | 'team'
  | 'expenses'

/** Pages on the back-office (owner/manager) surface. */
export const DASHBOARD_PAGES: PageKey[] = [
  'dashboard',
  'reports',
  'org',
  'groups',
  'close',
  'team',
  'expenses',
]
/** Pages on the POS (cashier) surface. */
export const POS_PAGES: PageKey[] = ['pos', 'menu', 'kitchen']

/**
 * The pages a login's ROLES can actually reach — the grantable set for a role-aware page picker.
 * Grants only NARROW within this (they never grant beyond the role), so a cashier only ever sees the
 * POS pages, an owner/manager login sees the dashboard pages + POS surface, and an employee-only
 * login has just {@code /me} (nothing to restrict).
 */
export function grantablePagesForRoles(roles: string[]): PageKey[] {
  const out: PageKey[] = []
  if (roles.includes('owner') || roles.includes('manager')) out.push(...DASHBOARD_PAGES)
  if (roles.includes('owner') || roles.includes('manager') || roles.includes('cashier')) {
    out.push(...POS_PAGES)
  }
  return out
}

interface MyPages {
  mode: 'ALL' | 'RESTRICTED'
  pageKeys: PageKey[]
}

/**
 * The signed-in login's page access. The OWNER bypasses grants entirely (mode ALL, no fetch — the
 * owner can never be locked out and is the recovery path for any mis-grant). A manager's grants
 * apply. Everyone else fetches GET /api/v1/users/me/pages; a load failure fails OPEN to ALL (never
 * lock a user out of their own surface because a read hiccuped — the gateway still enforces the real
 * boundary).
 */
export function usePageAccess(): {
  ready: boolean
  isAllowed: (page: PageKey) => boolean
} {
  const auth = useAuth()
  const bypass = hasAnyRole(auth.roles, 'owner')

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
