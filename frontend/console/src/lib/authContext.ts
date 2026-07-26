import { createContext, useContext } from 'react'

/**
 * Auth context, hook, and role helpers — kept in a hook-only module (no component export) so the
 * provider file (`auth.tsx`) can export ONLY its component and stay Fast-Refresh clean.
 *
 * The curated business roles mirror the gateway's
 * `TenantJwtAuthoritiesConverter.BUSINESS_ROLES`.
 */
export const BUSINESS_ROLES = ['owner', 'manager', 'cashier'] as const
export type BusinessRole = (typeof BUSINESS_ROLES)[number]

export interface AuthState {
  /** Initial auth resolution finished (a redirect/login may still be pending if false). */
  ready: boolean
  authenticated: boolean
  /** The verified `company_id` claim, or null for a not-yet-provisioned principal / dev mode. */
  companyId: string | null
  /** The acting principal (JWT `preferred_username`/`sub`, or the dev actor). */
  actor: string
  /** Curated business roles the principal holds. */
  roles: BusinessRole[]
  /**
   * Starts the login redirect. `loginHint` (an email) pre-fills the IdP's username field —
   * used right after signup so the user never re-types the address they just registered.
   */
  login: (loginHint?: string) => void
  logout: () => void
}

export const AuthContext = createContext<AuthState | null>(null)

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within an AuthProvider')
  return ctx
}

/** True if the principal holds any of the given roles. */
export function hasAnyRole(roles: readonly string[], ...wanted: BusinessRole[]): boolean {
  return wanted.some((r) => roles.includes(r))
}
