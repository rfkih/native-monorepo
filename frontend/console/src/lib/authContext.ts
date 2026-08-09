import { createContext, useContext } from 'react'

/**
 * Auth context, hook, and role helpers — kept in a hook-only module (no component export) so the
 * provider file (`auth.tsx`) can export ONLY its component and stay Fast-Refresh clean.
 *
 * The curated business roles mirror the gateway's
 * `TenantJwtAuthoritiesConverter.BUSINESS_ROLES`.
 *
 * `hr`/`accountant`/`chef`/`waitress` (preset role-based access model Phase 2 — the UI mirror of
 * the gateway's Phase 1) join the original four: `hr`/`accountant` split the back-office surface
 * finer than `owner`/`manager` (HR vs. detailed finance), `chef`/`waitress` ring the till like
 * `cashier`. See `lib/rolePreset.ts` for the capability table every nav/route gate reads.
 */
export const BUSINESS_ROLES = [
  'owner',
  'manager',
  'cashier',
  'employee',
  'hr',
  'accountant',
  'chef',
  'waitress',
] as const
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
  /** Curated business roles the principal holds — from the OUTLET/primary token only (a device
   * terminal's own roles, e.g. `['cashier']`). Never includes an elevation — see {@link
   * effectiveRoles} for the merged set back-office route gating should read. */
  roles: BusinessRole[]
  /** `device` (a Business-app till) or `user` (a normal personal login) — see {@link ActorType}. */
  actorType: ActorType
  /**
   * ADR 0049 P3b — the roles of a PERSONAL owner/manager elevation layered on top of a device
   * terminal (a second, ordinary OIDC login — see `lib/auth.tsx`). Always `[]` for a normal `user`
   * login (nothing ever elevates there) and for a device terminal with no elevation active — so
   * {@link effectiveRoles}`(roles, elevatedRoles)` is byte-identical to `roles` in both of those
   * cases, and only differs on an ELEVATED device.
   */
  elevatedRoles: BusinessRole[]
  /** Starts the personal-elevation login redirect (a device terminal's "Sign in to manage"). A
   * no-op-shaped call on a normal `user` login (nothing in the UI ever invokes it there). */
  elevate: () => void
  /** Ends the personal elevation (till-menu "Sign out of back office") — drops back to the bare
   * outlet terminal; does NOT touch the outlet credential itself (see `auth.logout` for that). */
  dropElevation: () => void
  /**
   * Starts the login redirect. `loginHint` (an email) pre-fills the IdP's username field —
   * used right after signup so the user never re-types the address they just registered.
   */
  login: (loginHint?: string) => void
  logout: () => void
  /**
   * Opens Keycloak's OWN secure change-password screen for the CALLER (Employee self-service
   * "Account settings" — two-app program). Implemented as a fresh `signinRedirect` on the SAME
   * PRIMARY manager `login`/`logout` use, passing `kc_action: 'UPDATE_PASSWORD'` instead of
   * `elevate()`'s `prompt: 'login'` — Keycloak runs its built-in UPDATE_PASSWORD required action
   * for the already-authenticated principal, then redirects back to `/auth/callback` (existing
   * handling). No new Keycloak client, no realm change, no backend call: Keycloak owns the
   * credential (rule 6). A no-op (console.info) in dev mode — there is no Keycloak to redirect to.
   */
  changePassword: () => void
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

/**
 * ADR 0049 P3b — the EFFECTIVE role set: the login's base `roles` unioned with any personal
 * elevation `elevatedRoles`. This is the ONLY thing back-office route gating (`canDashboard`/
 * `isOwner` in App.tsx) should read — `canPos`/`canEmployee` stay on the base `roles` (elevation
 * never changes what the OUTLET credential itself can do). A normal `user` login always has
 * `elevatedRoles = []`, so `effectiveRoles(roles, []) === roles`'s VALUES byte-for-byte (same
 * members, same order-independent set) — kept a pure function (no React) so it is trivially unit
 * testable and usable outside a component.
 */
export function effectiveRoles(
  roles: readonly BusinessRole[],
  elevatedRoles: readonly BusinessRole[],
): BusinessRole[] {
  if (elevatedRoles.length === 0) return [...roles]
  const merged = new Set<BusinessRole>(roles)
  for (const r of elevatedRoles) merged.add(r)
  return [...merged]
}
