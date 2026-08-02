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

/**
 * A handler the auth layer registers to react to an AUTHORIZATION FAILURE in oidc mode: a request
 * rejected with 401 means the bearer is expired/invalid at the gateway. Rather than surface a
 * silent, un-actionable error, the auth layer recovers the session (silent renew) or ENDS it
 * (log out → sign-in screen). The handler is debounced there, so many concurrent 401s (a dashboard
 * fires ~10 queries at once) trigger a single recovery, not a storm.
 */
let onUnauthorized: (() => void) | null = null
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  onUnauthorized = handler
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
  /** Request identity + server trace id — carried so an error UI can show/copy the ROOT CAUSE
   *  (see components/ErrorDetails) instead of a bare "Request failed (nnn)". */
  method: string
  path: string
  traceId: string | null
  // The request-identity params are OPTIONAL: tests (and the offline queue) construct synthetic
  // ApiErrors without them; ErrorDetails simply omits what is absent.
  constructor(
    status: number,
    problem: ProblemDetail | null,
    message: string,
    method = '',
    path = '',
    traceId: string | null = null,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
    this.method = method
    this.path = path
    this.traceId = traceId
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

/** The tenant/actor headers shared by {@link apiFetch} and {@link apiUpload} — see each's docs. */
function authHeaders(tenant?: { companyId: string; actor: string }, actor?: string): Record<string, string> {
  const headers: Record<string, string> = {}
  if (AUTH_MODE === 'oidc') {
    // Actor/roles come from the verified token. X-Company-Id is the ACTIVE-COMPANY SELECTION
    // (multi-company ownership, ADR 0021): the gateway and each service validate it against the
    // token's allowed set before binding — a value outside the set is a 403, so this is a
    // selection, never a trusted tenant. Omitted (bootstrap calls), the token's first company is
    // the default.
    if (accessToken) headers['Authorization'] = `Bearer ${accessToken}`
    if (tenant) headers['X-Company-Id'] = tenant.companyId
  } else if (tenant) {
    headers['X-Company-Id'] = tenant.companyId
    headers['X-Actor'] = tenant.actor
  } else if (actor) {
    headers['X-Actor'] = actor
  }
  return headers
}

/**
 * Shared response handling for {@link apiFetch} and {@link apiUpload}: 204 → null, parse the
 * problem+json body on failure, record AI-native diagnostics, trigger the 401 recovery hook, and
 * throw a typed {@link ApiError} — never a bare fetch rejection or an unparsed body.
 */
async function handleResponse<T>(
  res: Response,
  method: string,
  path: string,
  companyId: string | undefined,
): Promise<T | null> {
  if (res.status === 204) return null
  const text = await res.text()
  const data: unknown = text ? JSON.parse(text) : null

  if (!res.ok) {
    const problem = (data as ProblemDetail | null) ?? null
    const traceId =
      typeof (problem as Record<string, unknown> | null)?.traceId === 'string'
        ? ((problem as Record<string, unknown>).traceId as string)
        : null
    // AI-native diagnostics: every failure is recorded with the non-secret facts an assistant
    // needs to diagnose it in one hop (status, problem type, server traceId, the company
    // SELECTION, and the token's derived state — never the token itself, never the body).
    recordFailure({
      at: new Date().toISOString(),
      method,
      path,
      status: res.status,
      problemType: problem?.type ?? null,
      problemDetail: problem?.detail ?? problem?.title ?? null,
      traceId,
      companyId: companyId ?? null,
      authMode: AUTH_MODE,
      tokenState: AUTH_MODE === 'oidc' ? deriveTokenState(accessToken, companyId) : null,
    })
    // 401 in oidc mode = the gateway rejected the bearer (expired/invalid). Signal the auth layer
    // to recover-or-logout so the session can't just keep silently failing. Still throw so the
    // caller's query settles; the auth layer runs its recovery in parallel (and, if it renews,
    // react-query's retry succeeds with the fresh token).
    if (res.status === 401 && AUTH_MODE === 'oidc') {
      onUnauthorized?.()
    }
    throw new ApiError(
      res.status,
      problem,
      problem?.detail || problem?.title || `Request failed (${res.status})`,
      method,
      path,
      traceId,
    )
  }
  return data as T
}

export async function apiFetch<T>(path: string, opts: RequestOptions = {}): Promise<T | null> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json'
  Object.assign(headers, authHeaders(opts.tenant, opts.actor))
  if (opts.headers) Object.assign(headers, opts.headers)

  const res = await fetch(API_BASE_URL + path + buildQuery(opts.query), {
    method: opts.method ?? 'GET',
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  })

  return handleResponse<T>(res, opts.method ?? 'GET', path, opts.tenant?.companyId)
}

export interface UploadOptions {
  /** Tenant-scoped call: sends X-Company-Id + X-Actor. */
  tenant?: { companyId: string; actor: string }
  /** Bootstrap call: sends X-Actor only. */
  actor?: string
  /** Extra headers merged in after the auth/tenant headers (callers win on conflict). */
  headers?: Record<string, string>
}

/**
 * Uploads a {@link FormData} body (multipart/form-data) — the receipt-photo upload endpoint's
 * client (ADR 0030 §8, Phase E3; the first multipart upload in the console). Always POSTs.
 *
 * Deliberately sets NO `Content-Type` header: the browser computes
 * `multipart/form-data; boundary=...` itself from the `FormData` body, and setting it manually
 * (even to the same-looking string) drops the boundary parameter and breaks server-side parsing.
 * Auth/tenant headers are identical to {@link apiFetch} (bearer + company selection in oidc mode,
 * header-trust X-Company-Id/X-Actor in dev mode); no `Idempotency-Key` is added here — a receipt
 * upload is replace-idempotent by nature (see the server-side `ReceiptWriter` Javadoc), so callers
 * that need one for a DIFFERENT reason may still pass it via `opts.headers`.
 */
export async function apiUpload<T>(
  path: string,
  formData: FormData,
  opts: UploadOptions = {},
): Promise<T | null> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  Object.assign(headers, authHeaders(opts.tenant, opts.actor))
  if (opts.headers) Object.assign(headers, opts.headers)

  const res = await fetch(API_BASE_URL + path, {
    method: 'POST',
    headers,
    body: formData,
  })

  return handleResponse<T>(res, 'POST', path, opts.tenant?.companyId)
}
