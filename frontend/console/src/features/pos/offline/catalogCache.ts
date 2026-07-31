/**
 * catalogCache.ts — a write-through, TTL-stamped cache of the last-known-good catalog/pricing reads
 * (menu+options, packages/addons/staff, effective-rules) so the POS can keep selling with sane
 * (if stale-labelled) prices when the network drops mid-shift.
 *
 * Callers:
 *  - `stashCatalog` — fired from each vertical's successful online queries (features/pos/api.ts /
 *    features/servicepos/api.ts callers), fire-and-forget, best-effort (never blocks or throws into
 *    the caller's render path).
 *  - `readCatalog` — read by the POS screens when `useOffline().offline` is true, to source the cart
 *    UI + provisionalPricing inputs instead of the (failing) live queries.
 */
import { useEffect, useState } from 'react'
import { getDb, type CatalogCacheRow } from './db'

export type CatalogKind =
  | 'menu'
  | 'menuCategories'
  | 'packages'
  | 'addons'
  | 'staffProfiles'
  | 'effectiveRules'

const DEFAULT_TTL_MS = 24 * 60 * 60 * 1000 // 24h

function cacheKey(companyId: string, vertical: string, kind: CatalogKind): string {
  return `${companyId}:${vertical}:${kind}`
}

/** Write-through: stash the latest successful read. Best-effort — swallows storage errors. */
export async function stashCatalog<T>(
  companyId: string,
  vertical: string,
  kind: CatalogKind,
  data: T,
): Promise<void> {
  try {
    const db = await getDb()
    const row: CatalogCacheRow = { key: cacheKey(companyId, vertical, kind), data, cachedAt: Date.now() }
    await db.put('catalogCache', row)
  } catch {
    // best-effort only — the offline cache is a convenience, never a hard dependency of the online path
  }
}

export interface CachedCatalog<T> {
  data: T
  /** True once the cached entry is older than the TTL — callers still use it (better than nothing
   * offline) but should badge it as potentially out of date. */
  stale: boolean
}

/** Reads the last stashed value for this (companyId, vertical, kind), or null if never cached. */
export async function readCatalog<T>(
  companyId: string,
  vertical: string,
  kind: CatalogKind,
  ttlMs: number = DEFAULT_TTL_MS,
): Promise<CachedCatalog<T> | null> {
  try {
    const db = await getDb()
    const row = await db.get('catalogCache', cacheKey(companyId, vertical, kind))
    if (!row) return null
    return { data: row.data as T, stale: Date.now() - row.cachedAt > ttlMs }
  } catch {
    return null
  }
}

/**
 * React fallback for a POS screen's catalog state: when `active` (typically `offline && the live
 * query has no data`), loads the last cached value for this key and returns it; null otherwise or
 * while loading. Used the way `menuQuery.data ?? cachedFallback ?? []` reads — the live query's own
 * (possibly stale-but-present) data always wins when it exists, since a query that succeeded at
 * least once this session already IS the freshest available data.
 */
export function useCachedCatalogFallback<T>(
  companyId: string | undefined,
  vertical: string,
  kind: CatalogKind,
  active: boolean,
): T | null {
  const [data, setData] = useState<T | null>(null)

  useEffect(() => {
    if (!active || !companyId) return
    let cancelled = false
    void readCatalog<T>(companyId, vertical, kind).then((cached) => {
      if (!cancelled) setData(cached?.data ?? null)
    })
    return () => {
      cancelled = true
    }
  }, [active, companyId, vertical, kind])

  // Gate the RETURN (rather than resetting state synchronously in the effect above, which would
  // trip react-hooks/set-state-in-effect) — any stale `data` from a previous active period is
  // simply never surfaced once `active` goes false.
  return active ? data : null
}
