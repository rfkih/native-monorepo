import { createContext, useContext } from 'react'

/**
 * Auth context, hook, and role helpers — kept in a hook-only module (no component export) so the
 * provider file (`auth.tsx`) can export ONLY its component and stay Fast-Refresh clean.
 *
 * The curated business roles mirror the gateway's
 * `TenantJwtAuthoritiesConverter.BUSINESS_ROLES`.
 */
export const BUSINESS_ROLES = ['owner', 'manager', 'cashier', 'employee'] as const
export type BusinessRole = (typeof BUSINESS_ROLES)[number]

/**
 * ADR 0049 — `device` is a persistent per-outlet Keycloak login (the Business-app till itself, not
 * a person): the POS terminal then requires a PIN-identified operator before a sale can ring.
 * `user` is an ordinary personal login — today's exact behavior, byte-identical. Read from the JWT
 * `actor_type` claim (default `user`; the gateway injects/strips the same claim as `X-Actor-Type`,
 * but the CLIENT must read it from the verified token itself to branch the UI — see auth.tsx).
 */
export type ActorType = 'device' | 'user'

export interface AuthState {
  /** Initial auth resolution finished (a redirect/login may still be pending if false). */
  ready: boolean
  authenticated: boolean
  /**
   * The FIRST company of the verified `company_id` claim (the default active company), or null for
   * a not-yet-provisioned principal / dev mode. Kept for the many single-company call sites.
   */
  companyId: string | null
  /**
   * ALL companies of the verified `company_id` claim — the login's allowed set (multi-company
   * ownership, ADR 0021; the claim is `string | string[]`). Empty in dev mode / pre-onboarding.
   */
  companyIds: string[]
  /** The acting principal (JWT `preferred_username`/`sub`, or the dev actor). */
  actor: string
  /**
   * The JWT subject (the Keycloak user id) — display/diagnostic only; the backend always derives
   * identity from the verified token. Null in dev mode (no token).
   */
  sub: string | null
  /** Curated business roles the principal holds. */
  roles: BusinessRole[]
  /** `device` (a Business-app till) or `user` (a normal personal login) — see {@link ActorType}. */
  actorType: ActorType
  /**
   * Starts the login redirect. `loginHint` (an email) pre-fills the IdP's username field —
   * used right after signup so the user never re-types the address they just registered.
   */
  login: (loginHint?: string) => void
  logout: () => void
  /**
   * Silently renews the access token (oidc mode) so a just-changed `company_id` claim — e.g. after
   * "Add another business" enlarged the membership set — reaches the session without a re-login.
   * Resolves `true` when the renew succeeded (the fresh claims are applied), `false` when it failed
   * (the old token stays until the next automatic renew). Always `true` in dev mode (no token).
   */
  refresh: () => Promise<boolean>
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
