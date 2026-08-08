/**
 * operatorSessionStore.ts — pure persistence for the till's signed-in operator (ADR 0049 P3b). No
 * React, no fetch: just localStorage read/write/validate, so the expiry/shape logic is unit
 * testable without a DOM (this repo's vitest runs with `environment: 'node'` — see
 * lib/__tests__/SessionProvider.test.ts's header doc for the same constraint).
 *
 * ONE operator at a time, ONE key: a Business-app device rings for exactly one outlet (ADR 0049 —
 * the outlet credential carries a single `user_outlet_assignment`), so there is never more than one
 * operator session to persist.
 */

export interface OperatorSessionData {
  /** The opaque, HMAC-signed operator token — sent as `X-Operator-Session` (lib/api.ts). */
  token: string
  /** Name + role, exactly what the till shows (rule 6 — no PII beyond this). */
  displayName: string
  role: string
  /** ISO-8601 instant (the token's `exp` claim). Stored as a string, not epoch millis, so a
   * persisted row stays trivially human-readable in devtools. */
  expiresAt: string
}

export const OPERATOR_SESSION_STORAGE_KEY = 'native.console.operatorSession'

/**
 * True if `data` parses to a well-formed {@link OperatorSessionData} — defends a corrupted or
 * foreign localStorage value (a hand-edited devtools entry, a future schema change) from crashing
 * the till instead of just failing closed to "no operator".
 */
function isWellFormed(data: unknown): data is OperatorSessionData {
  if (!data || typeof data !== 'object') return false
  const d = data as Record<string, unknown>
  return (
    typeof d.token === 'string' &&
    d.token.length > 0 &&
    typeof d.displayName === 'string' &&
    typeof d.role === 'string' &&
    typeof d.expiresAt === 'string' &&
    !Number.isNaN(Date.parse(d.expiresAt))
  )
}

/**
 * Reads the persisted operator session, or null when absent, corrupted, or EXPIRED as of `now`
 * (defaults to the real clock — a param so this is deterministic to test). An expired/corrupted row
 * is actively cleared, not just ignored, so it can never resurrect on a later read.
 */
export function loadOperatorSession(
  storage: Pick<Storage, 'getItem' | 'removeItem'>,
  now: number = Date.now(),
): OperatorSessionData | null {
  let raw: string | null
  try {
    raw = storage.getItem(OPERATOR_SESSION_STORAGE_KEY)
  } catch {
    return null
  }
  if (!raw) return null

  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    clearOperatorSession(storage)
    return null
  }

  if (!isWellFormed(parsed) || Date.parse(parsed.expiresAt) <= now) {
    clearOperatorSession(storage)
    return null
  }
  return parsed
}

/** Persists the operator session. Never throws into the caller's sign-in flow — a full/unavailable
 * localStorage just means the session won't survive a reload; the in-memory state still governs
 * the current tab until then. */
export function saveOperatorSession(
  storage: Pick<Storage, 'setItem'>,
  data: OperatorSessionData,
): void {
  try {
    storage.setItem(OPERATOR_SESSION_STORAGE_KEY, JSON.stringify(data))
  } catch {
    /* storage unavailable/full — best-effort only, see doc above */
  }
}

export function clearOperatorSession(storage: Pick<Storage, 'removeItem'>): void {
  try {
    storage.removeItem(OPERATOR_SESSION_STORAGE_KEY)
  } catch {
    /* best-effort */
  }
}
