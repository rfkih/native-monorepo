/**
 * api.ts — tiny fetch client for the self-order mini app: anonymous, token-scoped, no cookies, no
 * login. Every call sends the token read from the page's `?t=` URL param (see App.tsx) as
 * `X-Self-Order-Token` — the gateway forwards this header opaquely on its one anonymous business
 * route, `/api/v1/self-order/**` (restaurant-service, see docs/adr/0029). The token is kept in
 * memory ONLY for the lifetime of this tab; it is never written to storage.
 *
 * Wire shapes VERIFIED against the landed backend (restaurant-service `selforder/` feature):
 * the menu is a bare `MenuItemResponse[]` (categories are derived client-side from each item's
 * categoryId/category name — there is no separate categories endpoint on the anonymous surface);
 * the create body is `SelfOrderCreateRequest {idempotencyKey, lines}` (the key is minted here,
 * fresh per attempt); the create response is the full `OrderResponse` of which this app reads
 * only `orderId`. The table label shown on the confirmation screen comes from the TOKEN payload
 * (base64url JSON before the first dot — decodable by anyone, unforgeable without the secret).
 */

// Same-origin by default so the Vite dev proxy (vite.config.ts) handles /api/** in local dev;
// set VITE_GATEWAY_URL at build time for a deployment served from a different origin than the
// gateway.
const API_BASE_URL: string = import.meta.env.VITE_GATEWAY_URL ?? ''

export class SelfOrderApiError extends Error {
  status: number
  problemType: string | null
  constructor(status: number, problemType: string | null, message: string) {
    super(message)
    this.name = 'SelfOrderApiError'
    this.status = status
    this.problemType = problemType
  }
}

let token: string | null = null

export function setToken(next: string | null): void {
  token = next
}

/**
 * The table label carried in the token's (public) payload half — `null` for a kiosk token or an
 * unparseable token. Purely presentational: the SERVER takes the label from its own verified
 * access row, never from anything this client sends.
 */
export function tokenTableLabel(): string | null {
  if (!token) return null
  try {
    const payloadB64 = token.split('.')[0].replace(/-/g, '+').replace(/_/g, '/')
    const payload = JSON.parse(atob(payloadB64)) as { tableLabel?: string | null }
    return payload.tableLabel ?? null
  } catch {
    return null
  }
}

// ---------------------------------------------------------------------------
// Wire shapes — GET /api/v1/self-order/menu
// ---------------------------------------------------------------------------

export interface SelfOrderCategory {
  id: string
  name: string
  displayOrder: number
}

/** The fields of the backend's `MenuItemResponse` this app actually renders — no modifier
 * selection UI here (out of scope for the 2-screen mini app), so `modifierGroups` is intentionally
 * not read even though the backend includes it. `category` is the display name paired with
 * `categoryId` — categories are derived from the items (no separate endpoint). */
export interface SelfOrderMenuItem {
  id: string
  name: string
  category: string | null
  categoryId: string | null
  priceMinor: number
  currency: string
  available: boolean
  stockQuantity: number | null
}

export async function fetchMenu(): Promise<SelfOrderMenuItem[]> {
  const res = await apiFetch<SelfOrderMenuItem[]>('/api/v1/self-order/menu')
  return res ?? []
}

/** Categories derived client-side from the flat item list, in first-appearance order. */
export function deriveCategories(items: SelfOrderMenuItem[]): SelfOrderCategory[] {
  const seen = new Map<string, SelfOrderCategory>()
  let order = 0
  for (const item of items) {
    const id = item.categoryId ?? 'uncategorized'
    if (!seen.has(id)) {
      seen.set(id, { id, name: item.category ?? '', displayOrder: order++ })
    }
  }
  return [...seen.values()]
}

// ---------------------------------------------------------------------------
// Wire shapes — POST /api/v1/self-order/orders
// ---------------------------------------------------------------------------

export interface SelfOrderLineInput {
  menuItemId: string
  qty: number
}

/** The one field this app reads from the backend's full `OrderResponse`. */
export interface SelfOrderCreateResponse {
  orderId: string
}

export async function submitOrder(lines: SelfOrderLineInput[]): Promise<SelfOrderCreateResponse> {
  // Fresh idempotency key per attempt (SelfOrderCreateRequest requires it); a RETRY of the same
  // failed submit reuses the same in-flight promise upstream, so per-call minting is correct here.
  const res = await apiFetch<SelfOrderCreateResponse>('/api/v1/self-order/orders', {
    method: 'POST',
    body: { idempotencyKey: crypto.randomUUID(), lines },
  })
  if (!res) throw new SelfOrderApiError(502, null, 'Empty response')
  return res
}

// ---------------------------------------------------------------------------
// Fetch plumbing
// ---------------------------------------------------------------------------

interface RequestOptions {
  method?: 'GET' | 'POST'
  body?: unknown
}

interface ProblemDetail {
  type?: string
  title?: string
  detail?: string
}

async function apiFetch<T>(path: string, opts: RequestOptions = {}): Promise<T | null> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers['X-Self-Order-Token'] = token

  let res: Response
  try {
    res = await fetch(API_BASE_URL + path, {
      method: opts.method ?? 'GET',
      headers,
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    })
  } catch (err) {
    throw new SelfOrderApiError(0, null, err instanceof Error ? err.message : String(err))
  }

  if (res.status === 204) return null
  const text = await res.text()
  const data: unknown = text ? JSON.parse(text) : null

  if (!res.ok) {
    const problem = data as ProblemDetail | null
    throw new SelfOrderApiError(
      res.status,
      problem?.type ?? null,
      problem?.detail || problem?.title || `Request failed (${res.status})`,
    )
  }
  return data as T
}
