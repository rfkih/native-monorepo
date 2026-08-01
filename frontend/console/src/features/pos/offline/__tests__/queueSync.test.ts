import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Mocked BEFORE importing sync.ts (which imports '@/lib/api') — sync.ts's `err instanceof ApiError`
// checks the SAME mocked class this factory returns, since both resolve to one mocked module.
vi.mock('@/lib/api', () => {
  class ApiError extends Error {
    status: number
    problem: { type?: string; title?: string; detail?: string } | null
    constructor(status: number, problem: { type?: string; title?: string; detail?: string } | null, message: string) {
      super(message)
      this.name = 'ApiError'
      this.status = status
      this.problem = problem
    }
  }
  return { apiFetch: vi.fn(), ApiError }
})

import { apiFetch, ApiError } from '@/lib/api'
import { __resetDbForTests, type ProvisionalTotals } from '../db'
import { __resetQueueStoreForTests, enqueueSale, getQueueRow, updateQueueRow } from '../queue'
import { __resetAutoSyncForTests, replayQueue } from '../sync'

const provisional: ProvisionalTotals = {
  subtotalMinor: 100_000,
  discountMinor: 0,
  serviceChargeMinor: 5_000,
  taxMinor: 11_550,
  grandTotalMinor: 116_550,
  currency: 'IDR',
  usesCachedRules: true,
}

async function enqueueOne() {
  return enqueueSale({
    vertical: 'restaurant',
    endpoint: '/api/v1/orders',
    companyId: 'company-1',
    actor: 'cashier-1',
    body: {
      businessId: 'biz-1',
      lines: [{ menuItemId: 'item-1', qty: 1, selectedOptionIds: [] }],
      payment: { tenderType: 'CASH', tenderedMinor: 120_000 },
    },
    provisional,
  })
}

