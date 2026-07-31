/**
 * Tiny typed API client for the Native gateway.
 *
 * Two auth modes (see {@link AUTH_MODE}):
 *  - `oidc` (production): send `Authorization: Bearer <token>`. The gateway VALIDATES the token and
 *    derives `X-Company-Id` / `X-Actor` / `X-Roles` from it, stripping any browser-supplied copies —
 *    so the console never asserts its own tenant.
 *  - `dev` (local, no Keycloak): send the header-trust `X-Company-Id` / `X-Actor` the dev
 *    `DevTenantFilter` reads, exactly as before.
 */
import { API_BASE_URL, AUTH_MODE } from '@/lib/config'
import { deriveTokenState, recordFailure } from '@/lib/diagnostics'

/** The current bearer token in oidc mode; null in dev mode. Set by the AuthProvider. */
let accessToken: string | null = null

/** Called by the auth layer whenever the access token changes (or clears on logout). */
export function setAccessToken(token: string | null): void {
  accessToken = token
}

export interface ProblemDetail {
  type?: string
  title?: string
  status?: number
  detail?: string
  errors?: { field: string; message: string }[]
}

export class ApiError extends Error {
  status: number
  problem: ProblemDetail | null
  constructor(status: number, problem: ProblemDetail | null, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }
}

/**
 * Detects the Phase-5 403 outlet-not-assigned problem+json (stable type URI
 * `https://errors.nativeapp.id/outlet-not-assigned`) thrown when a cashier rings a sale at an
 * outlet they are not assigned to. Surfaces on checkout / pay-parked / open-bill / pay-bill.
 * The caller maps it to the `pos.payment.outletNotAssigned` i18n key.
 */
export function isOutletNotAssigned(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 403 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('outlet-not-assigned')
  )
}

/**
 * Detects the org-service 409 `org-unit-has-data` problem+json (stable type URI
 * `https://errors.nativeapp.id/org-unit-has-data`) thrown when a permanent org-unit delete is
 * refused because the unit (or a descendant) has, or ever had, an assigned login. Surfaces on the
 * org-tree delete action; the caller maps it to the `org.deleteDialog.conflict` i18n key.
 */
export function isOrgUnitHasData(err: unknown): boolean {
  return (
    err instanceof ApiError &&
    err.status === 409 &&
    typeof err.problem?.type === 'string' &&
    err.problem.type.includes('org-unit-has-data')
  )
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  /** Tenant-scoped call: sends X-Company-Id + X-Actor. */
  tenant?: { companyId: string; actor: string }
  /** Bootstrap call (e.g. create-company): sends X-Actor only. */
  actor?: string
  query?: Record<string, string | undefined>
  /**
   * Extra headers merged in after the auth/tenant headers (e.g. `Idempotency-Key`). Callers win on
   * conflict — lets a call override a default if it ever needs to.
   */
  headers?: Record<string, string>
}

function buildQuery(query?: Record<string, string | undefined>): string {
  if (!query) return ''
  const params = new URLSearchParams()
  for (const [k, v] of Object.entries(query)) {
    if (v != null && v !== '') params.set(k, v)
  }
  const s = params.toString()
  return s ? `?${s}` : ''
}

export async function apiFetch<T>(path: string, opts: RequestOptions = {}): Promise<T | null> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json'
  if (AUTH_MODE === 'oidc') {
    // Actor/roles come from the verified token. X-Company-Id is the ACTIVE-COMPANY SELECTION
    // (multi-company ownership, ADR 0021): the gateway and each service validate it against the
    // token's allowed set before binding — a value outside the set is a 403, so this is a
    // selection, never a trusted tenant. Omitted (bootstrap calls), the token's first company is
    // the default.
    if (accessToken) headers['Authorization'] = `Bearer ${accessToken}`
    if (opts.tenant) headers['X-Company-Id'] = opts.tenant.companyId
  } else if (opts.tenant) {
    headers['X-Company-Id'] = opts.tenant.companyId
    headers['X-Actor'] = opts.tenant.actor
  } else if (opts.actor) {
    headers['X-Actor'] = opts.actor
  }
  if (opts.headers) Object.assign(headers, opts.headers)

  const res = await fetch(API_BASE_URL + path + buildQuery(opts.query), {
    method: opts.method ?? 'GET',
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  })

  if (res.status === 204) return null
  const text = await res.text()
  const data: unknown = text ? JSON.parse(text) : null

  if (!res.ok) {
    const problem = (data as ProblemDetail | null) ?? null
    // AI-native diagnostics: every failure is recorded with the non-secret facts an assistant
    // needs to diagnose it in one hop (status, problem type, server traceId, the company
    // SELECTION, and the token's derived state — never the token itself, never the body).
    recordFailure({
      at: new Date().toISOString(),
      method: opts.method ?? 'GET',
      path,
      status: res.status,
      problemType: problem?.type ?? null,
      problemDetail: problem?.detail ?? problem?.title ?? null,
      traceId:
        typeof (problem as Record<string, unknown> | null)?.traceId === 'string'
          ? ((problem as Record<string, unknown>).traceId as string)
          : null,
      companyId: opts.tenant?.companyId ?? null,
      authMode: AUTH_MODE,
      tokenState: AUTH_MODE === 'oidc' ? deriveTokenState(accessToken) : null,
    })
    throw new ApiError(
      res.status,
      problem,
      problem?.detail || problem?.title || `Request failed (${res.status})`,
    )
  }
  return data as T
}
