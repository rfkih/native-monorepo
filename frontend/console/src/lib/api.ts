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
import { deliverDownload } from '@/lib/csv'
import { deriveTokenState, recordFailure } from '@/lib/diagnostics'

/** The current bearer token in oidc mode; null in dev mode. Set by the AuthProvider. */
let accessToken: string | null = null

/** Called by the auth layer whenever the access token changes (or clears on logout). */
export function setAccessToken(token: string | null): void {
  accessToken = token
}

/**
 * ADR 0049 P3b — the PERSONAL bearer for back-office calls (`opts.auth: 'personal'`, see {@link
 * authHeaders}). For a normal `user` login this mirrors {@link accessToken} exactly (there is only
 * ever one login, so "personal" and "outlet" are the same token) — set by `lib/auth.tsx`'s
 * `OidcAuthProvider` whenever a non-device principal loads. For a `device` terminal (the
 * Business-app till) this stays `null` until an owner/manager ELEVATES on top (a second, personal
 * OIDC login — `AuthState.elevate()`); it is the elevation token, never the outlet credential, and
 * the outlet token remains the ONLY tenant source (`X-Company-Id` is unaffected by this — see
 * {@link authHeaders}).
 */
let personalAccessToken: string | null = null

/** Called by the auth layer whenever the personal/elevation bearer changes (or clears). */
export function setPersonalAccessToken(token: string | null): void {
  personalAccessToken = token
}

/**
 * The Business-app till's signed-in operator token (ADR 0049 P3b) — null for every normal `user`
 * login and for a `device` terminal with no operator signed in yet. Set by
 * `features/operator/OperatorSessionProvider` on operator sign-in/sign-out/expiry, sent as
 * `X-Operator-Session` on every request (see {@link authHeaders}). A vertical that doesn't consume
 * it (P2 wired only the restaurant/carwash/barbershop sale-write paths) simply ignores the header —
 * this is deliberately unconditional, exactly like `X-Company-Id`, rather than scoped per call.
 */
let operatorSessionToken: string | null = null

/** Called by the operator-session provider whenever the signed-in operator changes. */
export function setOperatorSession(token: string | null): void {
  operatorSessionToken = token
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
  /** Server trace id (present when a trace is in flight). */
  traceId?: string
  /** The user-quotable error reference the server persisted to error_log (500s). */
  reference?: string
}

export class ApiError extends Error {
  status: number
  problem: ProblemDetail | null
  /** Request identity + server trace id — carried so an error UI can show/copy the ROOT CAUSE
   *  (see components/ErrorDetails) instead of a bare "Request failed (nnn)". */
  method: string
  path: string
  traceId: string | null
  /** The user-quotable reference the server persisted (500s); falls back to traceId. */
  reference: string | null
  // The request-identity params are OPTIONAL: tests (and the offline queue) construct synthetic
  // ApiErrors without them; ErrorDetails simply omits what is absent.
  constructor(
    status: number,
    problem: ProblemDetail | null,
    message: string,
    method = '',
    path = '',
    traceId: string | null = null,
    reference: string | null = null,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
    this.method = method
    this.path = path
    this.traceId = traceId
    this.reference = reference
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

/**
 * ADR 0049 P3b — which bearer a call uses in oidc mode: `'outlet'` (default) is the till's own
 * credential (POS/sale/menu/register/operator-session calls — everything a bare cashier or an
 * unelevated device terminal must be able to do); `'personal'` is the signed-in PERSON's bearer
 * (every canDashboard-gated back-office read/write — dashboard, statements, AR/AP, bank, tax, org,
 * team, close, budgets, HR/payroll, etc.) — for a normal `user` login the two are the SAME token
 * (see {@link setPersonalAccessToken}'s doc), so this only matters on a device terminal. Ignored in
 * dev mode (header-trust, no bearer at all). `X-Company-Id` is unaffected either way — the outlet
 * token stays the only tenant source.
 */
export type AuthTarget = 'outlet' | 'personal'

/**
 * Pure bearer selection (ADR 0049 P3b) — kept standalone (no module state, no AUTH_MODE) so it is
 * unit-testable in isolation; {@link authHeaders} is the only caller.
 */
export function selectBearerToken(
  target: AuthTarget,
  tokens: { outlet: string | null; personal: string | null },
): string | null {
  return target === 'personal' ? tokens.personal : tokens.outlet
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
  /** Which bearer to send in oidc mode — see {@link AuthTarget}. Defaults to `'outlet'`. */
  auth?: AuthTarget
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
function authHeaders(
  tenant?: { companyId: string; actor: string },
  actor?: string,
  auth: AuthTarget = 'outlet',
): Record<string, string> {
  const headers: Record<string, string> = {}
  if (AUTH_MODE === 'oidc') {
    // Actor/roles come from the verified token. X-Company-Id is the ACTIVE-COMPANY SELECTION
    // (multi-company ownership, ADR 0021): the gateway and each service validate it against the
    // token's allowed set before binding — a value outside the set is a 403, so this is a
    // selection, never a trusted tenant. Omitted (bootstrap calls), the token's first company is
    // the default. ADR 0049 P3b: which BEARER (outlet vs personal) is selected via `auth` — see
    // {@link selectBearerToken}; X-Company-Id itself is unaffected — the outlet stays the only
    // tenant source either way.
    const bearer = selectBearerToken(auth, { outlet: accessToken, personal: personalAccessToken })
    if (bearer) headers['Authorization'] = `Bearer ${bearer}`
    if (tenant) headers['X-Company-Id'] = tenant.companyId
  } else if (tenant) {
    headers['X-Company-Id'] = tenant.companyId
    headers['X-Actor'] = tenant.actor
  } else if (actor) {
    headers['X-Actor'] = actor
  }
  // ADR 0049 P3b: the signed-in operator, when one exists — see operatorSessionToken's doc.
  if (operatorSessionToken) headers['X-Operator-Session'] = operatorSessionToken
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
    const prop = problem as Record<string, unknown> | null
    const traceId = typeof prop?.traceId === 'string' ? (prop.traceId as string) : null
    // The server sets `reference` on persisted 500s; fall back to the traceId when absent.
    const reference =
      typeof prop?.reference === 'string' ? (prop.reference as string) : traceId
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
      reference,
    )
  }
  return data as T
}

export async function apiFetch<T>(path: string, opts: RequestOptions = {}): Promise<T | null> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json'
  Object.assign(headers, authHeaders(opts.tenant, opts.actor, opts.auth))
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
  /** Which bearer to send in oidc mode — see {@link AuthTarget}. Defaults to `'outlet'`. */
  auth?: AuthTarget
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
  Object.assign(headers, authHeaders(opts.tenant, opts.actor, opts.auth))
  if (opts.headers) Object.assign(headers, opts.headers)

