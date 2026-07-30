import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'
import { setAccessToken } from '@/lib/api'
import { AUTH_MODE, KEYCLOAK_CLIENT_ID, KEYCLOAK_REALM, KEYCLOAK_URL } from '@/lib/config'
import { DEV_ACTOR } from '@/lib/devIdentity'
import { AuthContext, BUSINESS_ROLES, type AuthState, type BusinessRole } from '@/lib/authContext'

/**
 * Authentication provider for the console.
 *
 * Two interchangeable providers, chosen ONCE by {@link AUTH_MODE} (so the hook tree never changes
 * shape):
 *  - `oidc` — real Keycloak authorization-code + PKCE login (production). The access token is sent
 *    as a bearer to the gateway, which derives the tenant + roles from the VERIFIED token; the
 *    browser never asserts its own tenant.
 *  - `dev` — no Keycloak: a synthetic always-authenticated principal with every business role, so
 *    local `npm run dev` works offline against the header-trust `DevTenantFilter` path.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  return AUTH_MODE === 'oidc' ? (
    <OidcAuthProvider>{children}</OidcAuthProvider>
  ) : (
    <DevAuthProvider>{children}</DevAuthProvider>
  )
}

// ---------------------------------------------------------------------------
// dev: synthetic principal, full access. The header-trust path lives in api.ts.
// ---------------------------------------------------------------------------
function DevAuthProvider({ children }: { children: ReactNode }) {
  const value = useMemo<AuthState>(
    () => ({
      ready: true,
      authenticated: true,
      companyId: null, // dev tenant comes from the onboarding session, not a token
      companyIds: [],
      actor: DEV_ACTOR,
      sub: null,
      roles: [...BUSINESS_ROLES],
      login: () => {},
      logout: () => {},
      refresh: async () => true,
    }),
    [],
  )
  // Ensure no stale bearer leaks into the dev header-trust path.
  useEffect(() => setAccessToken(null), [])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// ---------------------------------------------------------------------------
// oidc: real Keycloak login (authorization-code + PKCE).
// ---------------------------------------------------------------------------
function OidcAuthProvider({ children }: { children: ReactNode }) {
  // Created exactly once (lazy initializer) — a ref read during render trips react-hooks/refs.
  const [manager] = useState(
    () =>
      new UserManager({
        authority: `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}`,
        client_id: KEYCLOAK_CLIENT_ID,
        redirect_uri: `${window.location.origin}/auth/callback`,
        post_logout_redirect_uri: window.location.origin,
        response_type: 'code',
        scope: 'openid profile email',
        automaticSilentRenew: true,
        userStore: new WebStorageStateStore({ store: window.sessionStorage }),
      }),
  )

  const [state, setState] = useState<Omit<AuthState, 'login' | 'logout' | 'refresh'>>({
    ready: false,
    authenticated: false,
    companyId: null,
    companyIds: [],
    actor: '',
    sub: null,
    roles: [],
  })

  useEffect(() => {
    let mounted = true

    function apply(user: User | null) {
      if (!mounted) return
      if (!user || user.expired || !user.access_token) {
        setAccessToken(null)
        setState({
          ready: true,
          authenticated: false,
          companyId: null,
          companyIds: [],
          actor: '',
          sub: null,
          roles: [],
        })
        return
      }
      setAccessToken(user.access_token)
      const claims = decodeJwt(user.access_token)
      // The company_id claim is `string | string[]` (a multivalued mapper emits an array — the
      // login's allowed companies, first = default active; pre-rollout tokens carry a scalar).
      const companyIds = extractCompanyIds(claims.company_id)
      setState({
        ready: true,
        authenticated: true,
        companyId: companyIds[0] ?? null,
        companyIds,
        actor:
          (typeof claims.preferred_username === 'string' && claims.preferred_username) ||
          (typeof claims.sub === 'string' && claims.sub) ||
          'unknown',
        sub: typeof claims.sub === 'string' ? claims.sub : null,
        roles: extractRoles(claims),
      })
    }

    async function init() {
      // Returning from the Keycloak redirect.
      if (window.location.pathname === '/auth/callback') {
        try {
          const user = await manager.signinRedirectCallback()
          window.history.replaceState({}, document.title, '/')
          apply(user)
          return
        } catch {
          // Stale/invalid callback — fall through to a fresh login.
        }
      }
      const user = await manager.getUser()
      if (user && !user.expired) {
        apply(user)
        return
      }
      // No valid session → render the PUBLIC marketing site (the landing page, /signup, /login)
      // instead of force-redirecting to Keycloak. Login is now EXPLICIT: the landing page's
      // "Sign in" button and the /login route call auth.login() → manager.signinRedirect(). This
      // gives an unauthenticated visitor an actual front door rather than an immediate bounce to
      // the IdP. Protected paths still fail closed — App routes any unauthenticated deep-link back
      // to the landing, and the gateway rejects any tenant-scoped API call without a valid token.
      setState({
        ready: true,
        authenticated: false,
        companyId: null,
        companyIds: [],
        actor: '',
        sub: null,
        roles: [],
      })
    }

    void init()

    const onLoaded = (user: User) => apply(user)
    const onUnloaded = () => apply(null)
    manager.events.addUserLoaded(onLoaded)
    manager.events.addUserUnloaded(onUnloaded)
    return () => {
      mounted = false
      manager.events.removeUserLoaded(onLoaded)
      manager.events.removeUserUnloaded(onUnloaded)
    }
  }, [manager])

  const value = useMemo<AuthState>(
    () => ({
      ...state,
      // login_hint pre-fills Keycloak's username field (used by the post-signup hand-off).
      login: (loginHint?: string) =>
        void manager.signinRedirect(loginHint ? { login_hint: loginHint } : undefined),
      logout: () => {
        setAccessToken(null)
        void manager.signoutRedirect()
      },
      // Silent renew: fetches a fresh token so a just-enlarged company_id claim (after "Add
      // another business") reaches the session. The addUserLoaded event applies the new claims.
      refresh: async () => {
        try {
          await manager.signinSilent()
          return true
        } catch {
          // Renew failed (e.g. IdP session gone) — the current token stays; the new membership
          // arrives on the next automatic renew or login.
          return false
        }
      },
    }),
    [state, manager],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// ---------------------------------------------------------------------------
// helpers (module-private)
// ---------------------------------------------------------------------------

/**
 * Normalizes the `string | string[]` company_id claim to a clean, deduplicated id list. Array
 * entries coerce primitives (mirroring the two backend extractors, which accept any element via
 * `toString()`), so all three implementations agree on the set for any claim shape.
 */
