/**
 * sync.ts — the serial FIFO replay engine. Triggered by the `online` event, a 30s interval, and a
 * manual "retry now" (SyncCenter). Guarded by the Web Locks API so two tabs (or two overlapping
 * triggers in the SAME tab) never replay concurrently — the lock is `ifAvailable`, so a call that
 * finds the lock already held simply skips (the holder will drain the queue; no work is lost or
 * duplicated, since the queue itself is the durable state, not the caller's stack).
 *
 * Exactly-once = the server's idempotency-key uniqueness (rule: "replay is just a retry" — this
 * module never tries to reason about duplicates itself, it only maps HTTP outcomes to queue states).
 *
 * Crash recovery: every drain first sweeps any row left `SYNCING` back to `QUEUED` (see
 * sweepStaleSyncing()) — a row can only be in that state because an earlier replay was interrupted
 * (tab crash/reload) before it could record the POST's outcome, so it would otherwise be invisible
 * to every trigger above (all of which only pick up `QUEUED`) and orphaned forever.
 */
import { apiFetch, ApiError } from '@/lib/api'
import { getQueueRow, listQueue, updateQueueRow } from './queue'
import type { SaleQueueRow } from './db'

const LOCK_NAME = 'native-pos-replay'
const AUTO_SYNC_INTERVAL_MS = 30_000

let autoSyncStarted = false

/**
 * Wires the automatic replay triggers (online event + 30s interval). Idempotent — safe to call from
 * every mounted consumer (useOffline.ts); only the FIRST call installs the listeners/interval.
 */
export function startAutoSync(): void {
  if (autoSyncStarted) return
  autoSyncStarted = true

  if (typeof window !== 'undefined') {
    window.addEventListener('online', () => {
      void replayQueue()
    })
    window.setInterval(() => {
      if (navigator.onLine) void replayQueue()
    }, AUTO_SYNC_INTERVAL_MS)
    // The app may have loaded already online with a queue left over from a previous offline spell.
    if (navigator.onLine) void replayQueue()
  }
}

/** Test-only: allows a fresh `startAutoSync()` call to re-install listeners in the next test. */
export function __resetAutoSyncForTests(): void {
  autoSyncStarted = false
}

/** Manual "retry now" (SyncCenter) — the same entrypoint the automatic triggers use. */
export function retryNow(): Promise<void> {
  return replayQueue()
}

// Fallback guard for environments without navigator.locks (older browsers) — same-tab safety only.
let inFlightFallback = false

export async function replayQueue(): Promise<void> {
  if (typeof navigator !== 'undefined' && navigator.locks?.request) {
    await navigator.locks.request(LOCK_NAME, { ifAvailable: true }, async (lock) => {
      // Another tab/call already holds the lock — it will drain the queue; skip, don't wait.
      if (!lock) return
      await drainQueue()
    })
    return
  }
  if (inFlightFallback) return
  inFlightFallback = true
  try {
    await drainQueue()
  } finally {
    inFlightFallback = false
  }
}

async function drainQueue(): Promise<void> {
  // A row can be left in SYNCING if a PRIOR replay was interrupted mid-flight (tab crash, reload,
  // browser kill) between "set SYNCING" and the POST settling — nothing else ever moves a row off
  // SYNCING, so an orphaned one would otherwise sit forever, invisible to both the automatic
  // triggers (they only pick up QUEUED) and "Retry now" (same filter). This sweep runs at the very
  // start of every drain, which only ever executes while this call holds the replay lock (or the
  // no-locks fallback's in-flight guard) — so a row still SYNCING here is guaranteed to be leftover
  // from a run that never got the chance to record its outcome, never one this same call is mid-way
  // through. Re-queuing it is safe: replaying it again either completes normally or, if the earlier
  // attempt actually reached the server, resolves via the existing 409 -> SYNCED path.
  await sweepStaleSyncing()

  const rows = await listQueue()
  const queued = rows.filter((row) => row.status === 'QUEUED')
  for (const row of queued) {
    // Re-read the row immediately before each replay — a concurrent caller (should be impossible
    // under the lock, but defensive against the no-locks fallback path, and against a manual
    // "retry now" racing the auto-sync between the listQueue() snapshot above and the lock
    // acquisition) may have already moved it off QUEUED. Replaying the stale snapshot value would
    // double-send; skip anything no longer QUEUED by the time we get to it.
    const fresh = await getQueueRow(row.idempotencyKey)
    if (!fresh || fresh.status !== 'QUEUED') continue
    await replayOne(fresh)
  }
}

