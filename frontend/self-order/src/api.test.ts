import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setToken, submitOrder, tokenTableLabel } from './api'

/** Base64url-encodes a UTF-8 JSON string, mirroring the (unsigned, opaque-to-this-client) payload
 * half of a real self-order token — see api.ts's `tokenTableLabel` doc. */
function encodeTokenPayload(payload: unknown): string {
  const json = JSON.stringify(payload)
  const b64 = Buffer.from(json, 'utf-8').toString('base64')
  return b64.replace(/\+/g, '-').replace(/\//g, '_')
}

function fakeToken(payload: unknown): string {
  return `${encodeTokenPayload(payload)}.signature`
}

describe('tokenTableLabel', () => {
  afterEach(() => {
    setToken(null)
  })

  it('decodes a plain ASCII label', () => {
    setToken(fakeToken({ tableLabel: 'Table 12' }))
    expect(tokenTableLabel()).toBe('Table 12')
  })

  it('decodes a non-ASCII label as UTF-8 (bug 5: atob alone mojibakes this)', () => {
    setToken(fakeToken({ tableLabel: 'Meja Kafé 3 — 東京' }))
    expect(tokenTableLabel()).toBe('Meja Kafé 3 — 東京')
  })

  it('decodes an emoji label (multi-byte UTF-8 beyond the BMP)', () => {
    setToken(fakeToken({ tableLabel: 'Meja 🍜' }))
    expect(tokenTableLabel()).toBe('Meja 🍜')
  })

  it('returns null when no token is set', () => {
    setToken(null)
    expect(tokenTableLabel()).toBeNull()
  })

  it('returns null for a kiosk token with no tableLabel field', () => {
    setToken(fakeToken({}))
    expect(tokenTableLabel()).toBeNull()
  })

  it('returns null (not a throw) for an unparseable token', () => {
    setToken('not-valid-base64!!!.signature')
    expect(tokenTableLabel()).toBeNull()
  })
})

describe('submitOrder — idempotency key', () => {
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    setToken(null)
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
    vi.restoreAllMocks()
  })

  it('sends exactly the idempotencyKey passed in — never mints its own (bug 2)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ orderId: 'order-1' }), {
        status: 201,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    globalThis.fetch = fetchMock as unknown as typeof fetch

    await submitOrder([{ menuItemId: 'item-1', qty: 2 }], 'fixed-key-abc')

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const body = JSON.parse(init.body as string) as { idempotencyKey: string; lines: unknown[] }
    expect(body.idempotencyKey).toBe('fixed-key-abc')
    expect(body.lines).toEqual([{ menuItemId: 'item-1', qty: 2 }])
  })

  it('a RETRY with the same key sends the identical key both times (never a fresh one per call)', async () => {
    const fetchMock = vi
      .fn()
      // First attempt: ambiguous network failure.
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      // Retry: succeeds.
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ orderId: 'order-2' }), {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        }),
      )
    globalThis.fetch = fetchMock as unknown as typeof fetch

    const lines = [{ menuItemId: 'item-9', qty: 1 }]
    await expect(submitOrder(lines, 'retry-key')).rejects.toThrow()
    const result = await submitOrder(lines, 'retry-key')

    expect(result.orderId).toBe('order-2')
    expect(fetchMock).toHaveBeenCalledTimes(2)
    const firstBody = JSON.parse((fetchMock.mock.calls[0]?.[1] as RequestInit).body as string) as {
      idempotencyKey: string
    }
    const secondBody = JSON.parse((fetchMock.mock.calls[1]?.[1] as RequestInit).body as string) as {
      idempotencyKey: string
    }
    expect(firstBody.idempotencyKey).toBe('retry-key')
    expect(secondBody.idempotencyKey).toBe('retry-key')
  })
})