function extractCompanyIds(claim: unknown): string[] {
  if (typeof claim === 'string') return claim ? [claim] : []
  if (Array.isArray(claim)) {
    const ids: string[] = []
    for (const value of claim) {
      const id =
        typeof value === 'string' ? value : typeof value === 'number' ? String(value) : ''
      if (id && !ids.includes(id)) ids.push(id)
    }
    return ids
  }
  return []
}

/** Decode a JWT payload (no verification — the gateway/services verify; we only read claims). */
function decodeJwt(token: string): Record<string, unknown> {
  try {
    const part = token.split('.')[1]
    const base64 = part
      .replace(/-/g, '+')
      .replace(/_/g, '/')
      .padEnd(part.length + ((4 - (part.length % 4)) % 4), '=')
    return JSON.parse(atob(base64)) as Record<string, unknown>
  } catch {
    return {}
  }
}

/** Curate the token's roles down to the known business roles (drops Keycloak infra roles). */
function extractRoles(claims: Record<string, unknown>): BusinessRole[] {
  const found = new Set<string>()
  const flat = claims.roles
  if (Array.isArray(flat)) flat.forEach((r) => found.add(String(r)))
  const realmAccess = claims.realm_access
  if (
    realmAccess &&
    typeof realmAccess === 'object' &&
    Array.isArray((realmAccess as { roles?: unknown }).roles)
  ) {
    ;(realmAccess as { roles: unknown[] }).roles.forEach((r) => found.add(String(r)))
  }
  return BUSINESS_ROLES.filter((r) => found.has(r))
}
