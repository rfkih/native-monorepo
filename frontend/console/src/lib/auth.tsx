import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'
import { isNativeShell } from '@/lib/escpos/transport'
import {
  setAccessToken,
  setOperatorSession,
  setPersonalAccessToken,
  setUnauthorizedHandler,
} from '@/lib/api'
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
 * ADR 0049 P3b — the personal-elevation callback path (a SECOND, distinct redirect_uri from the
 * primary `/auth/callback`, so the two OIDC round-trips never collide). No React Router `<Route>`
 * exists for it (mirroring `/auth/callback`, which is also handled purely at the pathname level,
 * before the router ever gets a say) — see the elevation effect below for how landing here is
 * consumed without racing the app's own routing.
 */
const ELEVATE_CALLBACK_PATH = '/auth/elevate-callback'

/**
 * How long a personal elevation survives with no pointer/keyboard activity before it auto-drops
 * (ADR 0049 P3b: "short idle TTL" — never leave an owner/manager elevated on a shared till after
 * they walk away). Defence in depth on top of `automaticSilentRenew: false` on the elevation
 * manager below, which already lets the underlying access token itself expire naturally.
 */
const ELEVATION_IDLE_TTL_MS = 10 * 60 * 1000

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
export function AuthProvider({
  children,
  persistSession = false,
}: {
  children: ReactNode
  /**
   * Persist the OIDC session in localStorage even in a plain browser (not just the native shell) —
   * used by the Employee app (a personal-device app, ADR 0049 P5) so an employee stays signed in
   * across app/browser restarts; the offline refresh token then keeps them in for the offline
   * session's idle window (~30 days). The console leaves this `false`: on a shared computer the
   * sessionStorage default is a security feature (the session dies when the browser closes).
   */
  persistSession?: boolean
}) {
  return AUTH_MODE === 'oidc' ? (
    <OidcAuthProvider persistSession={persistSession}>{children}</OidcAuthProvider>
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
  // Simulated personal elevation (no real 2nd OIDC round-trip in dev) — `elevate()` flips this to
  // `['owner']`; `dropElevation()` clears it. Irrelevant/unused outside the device-dev path (a
  // normal dev login is already every role, and nothing in the UI ever calls elevate() there).
  const [elevated, setElevated] = useState(false)
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
      elevatedRoles: elevated ? ['owner'] : [],
      elevate: () => setElevated(true),
      dropElevation: () => setElevated(false),
      login: () => {},
      logout: () => {},
      // No Keycloak in dev mode — nothing to redirect to.
      changePassword: () => console.info('[dev] changePassword is a no-op outside oidc mode'),
      refresh: async () => true,
    }),
    [isDeviceDev, elevated],
  )
  // Ensure no stale bearer leaks into the dev header-trust path.
  useEffect(() => setAccessToken(null), [])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// ---------------------------------------------------------------------------
