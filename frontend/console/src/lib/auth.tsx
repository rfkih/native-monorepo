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
      actor: DEV_ACTOR,
      roles: [...BUSINESS_ROLES],
      login: () => {},
      logout: () => {},
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

  const [state, setState] = useState<Omit<AuthState, 'login' | 'logout'>>({
    ready: false,
    authenticated: false,
    companyId: null,
    actor: '',
    roles: [],
  })

  useEffect(() => {
    let mounted = true

    function apply(user: User | null) {
      if (!mounted) return
      if (!user || user.expired || !user.access_token) {
        setAccessToken(null)
        setState({ ready: true, authenticated: false, companyId: null, actor: '', roles: [] })
        return
      }
      setAccessToken(user.access_token)
      const claims = decodeJwt(user.access_token)
      setState({
        ready: true,
        authenticated: true,
        companyId: typeof claims.company_id === 'string' ? claims.company_id : null,
        actor:
          (typeof claims.preferred_username === 'string' && claims.preferred_username) ||
          (typeof claims.sub === 'string' && claims.sub) ||
          'unknown',
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
      // No valid session → start the login redirect (App shows a spinner while ready=false).
      await manager.signinRedirect()
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
      login: () => void manager.signinRedirect(),
      logout: () => {
        setAccessToken(null)
        void manager.signoutRedirect()
      },
    }),
    [state, manager],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// ---------------------------------------------------------------------------
// helpers (module-private)
// ---------------------------------------------------------------------------

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