  const res = await fetch(API_BASE_URL + path, {
    method: 'POST',
    headers,
    body: formData,
  })

  return handleResponse<T>(res, 'POST', path, opts.tenant?.companyId)
}

/**
 * Fetches a non-JSON response (e.g. the expense-claim receipt photo, ADR 0030 Phase E6) as a raw
 * {@link Blob} — same auth/tenant headers and error path as {@link apiFetch} (a typed {@link
 * ApiError} with AI-native diagnostics on failure via the shared {@link handleResponse}), but
 * resolves the Blob directly on success instead of decoding JSON. Intended for a client-side
 * preview: the caller turns the Blob into an object URL (`URL.createObjectURL`) and MUST revoke it
 * (`URL.revokeObjectURL`) once done — this function does not manage that lifetime.
 */
export async function apiFetchBlob(path: string, opts: RequestOptions = {}): Promise<Blob | null> {
  const headers: Record<string, string> = {}
  Object.assign(headers, authHeaders(opts.tenant, opts.actor, opts.auth))
  if (opts.headers) Object.assign(headers, opts.headers)

  const res = await fetch(API_BASE_URL + path + buildQuery(opts.query), {
    method: opts.method ?? 'GET',
    headers,
  })

  if (!res.ok) {
    await handleResponse(res, opts.method ?? 'GET', path, opts.tenant?.companyId)
    return null
  }
  if (res.status === 204) return null
  return res.blob()
}

/**
 * Downloads a non-JSON response (e.g. the payroll bank-file CSV) as a browser file save, reusing
 * the same auth/tenant headers as {@link apiFetch}. On a non-2xx response it decodes the
 * `problem+json` body through the SAME {@link handleResponse} error path as every other call (a
 * typed {@link ApiError}, AI-native diagnostics, the 401 recovery hook) — the caller sees the
 * identical error shape whether the request was JSON or a file.
 *
 * The filename comes from the response's `Content-Disposition` header (the server names the file,
 * e.g. `payroll-2026-07-seq1.csv`); a fallback name is used only if the header is absent.
 */
export async function apiDownload(
  path: string,
  opts: RequestOptions = {},
  fallbackFilename = 'download',
): Promise<void> {
  const headers: Record<string, string> = {}
  Object.assign(headers, authHeaders(opts.tenant, opts.actor, opts.auth))
  if (opts.headers) Object.assign(headers, opts.headers)

  const res = await fetch(API_BASE_URL + path + buildQuery(opts.query), {
    method: opts.method ?? 'GET',
    headers,
  })

  if (!res.ok) {
    await handleResponse(res, opts.method ?? 'GET', path, opts.tenant?.companyId)
    return
  }

  const blob = await res.blob()
  const disposition = res.headers.get('Content-Disposition') ?? ''
  const match = /filename="?([^";]+)"?/i.exec(disposition)
  const filename = match?.[1] ?? fallbackFilename

  // Android shells ignore anchor downloads entirely — route through NativeShell.saveFile there
  // (FileSaveToast announces the outcome); the browser anchor path is unchanged otherwise.
  await deliverDownload(blob, filename)
}