// oidc: real Keycloak login (authorization-code + PKCE).
// ---------------------------------------------------------------------------
function OidcAuthProvider({
  children,
  persistSession,
}: {
  children: ReactNode
  persistSession: boolean
}) {
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
        // Native Till shell (ADR 0043) AND the Employee app (persistSession, ADR 0049 P5 — a
        // personal-device app) keep the session in localStorage so it survives an app/browser
        // restart; the offline refresh token then re-authenticates silently on reopen (Keycloak
        // token lifetimes still govern expiry). A plain console browser keeps the per-tab
        // sessionStorage default — a shared-computer safeguard (the session dies with the tab).
        userStore: new WebStorageStateStore({
          store:
            isNativeShell() || persistSession ? window.localStorage : window.sessionStorage,
        }),
      }),
  )

  // ADR 0049 P3b — the SECOND, PERSONAL-ELEVATION manager: a distinct redirect_uri (so its OIDC
  // round-trip never collides with the primary's) and a distinct storage prefix, ALWAYS backed by
  // `window.sessionStorage` — even inside the native shell, where the PRIMARY manager above
  // deliberately uses localStorage for kiosk persistence. An elevation must never survive a device
  // restart (a fresh WebView process has no sessionStorage to restore from), and `sessionStorage`
  // clears itself the moment the tab/app process ends either way — the security property the ADR
  // asks for ("never persisted like the outlet credential") falls out of the storage choice alone.
  // `automaticSilentRenew: false`: an elevation should NOT quietly renew forever in the background —
  // once its (short) access token expires it just ends, on top of the explicit idle-TTL below.
  const [elevationManager] = useState(
    () =>
      new UserManager({
        authority: `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}`,
        client_id: KEYCLOAK_CLIENT_ID,
        redirect_uri: `${window.location.origin}${ELEVATE_CALLBACK_PATH}`,
        post_logout_redirect_uri: window.location.origin,
        response_type: 'code',
        scope: KEYCLOAK_SCOPE,
        automaticSilentRenew: false,
        userStore: new WebStorageStateStore({
          store: window.sessionStorage,
          prefix: 'oidc.elevate.',
        }),
      }),
  )

  const [state, setState] = useState<
    Omit<
      AuthState,
      'login' | 'logout' | 'changePassword' | 'refresh' | 'elevatedRoles' | 'elevate' | 'dropElevation'
    >
  >({
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

  // ADR 0049 P3b — the active elevation's roles (`[]` when not elevated; a normal `user` login
  // never touches this). Kept as its OWN state slice, entirely separate from the primary `apply()`
  // above/below: the primary token silently renews often (automaticSilentRenew: true), and that
  // must never reset an in-progress elevation.
  const [elevatedRoles, setElevatedRoles] = useState<BusinessRole[]>([])
  // True while the current URL is the elevation callback and it hasn't finished processing yet —
  // folded into the PUBLIC `ready` below so App.tsx's existing `!auth.ready` boot gate (which
  // already renders nothing routable until ready) also covers this leg, with zero routing changes:
  // no <Routes>/<Navigate> ever mounts while a ?code=/&state= is still sitting in the URL, so there
  // is nothing to race against.
  const [elevateCallbackPending, setElevateCallbackPending] = useState(
    () => window.location.pathname === ELEVATE_CALLBACK_PATH,
  )

  /** Ends the personal elevation locally (till-menu "Sign out of back office" — ADR 0049 P3b): NO
   * Keycloak round-trip (unlike `logout` below), so it is instant and keeps the outlet signed in.
   * Also called from `logout` itself so an outlet sign-out can never leave a stale elevation behind
   * (see that function's doc), and from the idle-TTL/expiry effects below. Stable identity
   * (`useCallback`, only `elevationManager` as a dep — itself stable after mount) so it is safe to
   * reference from the primary effect below without an exhaustive-deps warning. */
  const dropElevation = useCallback(() => {
    setPersonalAccessToken(null)
    setElevatedRoles([])
    void elevationManager.removeUser().catch(() => {
      /* best-effort clear of the stored (dead/absent) elevation session */
    })
  }, [elevationManager])

  useEffect(() => {
    let mounted = true

    function apply(user: User | null) {
      if (!mounted) return
      if (!user || user.expired || !user.access_token) {
        setAccessToken(null)
        // ADR 0049 P3b — an outlet sign-out (or a failed/expired session) always ends any active
        // personal elevation too; a stale elevation must never survive the credential it rode in on.
        dropElevation()
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
      // ADR 0049 P3b — a normal `user` login has no separate elevation concept: its OWN token is
      // already the back-office bearer, so mirror it into the personal slot too (byte-identical
      // outcome for every `auth:'personal'` call — see lib/api.ts's doc). A `device` terminal's
      // personal bearer is managed EXCLUSIVELY by the elevation effect below; touching it here would
      // wipe an in-progress elevation on the outlet token's routine silent renew.
      if (actorType !== 'device') {
        setPersonalAccessToken(user.access_token)
      }
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
      // A stored session whose ACCESS token has expired (the app was closed longer than the ~5-min
      // access-token lifespan) is NOT treated as logged out: try ONE silent renew via the (offline)
      // refresh token first, so reopening a PERSISTED app (native till shell / Employee app) re-auths
      // seamlessly instead of dropping to the login screen. A plain browser with no persisted session
      // (user === null here) skips this and falls straight through to the public site below.
      if (user && user.expired) {
        try {
          const renewed = await manager.signinSilent()
          if (renewed && !renewed.expired) {
            apply(renewed)
            return
          }
        } catch {
          // Refresh/offline token is dead or the offline session has idled out → fall through to
          // the unauthenticated public site; the user signs in explicitly.
        }
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
  }, [manager, dropElevation])

  // ADR 0049 P3b — the elevation manager's OWN init + event wiring, entirely separate from the
  // primary effect above (different manager, different storage, different lifecycle). Handles BOTH
  // landing on the elevate-callback (consuming the redirect and turning `elevatedRoles` on) AND a
  // same-tab reload mid-elevation (`sessionStorage` restoring the manager's own persisted user —
  // never across a device/app restart, since sessionStorage itself doesn't survive that).
  useEffect(() => {
    let mounted = true

    function applyElevation(user: User | null) {
      if (!mounted) return
      if (!user || user.expired || !user.access_token) {
        setPersonalAccessToken(null)
        setElevatedRoles([])
        return
      }
      setPersonalAccessToken(user.access_token)
      setElevatedRoles(extractRoles(decodeJwt(user.access_token)))
    }

    async function init() {
      if (window.location.pathname === ELEVATE_CALLBACK_PATH) {
        try {
          const user = await elevationManager.signinRedirectCallback()
          applyElevation(user)
        } catch {
          // Stale/invalid callback (e.g. React StrictMode's double effect in dev already consumed
          // it) — fall through to "not elevated"; the till-menu's elevate entry is still there to
          // retry.
          applyElevation(null)
        } finally {
          window.history.replaceState({}, document.title, '/')
          if (mounted) setElevateCallbackPending(false)
        }
        return
      }
      try {
        const user = await elevationManager.getUser()
        if (user && !user.expired) applyElevation(user)
      } catch {
        // No restorable elevation — stays dropped (the default state already).
      }
    }

    void init()

    const onLoaded = (user: User) => applyElevation(user)
    const onUnloaded = () => applyElevation(null)
    // No forced re-login on expiry (automaticSilentRenew is off — see the manager's own doc): the
    // elevation just quietly ends, same kiosk-softening reasoning as the primary manager's
    // recoverOrLogout — nobody is standing at a shared till to re-type a manager's password.
    const onExpired = () => applyElevation(null)
    elevationManager.events.addUserLoaded(onLoaded)
    elevationManager.events.addUserUnloaded(onUnloaded)
    elevationManager.events.addAccessTokenExpired(onExpired)
    return () => {
      mounted = false
      elevationManager.events.removeUserLoaded(onLoaded)
      elevationManager.events.removeUserUnloaded(onUnloaded)
      elevationManager.events.removeAccessTokenExpired(onExpired)
    }
  }, [elevationManager])

  // ADR 0049 P3b — the idle-TTL: any pointer/keyboard activity resets the clock while elevated; no
  // activity for ELEVATION_IDLE_TTL_MS auto-drops it. Only wired up WHILE elevated (an unelevated
  // device pays zero listener/timer cost, and a normal `user` login never elevates at all).
  useEffect(() => {
    if (elevatedRoles.length === 0) return
    let timer = window.setTimeout(dropElevation, ELEVATION_IDLE_TTL_MS)
    const reset = () => {
      window.clearTimeout(timer)
      timer = window.setTimeout(dropElevation, ELEVATION_IDLE_TTL_MS)
    }
    window.addEventListener('pointerdown', reset)
    window.addEventListener('keydown', reset)
    return () => {
      window.clearTimeout(timer)
      window.removeEventListener('pointerdown', reset)
      window.removeEventListener('keydown', reset)
    }
  }, [elevatedRoles.length, dropElevation])

  const value = useMemo<AuthState>(
    () => ({
      ...state,
      // Folds in the elevation-callback leg (see `elevateCallbackPending`'s own doc) — App.tsx's
      // existing `!auth.ready` boot gate covers it with NO routing changes.
      ready: state.ready && !elevateCallbackPending,
      elevatedRoles,
      // "Sign in to manage" (till-menu, device terminal only) — starts the SECOND OIDC round-trip.
      // prompt=login FORCES a fresh Keycloak login: without it, KC's SSO session (the device's own
      // till.<outlet> login) would silently re-authenticate the elevation AS THE DEVICE (cashier),
      // never prompting for the owner/manager — so no back-office roles are ever gained. The device
      // itself persists via its offline-refresh token (independent of the SSO session), so forcing
      // a fresh elevation login here does not disturb the kiosk.
      elevate: () => void elevationManager.signinRedirect({ prompt: 'login' }),
      dropElevation,
      // login_hint pre-fills Keycloak's username field (used by the post-signup hand-off).
      login: (loginHint?: string) =>
        void manager.signinRedirect(loginHint ? { login_hint: loginHint } : undefined),
      logout: () => {
        setAccessToken(null)
        // ADR 0049 P3b — "Log out outlet" (the till-menu's owner/manager-guarded action) IS this
        // same `logout()` (no separate method): an outlet sign-out must never leave a stale
        // elevation behind. A no-op for a normal `user` login (elevatedRoles is already `[]`).
        dropElevation()
        // Determinism nit: clear the operator-session header directly here rather than relying
        // SOLELY on OperatorSessionProvider's authenticated->false safety-net effect (which only
        // fires once React re-renders after `auth.authenticated` flips — a real transition, but not
        // an immediate one, and `signoutRedirect()` below navigates away before that render is
        // guaranteed to happen). The provider's own effect still runs on any OTHER
        // authenticated->false transition (e.g. a failed silent renew) and remains the source of
        // truth for localStorage + its own React state; this just makes the outlet's own explicit
        // logout synchronous too.
        setOperatorSession(null)
        void manager.signoutRedirect()
      },
      // Employee self-service "Account settings" (two-app program) — the SAME signinRedirect
      // mechanism `elevate()` above uses, but on the PRIMARY manager (this is the caller's own
      // credential, not a second elevation login) and with `kc_action: 'UPDATE_PASSWORD'` instead
      // of `prompt: 'login'`. Keycloak runs its built-in secure change-password action for the
      // already-authenticated principal and redirects back to `/auth/callback`, which `init()`
      // above already handles exactly like any other login return.
      changePassword: () =>
        void manager.signinRedirect({ extraQueryParams: { kc_action: 'UPDATE_PASSWORD' } }),
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
    [state, manager, elevationManager, elevatedRoles, elevateCallbackPending, dropElevation],
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
