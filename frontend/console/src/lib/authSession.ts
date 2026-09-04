/**
 * authSession — the pure decision helpers behind the OIDC session lifetime policy (see `auth.tsx`),
 * split out so they are unit-testable without a live Keycloak.
 *
 * Policy:
 *  - Staff/employee (and kiosk `device`) logins ride Keycloak's offline session (default ~30-day
 *    idle) — the app must never DISCARD that session on a transient refresh failure (a reopen with
 *    no signal, a momentarily-unreachable IdP). Only a TERMINAL error (the offline token is
 *    genuinely dead) ends it.
 *  - An `owner` login is capped at 24h: an owner re-authenticates at most once a day.
 */
import { isNativeShell } from '@/lib/escpos/transport'

/** The owner-role session cap: an owner re-authenticates at most once a day (see `auth.tsx`). */
export const OWNER_SESSION_MAX_MS = 24 * 60 * 60 * 1000

/** localStorage key holding the ms timestamp of the last INTERACTIVE (fresh) login. */
const AUTH_ESTABLISHED_KEY = 'native.auth.establishedAt'

/**
 * The store the OIDC session lives in — `localStorage` inside the native shell or a `persistSession`
 * app (where the offline token persists across restarts), else per-tab `sessionStorage`. Mirrors
 * `AuthProvider`'s `WebStorageStateStore` choice EXACTLY so the login timestamp shares the session's
 * lifetime. Returns null in a non-browser (test/node) env.
 */
function sessionStore(persistSession: boolean): Storage | null {
  if (typeof window === 'undefined') return null
  return isNativeShell() || persistSession ? window.localStorage : window.sessionStorage
}

/**
 * True when a silent-renew error is TERMINAL — the refresh/offline token is genuinely dead (Keycloak
 * `invalid_grant`: the offline-session idle elapsed, the token was revoked, or the user logged out
 * elsewhere), so the only recovery is a fresh login. EVERYTHING else — a network error, a 5xx, a
 * timeout, an abort — is TRANSIENT: the token may still be valid, so the session must be KEPT and
 * retried, never discarded. oidc-client-ts surfaces an OIDC protocol error as an object carrying a
 * string `error` field (its `ErrorResponse`); a transport failure is a plain `Error`/`TypeError`
 * with no such field, so it reads as transient (the safe default — we never nuke a session on a blip).
 */
export function isTerminalAuthError(err: unknown): boolean {
  if (!err || typeof err !== 'object') return false
  const code = (err as { error?: unknown }).error
  return (
    code === 'invalid_grant' ||
    code === 'invalid_token' ||
    // `login_required`/`interaction_required` come off the iframe silent-renew path when the SSO
    // session is gone: a silent renew can NEVER succeed, so end the session rather than retry forever.
    code === 'login_required' ||
    code === 'interaction_required'
  )
}

/**
 * The effective "authenticated at" instant in ms — prefer the OIDC `auth_time` claim (seconds since
 * epoch; the ORIGINAL authentication, preserved across silent renews, so it is the true session
 * start), else the stored fresh-login timestamp, else null when neither is available.
 */
export function resolveAuthAtMs(
  authTimeSec: number | null | undefined,
  storedMs: number | null,
): number | null {
  if (authTimeSec != null && Number.isFinite(authTimeSec)) return authTimeSec * 1000
  return storedMs != null && Number.isFinite(storedMs) ? storedMs : null
}

/**
 * True when an `owner` login has outlived the 24h cap. ONLY the `owner` role is capped — every other
 * login (manager, cashier, other staff, and kiosk `device`) returns false and keeps the long window.
 * A null/unknown `authAtMs` never forces a logout: we never lock someone out on missing data (the
 * server-side token expiry is still the backstop).
 */
export function ownerSessionExpired(args: {
  roles: readonly string[]
  authAtMs: number | null
  nowMs: number
}): boolean {
  if (!args.roles.includes('owner')) return false
  if (args.authAtMs == null || !Number.isFinite(args.authAtMs)) return false
  return args.nowMs - args.authAtMs > OWNER_SESSION_MAX_MS
}

/** Records the moment of a fresh (interactive) login — the owner-cap fallback when `auth_time` is absent. */
export function stampAuthEstablished(persistSession: boolean, nowMs: number): void {
  try {
    sessionStore(persistSession)?.setItem(AUTH_ESTABLISHED_KEY, String(nowMs))
  } catch {
    // Storage full/blocked — the cap falls back to the token's auth_time, so this is best-effort.
  }
}

/** The stored fresh-login timestamp (ms), or null when unset/unreadable. */
export function readAuthEstablished(persistSession: boolean): number | null {
  try {
    const raw = sessionStore(persistSession)?.getItem(AUTH_ESTABLISHED_KEY)
    const n = raw == null ? NaN : Number(raw)
    return Number.isFinite(n) ? n : null
  } catch {
    return null
  }
}

/** Clears the stored fresh-login timestamp (on logout / owner-cap re-auth). */
export function clearAuthEstablished(persistSession: boolean): void {
  try {
    sessionStore(persistSession)?.removeItem(AUTH_ESTABLISHED_KEY)
  } catch {
    // best-effort
  }
}
