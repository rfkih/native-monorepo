/**
 * AI-native failure diagnostics — a small in-memory record of recent API failures, rich enough
 * that a failure shown in the UI can be copied as ONE structured JSON blob and handed to a human
 * or an AI assistant to diagnose without reproduction ("Request failed (401)" tells nobody
 * anything; this tells everything the browser legitimately knows).
 *
 * PRIVACY RULES (deliberate, do not relax):
 *  - NEVER store or export the raw bearer token — only derived, non-secret state (present?,
 *    expiry timestamps, company COUNT). Claims that are tenant PII never enter the record.
 *  - Request BODIES are not recorded (they can carry PII — phone numbers, names).
 *  - The record lives in memory only (no localStorage) and holds a bounded ring of entries.
 */

export interface TokenState {
  /** Whether an Authorization bearer was attached to the failing request. */
  present: boolean
  /** epoch-seconds `exp` of the attached token, when decodable. */
  exp: number | null
  /** Seconds until expiry at failure time — negative = the token was already EXPIRED. */
  expiresInSeconds: number | null
  /** How many companies the token's company_id claim carried (0 = claim absent/malformed). */
  companyCount: number | null
  /**
   * Whether the X-Company-Id SELECTION the request carried is inside the token's company_id
   * claim — `false` is the signature of a STALE CLAIM (a company created/bound after this token
   * was minted): the gateway rejects the selection with a body-less 403, so this derived boolean
   * is the only client-side way to recognize the case and offer a session refresh. `null` when
   * the request carried no selection or the claim was undecodable.
   */
  selectedCompanyInClaim: boolean | null
}

export interface FailureRecord {
  at: string
  method: string
  path: string
  status: number
  /** RFC-7807 `type` (the stable machine identity of the fault), when the body carried one. */
  problemType: string | null
  problemDetail: string | null
  /** The server-stamped trace id (problem+json `traceId`) — joins this failure to backend logs. */
  traceId: string | null
  /** The X-Company-Id SELECTION the request carried (a selection, never a secret). */
  companyId: string | null
  authMode: string
  tokenState: TokenState | null
}

const MAX_ENTRIES = 20
const failures: FailureRecord[] = []
const listeners = new Set<() => void>()

/**
 * Decodes the non-secret state of a JWT (exp + company count + whether the request's company
 * SELECTION is inside the claim) without verifying it.
 */
export function deriveTokenState(token: string | null, selectedCompanyId?: string | null): TokenState {
  if (!token)
    return {
      present: false,
      exp: null,
      expiresInSeconds: null,
      companyCount: null,
      selectedCompanyInClaim: null,
    }
  try {
    const payloadB64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    const payload = JSON.parse(atob(payloadB64)) as {
      exp?: number
      company_id?: string | string[]
    }
    const exp = typeof payload.exp === 'number' ? payload.exp : null
    const cid = payload.company_id
    const claimIds = cid == null ? [] : Array.isArray(cid) ? cid : [cid]
    return {
      present: true,
      exp,
      expiresInSeconds: exp != null ? Math.round(exp - Date.now() / 1000) : null,
      companyCount: claimIds.length,
      selectedCompanyInClaim: selectedCompanyId ? claimIds.includes(selectedCompanyId) : null,
    }
  } catch {
    return {
      present: true,
      exp: null,
      expiresInSeconds: null,
      companyCount: null,
      selectedCompanyInClaim: null,
    }
  }
}

export function recordFailure(entry: FailureRecord): void {
  failures.push(entry)
  if (failures.length > MAX_ENTRIES) failures.shift()
  listeners.forEach((l) => l())
}

/** Most recent failure, optionally the most recent one matching a path prefix. */
export function lastFailure(pathPrefix?: string): FailureRecord | null {
  for (let i = failures.length - 1; i >= 0; i--) {
    if (!pathPrefix || failures[i].path.startsWith(pathPrefix)) return failures[i]
  }
  return null
}

export function allFailures(): readonly FailureRecord[] {
  return failures
}

export function subscribeFailures(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** The copyable diagnostic bundle — everything above plus environment context. */
export function diagnosticBundle(focus?: FailureRecord | null): string {
  return JSON.stringify(
    {
      generatedAt: new Date().toISOString(),
      href: typeof window !== 'undefined' ? window.location.href : null,
      userAgent: typeof navigator !== 'undefined' ? navigator.userAgent : null,
      focusFailure: focus ?? lastFailure(),
      recentFailures: allFailures().slice(-8),
    },
    null,
    2,
  )
}
