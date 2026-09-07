/**
 * scrollBehavior — where a navigation leaves the page (navigation contract, rule N4).
 *
 * The console had NO scroll management at all: `window.scrollTo` appeared exactly twice in the
 * whole app (the phone tab bar and the landing page's mount). So scrolling halfway down /invoices
 * and clicking /bills in the sidebar opened the new page at the OLD offset — landing you in the
 * middle of a table you had not read. That is a large part of the "the app jumps around" report.
 *
 * Why we take the browser's restoration over instead of leaning on it: this app renders routes
 * against a DEFERRED location (TransitionedRoutes) behind lazy chunks and async data, so at the
 * moment `popstate` fires the document still holds the OLD page and is usually the WRONG height.
 * The browser restores against that, clamps, and never corrects — measured as 1194px where 600px
 * was wanted (scripts/nav-smoke.mjs, phase-1 run). `scrollRestoration = 'manual'` therefore is not
 * us discarding something that worked; it is us taking over something that did not.
 *
 * Split deliberately: the DECISION is a pure function (unit-tested below in __tests__), the
 * application is the DOM-touching part, and the memory is a bounded map mirrored into
 * sessionStorage — NOT into `history.state`, which the back guard already owns (backGuardProtocol).
 */
export type ScrollAction = { kind: 'top' } | { kind: 'restore'; offset: number } | { kind: 'none' }

export type NavKind = 'PUSH' | 'POP' | 'REPLACE'

/**
 * react-router models the navigation type as a string ENUM (`Action.Push = "PUSH"`), which
 * TypeScript keeps nominal — the member is not assignable to the identical string literal. Convert
 * once here rather than importing the enum, so this module stays dependency-free and therefore
 * trivially unit-testable. Anything unrecognised reads as POP: the conservative branch, which
 * restores or goes to the top but never yanks a page the user is reading.
 */
export function navKindOf(navigationType: string): NavKind {
  return navigationType === 'PUSH' || navigationType === 'REPLACE' ? navigationType : 'POP'
}

/**
 * What a navigation should do to the scroll position.
 *
 * - A URL with a `hash` names its own anchor — never fight it.
 * - REPLACE is a redirect the user did not ask for (a guard bouncing them, a tab syncing its
 *   query): moving the page under them would read as a glitch, so it does nothing.
 * - POP is "go back to where I was", so it restores — falling back to the top when we have no
 *   memory of that entry (a reload, or an entry from before this session).
 * - PUSH is a new destination, which always starts at the top.
 */
export function scrollActionFor(params: {
  navigationType: NavKind
  hash: string
  saved: number | undefined
}): ScrollAction {
  const { navigationType, hash, saved } = params
  if (hash) return { kind: 'none' }
  if (navigationType === 'REPLACE') return { kind: 'none' }
  if (navigationType === 'POP') {
    return saved == null ? { kind: 'top' } : { kind: 'restore', offset: saved }
  }
  return { kind: 'top' }
}

// ---------------------------------------------------------------------------
// Memory — offset per react-router location key
// ---------------------------------------------------------------------------

const STORAGE_KEY = 'native.console.scroll'
/** Keep the map small: a long session must not grow an unbounded sessionStorage entry. */
const LIMIT = 40

/** Insertion-ordered, so the oldest key is simply the first one. */
const positions = new Map<string, number>()

function storage(): Storage | null {
  try {
    return typeof sessionStorage === 'undefined' ? null : sessionStorage
  } catch {
    return null // private mode / storage disabled — memory-only is a fine degradation
  }
}

/** Rehydrate after a reload. `location.key` survives a reload (react-router keeps it in
 *  history.state), so the entry we come back to can still be restored. */
export function loadScrollMemory(): void {
  try {
    const raw = storage()?.getItem(STORAGE_KEY)
    if (!raw) return
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return
    for (const entry of parsed) {
      if (Array.isArray(entry) && typeof entry[0] === 'string' && typeof entry[1] === 'number') {
        positions.set(entry[0], entry[1])
      }
    }
  } catch {
    // A malformed entry is not worth a crash — start with no memory.
  }
}

export function saveScrollMemory(): void {
  try {
    storage()?.setItem(STORAGE_KEY, JSON.stringify([...positions]))
  } catch {
    // Quota or disabled storage: the in-memory map still serves this session.
  }
}

export function rememberScroll(key: string, offset: number): void {
  // Re-set moves the key to the end, so LIMIT evicts the least recently LEFT page.
  positions.delete(key)
  positions.set(key, offset)
  while (positions.size > LIMIT) {
    const oldest = positions.keys().next().value
    if (oldest === undefined) break
    positions.delete(oldest)
  }
}

export function recallScroll(key: string): number | undefined {
  return positions.get(key)
}

/** Test seam — the module-level map is shared by every consumer. */
export function clearScrollMemory(): void {
  positions.clear()
}

// ---------------------------------------------------------------------------
// Application
// ---------------------------------------------------------------------------

/** How long a restore keeps re-applying while the destination is still filling in. */
const RESTORE_WINDOW_MS = 700

/** Cancels the in-flight restore when a second navigation overtakes the first. */
let restoreToken = 0

/**
 * Apply the action. A restore cannot be a single `scrollTo`: the destination is typically still a
 * Suspense skeleton, shorter than the offset we want, so the browser clamps and the page is left
 * stranded mid-way once the real content arrives. So we re-apply each frame until we land, the
 * user takes over, or the window closes.
 */
export function applyScrollAction(action: ScrollAction): void {
  restoreToken += 1
  if (action.kind === 'none') return

  const target = action.kind === 'top' ? 0 : action.offset
  window.scrollTo(0, target)
  // The top always exists, so there is nothing to wait for.
  if (target === 0) return

  const token = restoreToken
  const deadline = performance.now() + RESTORE_WINDOW_MS
  const userEvents = ['wheel', 'touchstart', 'keydown'] as const

  const stop = () => {
    restoreToken += 1 // invalidate our own token
    for (const type of userEvents) window.removeEventListener(type, stop)
  }
  // The user reaching for the page outranks our restore — never fight a live gesture.
  for (const type of userEvents) window.addEventListener(type, stop, { passive: true })

  const tick = () => {
    if (token !== restoreToken) return // superseded or stopped
    if (Math.abs(window.scrollY - target) <= 1) {
      stop()
      return
    }
    window.scrollTo(0, target)
    if (performance.now() >= deadline) {
      stop()
      return
    }
    requestAnimationFrame(tick)
  }
  requestAnimationFrame(tick)
}
