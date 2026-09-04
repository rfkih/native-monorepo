import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  OWNER_SESSION_MAX_MS,
  clearAuthEstablished,
  isTerminalAuthError,
  ownerSessionExpired,
  readAuthEstablished,
  resolveAuthAtMs,
  stampAuthEstablished,
} from '../authSession'

/**
 * vitest here runs `environment: 'node'` (no jsdom) — Web Storage isn't global. A ~15-line in-memory
 * `Storage` (the same pattern SessionProvider.test uses) lets the persistence helpers run; the pure
 * decision helpers need no DOM at all. `isNativeShell()` reads a bare polyfilled window and returns
 * false (no androidBridge/Capacitor/NativePrint), so the store choice follows `persistSession`.
 */
class MemStorage implements Storage {
  private m = new Map<string, string>()
  get length() {
    return this.m.size
  }
  clear() {
    this.m.clear()
  }
  getItem(k: string) {
    return this.m.has(k) ? this.m.get(k)! : null
  }
  key(i: number) {
    return Array.from(this.m.keys())[i] ?? null
  }
  removeItem(k: string) {
    this.m.delete(k)
  }
  setItem(k: string, v: string) {
    this.m.set(k, String(v))
  }
}

const AUTH_ESTABLISHED_KEY = 'native.auth.establishedAt'
const hadWindow = 'window' in globalThis
const originalWindow = (globalThis as unknown as { window?: unknown }).window

beforeEach(() => {
  ;(globalThis as unknown as { window: unknown }).window = {
    localStorage: new MemStorage(),
    sessionStorage: new MemStorage(),
  }
})
afterEach(() => {
  if (hadWindow) (globalThis as unknown as { window: unknown }).window = originalWindow
  else delete (globalThis as unknown as { window?: unknown }).window
})

describe('isTerminalAuthError', () => {
  it('is TRUE only for the definitive dead-token OIDC errors', () => {
    expect(isTerminalAuthError({ error: 'invalid_grant' })).toBe(true)
    expect(isTerminalAuthError({ error: 'invalid_token' })).toBe(true)
    expect(isTerminalAuthError({ error: 'login_required' })).toBe(true)
    expect(isTerminalAuthError({ error: 'interaction_required' })).toBe(true)
  })

  it('is FALSE for transient failures — a session must never be discarded on a blip', () => {
    // Network / transport failures (the reopen-with-no-signal case) — plain Errors, no `error` field.
    expect(isTerminalAuthError(new Error('Network Error'))).toBe(false)
    expect(isTerminalAuthError(new TypeError('Failed to fetch'))).toBe(false)
    // A non-terminal OIDC/5xx code.
    expect(isTerminalAuthError({ error: 'temporarily_unavailable' })).toBe(false)
    // Junk / absent.
    expect(isTerminalAuthError(null)).toBe(false)
    expect(isTerminalAuthError(undefined)).toBe(false)
    expect(isTerminalAuthError('invalid_grant')).toBe(false)
    expect(isTerminalAuthError({})).toBe(false)
  })
})

describe('resolveAuthAtMs', () => {
  it('prefers the auth_time claim (seconds → ms)', () => {
    expect(resolveAuthAtMs(1000, 9_999)).toBe(1_000_000)
  })
  it('falls back to the stored login timestamp when auth_time is absent/invalid', () => {
    expect(resolveAuthAtMs(null, 5_000)).toBe(5_000)
    expect(resolveAuthAtMs(undefined, 5_000)).toBe(5_000)
    expect(resolveAuthAtMs(Number.NaN, 5_000)).toBe(5_000)
  })
  it('is null when neither source is available', () => {
    expect(resolveAuthAtMs(null, null)).toBeNull()
    expect(resolveAuthAtMs(undefined, Number.NaN)).toBeNull()
  })
})

describe('ownerSessionExpired', () => {
  const now = 1_000_000_000_000

  it('forces re-login for an owner past the 24h cap', () => {
    expect(
      ownerSessionExpired({ roles: ['owner'], authAtMs: now - OWNER_SESSION_MAX_MS - 1, nowMs: now }),
    ).toBe(true)
  })

  it('keeps an owner within 24h (boundary is inclusive — exactly 24h is still valid)', () => {
    expect(
      ownerSessionExpired({ roles: ['owner'], authAtMs: now - OWNER_SESSION_MAX_MS, nowMs: now }),
    ).toBe(false)
    expect(ownerSessionExpired({ roles: ['owner'], authAtMs: now - 1000, nowMs: now })).toBe(false)
  })

  it('never caps a non-owner (staff, cashier, or a kiosk device) even past 24h', () => {
    const old = now - OWNER_SESSION_MAX_MS - 1
    expect(ownerSessionExpired({ roles: ['cashier'], authAtMs: old, nowMs: now })).toBe(false)
    expect(ownerSessionExpired({ roles: ['manager'], authAtMs: old, nowMs: now })).toBe(false)
    expect(ownerSessionExpired({ roles: [], authAtMs: old, nowMs: now })).toBe(false)
  })

  it('caps a multi-role login that INCLUDES owner', () => {
    expect(
      ownerSessionExpired({
        roles: ['owner', 'manager'],
        authAtMs: now - OWNER_SESSION_MAX_MS - 1,
        nowMs: now,
      }),
    ).toBe(true)
  })

  it('never logs out on a missing/unknown auth time (no lockout on missing data)', () => {
    expect(ownerSessionExpired({ roles: ['owner'], authAtMs: null, nowMs: now })).toBe(false)
  })
})

describe('authEstablished persistence', () => {
  it('round-trips through localStorage for a persisted session', () => {
    stampAuthEstablished(true, 1234)
    expect(readAuthEstablished(true)).toBe(1234)
    expect(
      (globalThis as unknown as { window: { localStorage: Storage } }).window.localStorage.getItem(
        AUTH_ESTABLISHED_KEY,
      ),
    ).toBe('1234')
  })

  it('round-trips through sessionStorage for a non-persisted (console tab) session', () => {
    stampAuthEstablished(false, 5678)
    expect(readAuthEstablished(false)).toBe(5678)
    // Not written to localStorage.
    expect(readAuthEstablished(true)).toBeNull()
  })

  it('reads null when unset and after clear, and null on garbage', () => {
    expect(readAuthEstablished(true)).toBeNull()
    stampAuthEstablished(true, 42)
    clearAuthEstablished(true)
    expect(readAuthEstablished(true)).toBeNull()
    ;(globalThis as unknown as { window: { localStorage: Storage } }).window.localStorage.setItem(
      AUTH_ESTABLISHED_KEY,
      'not-a-number',
    )
    expect(readAuthEstablished(true)).toBeNull()
  })
})