/** Resets any row still `SYNCING` back to `QUEUED` — see the call site's comment in drainQueue(). */
async function sweepStaleSyncing(): Promise<void> {
  const rows = await listQueue()
  const stuck = rows.filter((row) => row.status === 'SYNCING')
  for (const row of stuck) {
    await updateQueueRow(row.idempotencyKey, { status: 'QUEUED' })
  }
}

async function replayOne(row: SaleQueueRow): Promise<void> {
  await updateQueueRow(row.idempotencyKey, { status: 'SYNCING' })
  try {
    const res = await apiFetch<Record<string, unknown>>(row.endpoint, {
      method: 'POST',
      tenant: { companyId: row.companyId, actor: row.actor },
      body: row.body,
    })
    const serverGrandTotalMinor = extractGrandTotal(res)
    const mismatch =
      serverGrandTotalMinor != null && serverGrandTotalMinor !== row.provisional.grandTotalMinor
    await updateQueueRow(row.idempotencyKey, {
      status: mismatch ? 'SYNCED_WITH_MISMATCH' : 'SYNCED',
      serverGrandTotalMinor: serverGrandTotalMinor ?? null,
      lastError: null,
    })
  } catch (err) {
    await handleReplayError(row, err)
  }
}

async function handleReplayError(row: SaleQueueRow, err: unknown): Promise<void> {
  if (err instanceof ApiError) {
    // 409 — the checkout already committed under this idempotency key on a previous attempt whose
    // response was lost. The sale exists server-side: treat the replay as successful.
    if (err.status === 409) {
      await updateQueueRow(row.idempotencyKey, { status: 'SYNCED', lastError: null })
      return
    }
    // 401 — the bearer token needs the auth layer to renew it; never drop the row, just retry later.
    if (err.status === 401) {
      await updateQueueRow(row.idempotencyKey, {
        status: 'QUEUED',
        attempts: row.attempts + 1,
        lastError: err.problem?.detail ?? err.problem?.title ?? err.message,
      })
      return
    }
    // Any other 4xx — the server durably rejects this exact request (validation, entitlement,
    // outlet assignment, ...). Retrying verbatim would never succeed: keep the row visible for
    // manual review instead of retrying forever.
    if (err.status >= 400 && err.status < 500) {
      await updateQueueRow(row.idempotencyKey, {
        status: 'REJECTED',
        attempts: row.attempts + 1,
        lastError: err.problem?.detail ?? err.problem?.title ?? err.message,
      })
      return
    }
    // 5xx — transient server failure; retry next tick.
    await updateQueueRow(row.idempotencyKey, {
      status: 'QUEUED',
      attempts: row.attempts + 1,
      lastError: err.problem?.detail ?? err.problem?.title ?? err.message,
    })
    return
  }
  // Network failure (fetch threw before a response arrived) — retry next tick.
  await updateQueueRow(row.idempotencyKey, {
    status: 'QUEUED',
    attempts: row.attempts + 1,
    lastError: err instanceof Error ? err.message : String(err),
  })
}

/** Pulls the authoritative grand total out of a checkout response, whichever vertical shape it is:
 * restaurant's OrderResponse nests `breakdown.grandTotalMinor` (falling back to `totalMinor`); the
 * service-POS TicketResponse only ever has `breakdown.grandTotalMinor`. */
function extractGrandTotal(res: unknown): number | null {
  if (!res || typeof res !== 'object') return null
  const r = res as Record<string, unknown>
  const breakdown = r.breakdown as Record<string, unknown> | null | undefined
  if (breakdown && typeof breakdown.grandTotalMinor === 'number') return breakdown.grandTotalMinor
  if (typeof r.totalMinor === 'number') return r.totalMinor
  return null
}
