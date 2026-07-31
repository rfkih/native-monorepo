/**
 * db.ts — IndexedDB schema for offline POS selling (Phase 5, ADR 0028).
 *
 * Two stores:
 *   - `saleQueue`   — one row per queued/replayed offline sale, keyed by its idempotency key (the
 *     SAME key the server's checkout endpoint dedupes on — the queue IS the durable outbox for the
 *     browser). Never garbage-collected here; SyncCenter is the only UI that clears entries and it
 *     currently only ever reads/updates (a manual "clear" is a future add, out of this phase's scope).
 *   - `catalogCache` — a write-through TTL cache of the last-known-good menu/packages/addons/staff/
 *     effective-rules responses, keyed by `${companyId}:${vertical}:${kind}` so two outlets or two
 *     verticals never collide. See catalogCache.ts for the read/write helpers.
 *
 * Exactly-once semantics live server-side (idempotency-key uniqueness) — this module only has to
 * guarantee the row survives a reload/crash BEFORE the cashier is told the sale succeeded (queue.ts
 * awaits the `put` before returning) and is never silently dropped by browser storage pressure
 * (requestPersistentStorage below).
 */
import { openDB, deleteDB, type DBSchema, type IDBPDatabase } from 'idb'

export type SaleVertical = 'restaurant' | 'carwash' | 'barbershop'

export type SaleQueueStatus = 'QUEUED' | 'SYNCING' | 'SYNCED' | 'SYNCED_WITH_MISMATCH' | 'REJECTED'

/** The provisional price breakdown computed client-side at enqueue time (provisionalPricing.ts) —
 * always carries `usesCachedRules: true` so any UI rendering it can badge it as an estimate. */
export interface ProvisionalTotals {
  subtotalMinor: number
  discountMinor: number
  serviceChargeMinor: number
  taxMinor: number
  grandTotalMinor: number
  currency: string
  usesCachedRules: true
}

export interface SaleQueueRow {
  /** Primary key — minted once at enqueue, reused verbatim on every replay attempt (never
   * regenerated), matching the server's checkout idempotency-key contract. */
  idempotencyKey: string
  vertical: SaleVertical
  /** The vertical's checkout REST path, e.g. '/api/v1/orders' or '/api/v1/carwash/tickets/checkout'. */
  endpoint: string
  /** The exact JSON body to POST on replay — already carries idempotencyKey, offlineReplay: true,
   * and clientOccurredAt (queue.ts stamps these at enqueue time). Replayed VERBATIM, nothing added. */
  body: Record<string, unknown>
  companyId: string
  /** The tenant actor header (dev-mode header-trust; a no-op extra field in oidc mode, where the
   * bearer token alone carries identity — see lib/api.ts). */
  actor: string
  provisional: ProvisionalTotals
  enqueuedAt: string
  clientOccurredAt: string
  status: SaleQueueStatus
  attempts: number
  lastError: string | null
  /** Populated once a 2xx replay response resolves — used to detect a provisional/server mismatch. */
  serverGrandTotalMinor: number | null
}

export interface CatalogCacheRow {
  /** `${companyId}:${vertical}:${kind}` */
  key: string
  data: unknown
  cachedAt: number
}

interface NativeOfflineDbSchema extends DBSchema {
  saleQueue: {
    key: string
    value: SaleQueueRow
    indexes: { 'by-status': string }
  }
  catalogCache: {
    key: string
    value: CatalogCacheRow
  }
}

const DB_NAME = 'native-pos-offline'
const DB_VERSION = 1

let dbPromise: Promise<IDBPDatabase<NativeOfflineDbSchema>> | null = null

/** Lazily opens (and memoizes) the offline database. Safe to call repeatedly/concurrently. */
export function getDb(): Promise<IDBPDatabase<NativeOfflineDbSchema>> {
  if (!dbPromise) {
    dbPromise = openDB<NativeOfflineDbSchema>(DB_NAME, DB_VERSION, {
      upgrade(db) {
        if (!db.objectStoreNames.contains('saleQueue')) {
          const store = db.createObjectStore('saleQueue', { keyPath: 'idempotencyKey' })
          store.createIndex('by-status', 'status')
        }
        if (!db.objectStoreNames.contains('catalogCache')) {
          db.createObjectStore('catalogCache', { keyPath: 'key' })
        }
      },
    })
  }
  return dbPromise
}

/** Test-only: closes and DELETES the whole database (not just the memoized handle — IndexedDB is
 * durable storage, so a fresh `getDb()` on the same name would otherwise still see every previous
 * test's rows) so each test starts from a genuinely empty store. */
export async function __resetDbForTests(): Promise<void> {
  if (dbPromise) {
    const db = await dbPromise
    db.close()
  }
  dbPromise = null
  await deleteDB(DB_NAME)
}

let persistRequested = false

/**
 * Requests persistent storage so the browser's storage-pressure eviction heuristics never discard
 * queued offline sales. Called once, from queue.ts, the first time a sale is enqueued (not on every
 * app load — the browser's grant decision is effectively one-shot per origin). Best-effort: a denial
 * or an unsupported browser silently no-ops, matching every other offline affordance here (the
 * queue still works, just without the extra eviction protection).
 */
export async function requestPersistentStorage(): Promise<void> {
  if (persistRequested) return
  persistRequested = true
  try {
    if (typeof navigator !== 'undefined' && navigator.storage?.persist) {
      await navigator.storage.persist()
    }
  } catch {
    // best-effort only
  }
}
