import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'
import { isNativeShell } from '@/lib/escpos/transport'
import { setAccessToken, setUnauthorizedHandler } from '@/lib/api'
import { AUTH_MODE, KEYCLOAK_CLIENT_ID, KEYCLOAK_REALM, KEYCLOAK_SCOPE, KEYCLOAK_URL } from '@/lib/config'
import { DEV_ACTOR } from '@/lib/devIdentity'
import {
  AuthContext,
  BUSINESS_ROLES,
  type ActorType,
  type AuthState,
  type BusinessRole,
} from '@/lib/authContext'

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
  // ADR 0049 P3b: a dev run can simulate the Business-app terminal without Keycloak — set
  // VITE_DEV_ACTOR_TYPE=device and the synthetic principal carries only `cashier` (matching a real
  // per-outlet device login, which is never owner/manager-capable) instead of every business role,
  // so the operator-PIN gate/chip render exactly as they would on a real device.
  const isDeviceDev = import.meta.env.VITE_DEV_ACTOR_TYPE === 'device'
  const value = useMemo<AuthState>(
    () => ({
      ready: true,
      authenticated: true,
      companyId: null, // dev tenant comes from the onboarding session, not a token
      companyIds: [],
      actor: DEV_ACTOR,
      sub: null,
      actorType: isDeviceDev ? 'device' : 'user',
      roles: isDeviceDev ? ['cashier'] : [...BUSINESS_ROLES],
      login: () => {},
      logout: () => {},
      refresh: async () => true,
    }),
    [isDeviceDev],
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
        scope: KEYCLOAK_SCOPE,
        automaticSilentRenew: true,
        // Native Till shell (ADR 0043): the WebView process dies with the app, and
        // sessionStorage with it — every cold start dumped the operator on the logged-out
        // landing page. localStorage keeps the till signed in across restarts (Keycloak
        // token lifetimes still govern expiry). Browsers keep the per-tab default.
        userStore: new WebStorageStateStore({
          store: isNativeShell() ? window.localStorage : window.sessionStorage,
        }),
      }),
  )

  const [state, setState] = useState<Omit<AuthState, 'login' | 'logout' | 'refresh'>>({
    ready: false,
    authenticated: false,
    companyId: null,
    companyIds: [],
    actor: '',
    sub: null,
    actorType: 'user',
    roles: [],
  })
  // Mirrors state.actorType for recoverOrLogout's kiosk-softening branch below — see apply()'s
  // comment for why a ref (not the `state` closure) is needed there.
  const actorTypeRef = useRef<ActorType>('user')

  useEffect(() => {
    let mounted = true

    function apply(user: User | null) {
      if (!mounted) return
      if (!user || user.expired || !user.access_token) {
        setAccessToken(null)
        actorTypeRef.current = 'user'
        setState({
          ready: true,
          authenticated: false,
          companyId: null,
          companyIds: [],
          actor: '',
          sub: null,
          actorType: 'user',
          roles: [],
        })
        return
      }
      setAccessToken(user.access_token)
      const claims = decodeJwt(user.access_token)
      // The company_id claim is `string | string[]` (a multivalued mapper emits an array — the
      // login's allowed companies, first = default active; pre-rollout tokens carry a scalar).
      const companyIds = extractCompanyIds(claims.company_id)
      const actorType = extractActorType(claims)
      // Mirrored into a ref (not just React state) so recoverOrLogout below — a stable closure
      // captured once on mount — can read the CURRENT actor type instead of the stale value from
      // whenever the effect first ran (see its own doc).
      actorTypeRef.current = actorType
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
        actorType,
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
        actorType: 'user',
        roles: [],
      })
    }

    void init()

    // Recover-or-logout on an UNRECOVERABLE auth failure: an access token that expired without a
    // successful automatic silent renew (addAccessTokenExpired), a silent-renew failure
    // (addSilentRenewError), or a 401 from the API — the gateway rejecting the bearer (via
    // setUnauthorizedHandler). Previously NONE of these were handled, so an expired session just
    // kept firing requests with a dead token and surfacing silent errors while `state` still said
    // authenticated. Now: try ONE silent renew; if it fails, the session is genuinely over → clear
    // it and send the user to the sign-in screen. Debounced (`recovering`) so a burst of 401s (a
    // dashboard fires ~10 queries at once) triggers a single attempt, not a storm of redirects.
    let recovering = false
    async function recoverOrLogout() {
      if (!mounted || recovering) return
      recovering = true
      try {
        // Success fires addUserLoaded → apply(newUser); in-flight react-query retries then succeed.
        await manager.signinSilent()
      } catch {
        // Truly unrecoverable (refresh token expired / IdP session gone) → hard logout.
        // Read BEFORE apply(null) below, which always resets the ref to 'user'.
        const wasKiosk = actorTypeRef.current === 'device' || isNativeShell()
        setAccessToken(null)
        apply(null) // flip to unauthenticated so the app stops issuing tenant-scoped calls
        try {
          await manager.removeUser()
        } catch {
          // best-effort clear of the stored (dead) session
        }
        // Kiosk-renew softening (ADR 0049 P3b): a Business-app terminal — an outlet `device` login
        // (ADR 0049), or literally the native shell, which is ALWAYS a till — must never be
        // auto-bounced to the Keycloak hosted login FORM here: nobody is standing at a shared till
        // to type a password, and a silent redirect strands the terminal on the IdP's page until
        // someone notices. Left signed out instead (apply(null) already ran above) — the terminal
        // falls back to its normal signed-out screen with its own explicit "Sign in" affordance,
        // which re-attempts login on a real tap/gesture rather than an automatic one. A normal
        // `user` login (a phone/laptop with a person in front of it) keeps the existing hard bounce
        // — for a person, it is the fastest way back in once the refresh token is genuinely dead.
        if (mounted && !wasKiosk) {
          // Explicit re-login: seamless if the Keycloak SSO session is still alive, otherwise the
          // login form. Either way the user is never stranded on a silently-broken page.
          void manager.signinRedirect()
        }
      } finally {
        recovering = false
      }
    }

    const onLoaded = (user: User) => apply(user)
    const onUnloaded = () => apply(null)
    const onExpired = () => void recoverOrLogout()
    const onRenewError = () => void recoverOrLogout()
    manager.events.addUserLoaded(onLoaded)
    manager.events.addUserUnloaded(onUnloaded)
    manager.events.addAccessTokenExpired(onExpired)
    manager.events.addSilentRenewError(onRenewError)
    setUnauthorizedHandler(() => void recoverOrLogout())
    return () => {
      mounted = false
      manager.events.removeUserLoaded(onLoaded)
      manager.events.removeUserUnloaded(onUnloaded)
      manager.events.removeAccessTokenExpired(onExpired)
      manager.events.removeSilentRenewError(onRenewError)
      setUnauthorizedHandler(null)
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

/**
 * Reads the ADR 0049 `actor_type` claim (`device | user`) — mirrors the gateway's
 * `TenantJwtAuthoritiesConverter.extractActorType`/`DEFAULT_ACTOR_TYPE` exactly: an absent/blank
 * claim (every login minted before the Keycloak mapper shipped, and every ordinary person login
 * today) defaults to `user`, never silently `device`.
 */
function extractActorType(claims: Record<string, unknown>): ActorType {
  return claims.actor_type === 'device' ? 'device' : 'user'
}
