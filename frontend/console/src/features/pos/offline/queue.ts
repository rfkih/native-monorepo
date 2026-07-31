/**
 * queue.ts — the durable offline sale outbox: enqueue, list/update, and a synchronous snapshot for
 * React (useSyncExternalStore, consumed by useOffline.ts / OfflineBanner / SyncCenter).
 *
 * Exactly-once is the SERVER's job (idempotency-key uniqueness) — this module's only correctness
 * obligation is: (1) never show the cashier a success state before the row is durably persisted,
 * (2) never mutate `idempotencyKey` or `body.idempotencyKey` once minted (every replay of the same
 * row must be byte-identical on that field), and (3) keep every state transition visible to
 * subscribers so the UI never shows a stale queue.
 */
import {
  getDb,
  requestPersistentStorage,
  type ProvisionalTotals,
  type SaleQueueRow,
  type SaleQueueStatus,
  type SaleVertical,
} from './db'

export interface EnqueueSaleInput {
  vertical: SaleVertical
  /** The vertical's checkout REST path, e.g. '/api/v1/orders'. */
  endpoint: string
  companyId: string
  actor: string
  /** The checkout request body EXCLUDING idempotencyKey/offlineReplay/clientOccurredAt — this
   * function stamps those three fields itself so they are minted exactly once, here, and never
   * touched again by a replay. */
  body: Record<string, unknown>
  provisional: ProvisionalTotals
}

/**
 * Mints a fresh idempotency key + clientOccurredAt, PERSISTS the row, THEN returns it — callers
 * must await this before showing any success/provisional-receipt UI, so a sale is never displayed
 * as "sold" without first being durably queued (a crash/reload between the two would otherwise lose
 * a cash sale the cashier believes went through).
 */
export async function enqueueSale(input: EnqueueSaleInput): Promise<SaleQueueRow> {
  const idempotencyKey = crypto.randomUUID()
  const nowIso = new Date().toISOString()
  const row: SaleQueueRow = {
    idempotencyKey,
    vertical: input.vertical,
    endpoint: input.endpoint,
    body: {
      ...input.body,
      idempotencyKey,
      offlineReplay: true,
      clientOccurredAt: nowIso,
    },
    companyId: input.companyId,
    actor: input.actor,
    provisional: input.provisional,
    enqueuedAt: nowIso,
    clientOccurredAt: nowIso,
    status: 'QUEUED',
    attempts: 0,
    lastError: null,
    serverGrandTotalMinor: null,
  }

  await requestPersistentStorage()
  const db = await getDb()
  await db.put('saleQueue', row)
  await refreshSnapshot()
  return row
}

/** Fresh read of every queued row (any status), oldest-enqueued first — the FIFO replay order. */
export async function listQueue(): Promise<SaleQueueRow[]> {
  const db = await getDb()
  const rows = await db.getAll('saleQueue')
  return rows.sort((a, b) => a.enqueuedAt.localeCompare(b.enqueuedAt))
}

export async function getQueueRow(idempotencyKey: string): Promise<SaleQueueRow | undefined> {
  const db = await getDb()
  return db.get('saleQueue', idempotencyKey)
}

/** Partial update by idempotency key. No-ops silently if the row is gone (never expected in
 * practice — rows are never deleted in this phase). */
export async function updateQueueRow(
  idempotencyKey: string,
  patch: Partial<Omit<SaleQueueRow, 'idempotencyKey' | 'body'>>,
): Promise<void> {
  const db = await getDb()
  const existing = await db.get('saleQueue', idempotencyKey)
  if (!existing) return
  await db.put('saleQueue', { ...existing, ...patch })
  await refreshSnapshot()
}

export async function countByStatus(): Promise<Record<SaleQueueStatus, number>> {
  const rows = await listQueue()
  const counts: Record<SaleQueueStatus, number> = {
    QUEUED: 0,
    SYNCING: 0,
    SYNCED: 0,
    SYNCED_WITH_MISMATCH: 0,
    REJECTED: 0,
  }
  for (const row of rows) counts[row.status]++
  return counts
}

// ---------------------------------------------------------------------------
// React subscription — useSyncExternalStore needs a SYNCHRONOUS snapshot, but IndexedDB reads are
// async, so we keep an in-memory mirror that every mutation (and the initial load) refreshes from
// the DB, then notifies subscribers. Consumers: useOffline.ts, SyncCenter.tsx.
// ---------------------------------------------------------------------------

type Listener = () => void
const listeners = new Set<Listener>()
let snapshot: SaleQueueRow[] = []
let initialized = false

async function refreshSnapshot(): Promise<void> {
  snapshot = await listQueue()
  for (const listener of listeners) listener()
}

function ensureInitialized(): void {
  if (initialized) return
  initialized = true
  void refreshSnapshot()
}

/** The current in-memory snapshot — safe to call from a React render (no I/O). */
export function getSnapshot(): SaleQueueRow[] {
  ensureInitialized()
  return snapshot
}

/** Subscribes to queue changes; returns an unsubscribe function. For useSyncExternalStore. */
export function subscribe(listener: Listener): () => void {
  ensureInitialized()
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** Test-only: forces the in-memory snapshot back to empty/uninitialized between test cases. */
export function __resetQueueStoreForTests(): void {
  listeners.clear()
  snapshot = []
  initialized = false
}
