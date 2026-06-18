/**
 * Tiny typed API client for the Native gateway. In dev, Vite proxies `/api/**` to the gateway;
 * in prod the console is served behind that same gateway, so the relative path is identical.
 *
 * Tenancy is carried in headers — the gateway's stand-in `X-Company-Id` / `X-Actor` (in production
 * the gateway injects these from the validated JWT). The browser never picks the tenant from a body
 * or query (rule 5); the console sends the company it is acting as.
 */

const BASE = (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? ''

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

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  /** Tenant-scoped call: sends X-Company-Id + X-Actor. */
  tenant?: { companyId: string; actor: string }
  /** Bootstrap call (e.g. create-company): sends X-Actor only. */
  actor?: string
  query?: Record<string, string | undefined>
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
  if (opts.tenant) {
    headers['X-Company-Id'] = opts.tenant.companyId
    headers['X-Actor'] = opts.tenant.actor
  } else if (opts.actor) {
    headers['X-Actor'] = opts.actor
  }

  const res = await fetch(BASE + path + buildQuery(opts.query), {
    method: opts.method ?? 'GET',
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  })

  if (res.status === 204) return null
  const text = await res.text()
  const data: unknown = text ? JSON.parse(text) : null

  if (!res.ok) {
    const problem = (data as ProblemDetail | null) ?? null
    throw new ApiError(
      res.status,
      problem,
      problem?.detail || problem?.title || `Request failed (${res.status})`,
    )
  }
  return data as T
}