beforeEach(async () => {
  await __resetDbForTests()
  __resetQueueStoreForTests()
  __resetAutoSyncForTests()
  vi.mocked(apiFetch).mockReset()
  // vitest's `node` test environment has no `navigator` global by default — replayQueue then falls
  // back to its in-module boolean guard (same-tab only). The concurrency test below installs a
  // minimal navigator.locks shim explicitly.
  // @ts-expect-error test-only global shim
  delete globalThis.navigator
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('offline sale queue + replay state machine', () => {
  it('a 2xx reply with a MATCHING total -> SYNCED, server total recorded', async () => {
    const row = await enqueueOne()
    vi.mocked(apiFetch).mockResolvedValueOnce({
      breakdown: { grandTotalMinor: row.provisional.grandTotalMinor },
    })

    await replayQueue()

    const after = await getQueueRow(row.idempotencyKey)
    expect(after?.status).toBe('SYNCED')
    expect(after?.serverGrandTotalMinor).toBe(row.provisional.grandTotalMinor)
    expect(after?.lastError).toBeNull()
  })

  it('a 2xx reply with a DIFFERENT total -> SYNCED_WITH_MISMATCH, server total recorded', async () => {
    const row = await enqueueOne()
    const serverTotal = row.provisional.grandTotalMinor + 500
    vi.mocked(apiFetch).mockResolvedValueOnce({ breakdown: { grandTotalMinor: serverTotal } })

    await replayQueue()

    const after = await getQueueRow(row.idempotencyKey)
    expect(after?.status).toBe('SYNCED_WITH_MISMATCH')
    expect(after?.serverGrandTotalMinor).toBe(serverTotal)
  })

  it('409 (duplicate) -> SYNCED — the idempotent replay already exists server-side', async () => {
    const row = await enqueueOne()
    vi.mocked(apiFetch).mockRejectedValueOnce(
      new ApiError(409, { type: 'https://errors.nativeapp.id/duplicate-checkout' }, 'duplicate'),
    )

    await replayQueue()

    const after = await getQueueRow(row.idempotencyKey)
    expect(after?.status).toBe('SYNCED')
  })

  it('422 (other 4xx) -> REJECTED, row kept with the server error message', async () => {
    const row = await enqueueOne()
    vi.mocked(apiFetch).mockRejectedValueOnce(
      new ApiError(422, { detail: 'offline sales must be cash-only' }, 'unprocessable'),
    )

    await replayQueue()

    const after = await getQueueRow(row.idempotencyKey)
    expect(after?.status).toBe('REJECTED')
    expect(after?.lastError).toBe('offline sales must be cash-only')
    expect(after?.attempts).toBe(1)
  })

  it('a network failure -> stays QUEUED (retried next tick), attempts increments', async () => {
    const row = await enqueueOne()
    vi.mocked(apiFetch).mockRejectedValueOnce(new TypeError('Failed to fetch'))

    await replayQueue()

    const after = await getQueueRow(row.idempotencyKey)
    expect(after?.status).toBe('QUEUED')
    expect(after?.attempts).toBe(1)
  })

  it('401 -> stays QUEUED — the auth layer must renew the token, the sale is never dropped', async () => {
    const row = await enqueueOne()
    vi.mocked(apiFetch).mockRejectedValueOnce(new ApiError(401, null, 'unauthorized'))

    await replayQueue()

    const after = await getQueueRow(row.idempotencyKey)
    expect(after?.status).toBe('QUEUED')
    expect(after?.attempts).toBe(1)
  })

  it('a 5xx failure -> stays QUEUED (transient, retried next tick)', async () => {
    const row = await enqueueOne()
    vi.mocked(apiFetch).mockRejectedValueOnce(new ApiError(503, null, 'service unavailable'))

    await replayQueue()

    const after = await getQueueRow(row.idempotencyKey)
    expect(after?.status).toBe('QUEUED')
  })

  it('the idempotency key is IDENTICAL across retries (never regenerated)', async () => {
    const row = await enqueueOne()

    vi.mocked(apiFetch).mockRejectedValueOnce(new TypeError('offline'))
    await replayQueue()
    const afterFirst = await getQueueRow(row.idempotencyKey)
    expect(afterFirst?.status).toBe('QUEUED')
    expect((afterFirst?.body as { idempotencyKey: string }).idempotencyKey).toBe(row.idempotencyKey)

    vi.mocked(apiFetch).mockResolvedValueOnce({
      breakdown: { grandTotalMinor: row.provisional.grandTotalMinor },
    })
    await replayQueue()
    const afterSecond = await getQueueRow(row.idempotencyKey)
    expect(afterSecond?.status).toBe('SYNCED')
    expect((afterSecond?.body as { idempotencyKey: string }).idempotencyKey).toBe(row.idempotencyKey)

    expect(apiFetch).toHaveBeenCalledTimes(2)
    const calls = vi.mocked(apiFetch).mock.calls
    const firstBody = calls[0]?.[1]?.body as { idempotencyKey: string }
    const secondBody = calls[1]?.[1]?.body as { idempotencyKey: string }
    expect(firstBody.idempotencyKey).toBe(row.idempotencyKey)
    expect(secondBody.idempotencyKey).toBe(row.idempotencyKey)
  })

  it('a row left SYNCING from a prior crash is swept back to QUEUED and reaches SYNCED on the next replay', async () => {
    const row = await enqueueOne()
    // Simulate a crash/reload mid-POST on a PREVIOUS replay attempt: the row was moved to SYNCING
    // but nothing ever recorded the outcome (drainQueue's stale-SYNCING sweep is the only thing that
    // can recover it — neither the auto-sync triggers nor "Retry now" pick up anything but QUEUED).
    await updateQueueRow(row.idempotencyKey, { status: 'SYNCING' })
    const stuck = await getQueueRow(row.idempotencyKey)
    expect(stuck?.status).toBe('SYNCING')

    vi.mocked(apiFetch).mockResolvedValueOnce({
      breakdown: { grandTotalMinor: row.provisional.grandTotalMinor },
    })

    await replayQueue()

    const after = await getQueueRow(row.idempotencyKey)
    expect(after?.status).toBe('SYNCED')
    expect(apiFetch).toHaveBeenCalledTimes(1)
  })

  it('two concurrent replayQueue() invocations under a mocked Web Lock -> processed exactly once', async () => {
    // A minimal same-process stand-in for navigator.locks.request({ ifAvailable: true }, cb): only
    // one holder at a time; a second concurrent caller gets `lock: null` (matching the real API) and
    // its callback short-circuits without doing any work.
    let held = false
    const lockManagerShim = {
      locks: {
        async request(
          _name: string,
          _opts: unknown,
          callback: (lock: { name: string } | null) => Promise<void>,
        ) {
          if (held) return callback(null)
          held = true
          try {
            return await callback({ name: _name })
          } finally {
            held = false
          }
        },
      },
    }
    globalThis.navigator = lockManagerShim as unknown as Navigator

    const row = await enqueueOne()
    let callCount = 0
    vi.mocked(apiFetch).mockImplementation(async () => {
      callCount++
      // A slight delay makes the two replayQueue() calls genuinely overlap in time (not required
      // for correctness here — the lock is acquired synchronously before either call awaits network
      // — but it makes the "concurrent" intent of the test explicit).
      await new Promise((resolve) => setTimeout(resolve, 10))
      return { breakdown: { grandTotalMinor: row.provisional.grandTotalMinor } }
    })

    await Promise.all([replayQueue(), replayQueue()])

    expect(callCount).toBe(1)
    const after = await getQueueRow(row.idempotencyKey)
    expect(after?.status).toBe('SYNCED')
  })
})
