/**
 * bumpStore — local (single-screen) bump state for the Kitchen Display.
 *
 * Stores a map of { billId -> lineCountWhenBumped } in localStorage so
 * the state survives a page refresh on the same device.
 *
 * Re-appear rule: if a bumped bill's current lineCount later exceeds the
 * stored value (a new round was added by the cashier), `isBumped` returns
 * false and the ticket reappears on the KDS board.
 *
 * NOTE: This is intentionally a local, single-screen bump.  There is no
 * backend kitchen-status endpoint yet.  When one is added, this store can
 * be replaced with a server mutation + query without touching the UI.
 */

import { useSyncExternalStore, useCallback } from 'react'

const STORAGE_KEY = 'native.kds.bumped'
const EVENT = 'native:kds:bump'

/** Map of billId → lineCount at the time of the bump. */
export type BumpedMap = Record<string, number>

// ---------------------------------------------------------------------------
// Module-level state (single source of truth on this tab)
// ---------------------------------------------------------------------------

function readStorage(): BumpedMap {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as BumpedMap) : {}
  } catch {
    return {}
  }
}

function writeStorage(map: BumpedMap): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(map))
  } catch {
    /* storage unavailable — in-memory only */
  }
}

// In-memory cache (avoids repeated JSON.parse on every render).
let _cache: BumpedMap = readStorage()

function notifyListeners(): void {
  window.dispatchEvent(new CustomEvent(EVENT))
}

function subscribe(onChange: () => void): () => void {
  window.addEventListener(EVENT, onChange)
  // Also sync when storage changes in another tab.
  window.addEventListener('storage', onChange)
  return () => {
    window.removeEventListener(EVENT, onChange)
    window.removeEventListener('storage', onChange)
  }
}

function getSnapshot(): BumpedMap {
  return _cache
}

// ---------------------------------------------------------------------------
// Exported store actions
// ---------------------------------------------------------------------------

/** Mark a bill as bumped, recording the lineCount at the time of the bump. */
export function bumpBill(billId: string, lineCount: number): void {
  _cache = { ..._cache, [billId]: lineCount }
  writeStorage(_cache)
  notifyListeners()
}

/**
 * Returns true when a bill should be hidden from the active board.
 * A bumped bill re-appears if its current lineCount exceeds the stored value
 * (new items were added to an already-bumped ticket — a new round).
 */
export function isBillBumped(bumpedMap: BumpedMap, billId: string, currentLineCount: number): boolean {
  if (!(billId in bumpedMap)) return false
  const bumpedAt = bumpedMap[billId]
  // If more lines were added after bump, the ticket needs kitchen attention again.
  return currentLineCount <= bumpedAt
}

// ---------------------------------------------------------------------------
// React hook — components subscribe via this
// ---------------------------------------------------------------------------

export function useBumpStore() {
  const bumpedMap = useSyncExternalStore(subscribe, getSnapshot, () => ({} as BumpedMap))

  const bump = useCallback((billId: string, lineCount: number) => {
    bumpBill(billId, lineCount)
  }, [])

  const isBumped = useCallback(
    (billId: string, currentLineCount: number) => isBillBumped(bumpedMap, billId, currentLineCount),
    [bumpedMap],
  )

  return { bumpedMap, bump, isBumped }
}
