/**
 * backGuardProtocol — the shared History-API contract between the two back-button features:
 *
 *   • useBackDismiss (per overlay) parks an entry `{ nativeBackDismiss: true }` so hardware Back
 *     closes the overlay instead of navigating.
 *   • useBackGuard (one per app) parks a sentinel `{ ...routerState, backGuard: true }` above
 *     EVERY route entry so hardware Back is intercepted and confirmed before it can navigate.
 *
 * The two markers are disjoint by construction: the guard sentinel spreads only the ROUTER entry's
 * state (usr/key/idx), never an overlay's, and an overlay entry never carries `backGuard`. Both
 * hooks listen to the same `popstate` stream and use these classifiers to decide whose pop it was.
 *
 * Pure module (no React) so the whole protocol is unit-testable.
 */
import { isNativeShell } from '@/lib/escpos/transport'

/** State shape the guard parks. Spreads the underlying router state so react-router's `idx`
 *  (root detection) and `usr`/`key` (location identity) survive a pop landing on the sentinel. */
export function isGuardState(state: unknown): boolean {
  return (state as { backGuard?: unknown } | null)?.backGuard === true
}

/** State shape useBackDismiss parks (unchanged since the MobileSheet original). */
export function isOverlayState(state: unknown): boolean {
  return (state as { nativeBackDismiss?: unknown } | null)?.nativeBackDismiss === true
}

/** Either marker — the guard must never park a second protocol entry on top of one. */
export function isProtocolState(state: unknown): boolean {
  return isGuardState(state) || isOverlayState(state)
}

// ---------------------------------------------------------------------------
// Overlay registry — which overlays currently OWN a parked entry, in stacking order.
// ---------------------------------------------------------------------------
// Two consumers:
//   • useBackDismiss: only the TOPMOST parked overlay may react to a pop — without this, two
//     stacked sheets would both close on one Back (every instance hears every popstate).
//   • useBackGuard: a pop landing on an overlay-marked entry with an EMPTY registry means the
//     entry is stale (its overlay closed via a click that also navigated, leaving it buried) —
//     the guard skims past it so Back presses are never silently swallowed.

const overlayStack: symbol[] = []

/** Called when an overlay parks its entry; returns handle used on popstate + cleanup. */
export function registerOverlay(): { isTop: () => boolean; deregister: () => void } {
  const token = Symbol('overlay')
  overlayStack.push(token)
  return {
    isTop: () => overlayStack[overlayStack.length - 1] === token,
    deregister: () => {
      const at = overlayStack.indexOf(token)
      if (at >= 0) overlayStack.splice(at, 1)
    },
  }
}

export function openOverlayCount(): number {
  return overlayStack.length
}

// ---------------------------------------------------------------------------
// Overlay self-pop consumption — shared across ALL useBackDismiss instances.
// ---------------------------------------------------------------------------
// When an overlay closes via its UI, its cleanup unwinds the parked entry with history.back().
// That popstate reaches every OTHER open overlay's handler too — and by then the unwinder has
// already deregistered, so the overlay below is "top" and would wrongly treat the unwind as a
// user Back and close itself (one Cancel click collapsing a whole stack of sheets). A per-instance
// flag can't fix this (the instance that set it removed its listener before back()), so the
// pending-unwind count lives here: the first handler to see the event consumes one pending pop,
// and every later handler for the SAME event reads it as already-consumed.

let pendingOverlaySelfPops = 0
let overlaySelfPopConsumedFor: unknown = null

/** Call immediately before the unwinding history.back() in useBackDismiss cleanup. */
export function beginOverlaySelfPop(): void {
  pendingOverlaySelfPops += 1
}

/** True when this popstate event is an overlay's own unwind — ignore it. Idempotent per event. */
export function consumeOverlaySelfPop(event: unknown): boolean {
  if (overlaySelfPopConsumedFor === event) return true
  if (pendingOverlaySelfPops > 0) {
    pendingOverlaySelfPops -= 1
    overlaySelfPopConsumedFor = event
    return true
  }
  return false
}

// ---------------------------------------------------------------------------
// Guard policy
// ---------------------------------------------------------------------------

/**
 * Routes the guard leaves alone. The customer display is an unattended second screen (no operator
 * to answer a dialog) and onboarding drives its own linear flow. Public/unauthenticated routes are
 * excluded by the mount point (BackGuard only renders in the authenticated tree), not here.
 */
export function isGuardablePath(pathname: string): boolean {
  if (pathname.startsWith('/pos/customer-display')) return false
  if (pathname.startsWith('/onboarding')) return false
  return true
}

/**
 * True when Back from here means "leave the app", not "previous page": the app's home, or the
 * first in-app history entry (react-router seeds `idx: 0` on the entry it booted on — anything at
 * or below that is the cross-document IdP page, which an authenticated session would only bounce
 * off; see the same guard in the Kotlin shells' MainActivity).
 */
export function isAtRoot(pathname: string, state: unknown, homePath: string): boolean {
  if (pathname === homePath) return true
  const idx = (state as { idx?: unknown } | null)?.idx
  return typeof idx !== 'number' || idx <= 0
}

/**
 * Deliberate in-app back (ScreenHeader arrow etc.) — must not trigger the confirm. While guarded,
 * a sentinel sits on top of the current route entry, so "previous page" is one atomic `go(-2)`;
 * the pop lands on the previous route's own sentinel, which the guard ignores.
 */
export function guardedNavigateBack(navigate: (delta: number) => void): void {
  navigate(isGuardState(window.history.state) ? -2 : -1)
}

// ---------------------------------------------------------------------------
// Activation
// ---------------------------------------------------------------------------

const DEV_FORCE_KEY = 'backGuardDev'

/**
 * Dev override: open any page with `?backGuardDev=1` to exercise the guard in a plain browser
 * (the browser Back button drives the exact popstate path the Android WebView produces). Sticky
 * for the tab via sessionStorage so in-app navigation doesn't lose it.
 */
export function isBackGuardForced(): boolean {
  try {
    if (typeof window === 'undefined') return false
    if (new URLSearchParams(window.location.search).has(DEV_FORCE_KEY)) {
      window.sessionStorage.setItem(DEV_FORCE_KEY, '1')
      return true
    }
    return window.sessionStorage.getItem(DEV_FORCE_KEY) === '1'
  } catch {
    return false
  }
}

/** The guard runs only inside the Android shells (plus the dev override) — browser Back stays native. */
export function isBackGuardEnabled(): boolean {
  return isNativeShell() || isBackGuardForced()
}
