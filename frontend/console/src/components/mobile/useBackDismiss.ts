/**
 * useBackDismiss — make the phone/browser BACK button dismiss an open overlay instead of
 * navigating away.
 *
 * On the native shells (ADR 0043 Till / ADR 0049 Employee) the WebView is a thin client loading
 * the live console origin, and the console ships NO @capacitor/app plugin — so there is no native
 * `backButton` listener to hook. Capacitor's default hardware-back therefore calls
 * `webView.goBack()`, which fires a `popstate`. So the single interception that works in BOTH the
 * Android WebView and a plain browser/PWA is the History API: while the overlay is open we park one
 * throwaway history entry on the stack; the next Back press pops it → `popstate` → `onClose()`,
 * and the router never navigates. Closing via the UI (button, backdrop, Escape) unwinds that entry
 * so the stack stays balanced and a later Back isn't silently swallowed.
 *
 * Router-safe: the parked entry keeps the SAME URL (no path/search change), and react-router's
 * `BrowserRouter` is only notified on `popstate` — when Back returns to the previous (real, router-
 * owned) entry it reads that entry's valid state, so no phantom navigation is triggered on the way
 * in or out. Stacked overlays each park their own entry and pop LIFO: every instance hears every
 * popstate, so only the TOPMOST registered overlay (backGuardProtocol registry) may treat it as its
 * own — without that check one Back would close the whole stack at once.
 *
 * The teardown unwind is DEFERRED one tick and adoptable: `history.back()` is asynchronous and the
 * browser resolves the traversal against the entry that was current when it was CALLED — so the
 * unmount→remount of React StrictMode (unwind, then immediately re-park) used to race the pending
 * traversal and strand the overlay's entry in forward history (verified in Chrome). Instead the
 * cleanup schedules the unwind via setTimeout(0); a mount that follows within the same tick cancels
 * the timer and adopts the already-parked entry, so no traversal is ever in flight while parking.
 *
 * Composes with useBackGuard (route-level confirm): the guard's sentinels sit BELOW overlay
 * entries and the guard ignores any pop that lands on a protocol-marked entry — see
 * backGuardProtocol.ts for the contract. The registry also lets the guard detect a buried stale
 * entry (closing click that also navigated) and skim past it.
 *
 * `enabled=false` parks nothing (and unwinds if currently parked): used by overlays that must not
 * be dismissable mid-operation (e.g. a payment in flight) — Back then falls through to the route
 * guard's confirm dialog, which is safe.
 */
import { useEffect, useRef } from 'react'
import {
  beginOverlaySelfPop,
  consumeOverlaySelfPop,
  isOverlayState,
  registerOverlay,
} from './backGuardProtocol'

// Unwinds scheduled by closing overlays, adoptable by an overlay mounting in the same tick
// (StrictMode's setup→cleanup→setup, or a genuine close→reopen). Module-level: the closing and
// the adopting instance are different hook instances.
const pendingUnwindTimers: number[] = []

export function useBackDismiss(onClose: () => void, enabled = true) {
  // Keep the latest onClose without re-running the park effect (which would re-park an entry each
  // render). Synced in its own effect — writing a ref during render is disallowed (react-hooks/refs)
  // and unnecessary here, since onCloseRef is only read later in the popstate/teardown handlers.
  const onCloseRef = useRef(onClose)
  useEffect(() => {
    onCloseRef.current = onClose
  }, [onClose])

  useEffect(() => {
    if (!enabled) return
    if (typeof window === 'undefined' || !window.history) return
    let dismissedByBack = false

    // Registered exactly while our entry is parked — the registry mirrors the stack of live
    // overlay-owned entries (mount order == park order == stacking order).
    const registration = registerOverlay()

    // Adopt a just-closed overlay's parked entry when one is pending instead of pushing a second
    // one over an in-flight unwind traversal (see header). Adoption requires the pending entry to
    // actually be the current top — a close-AND-navigate leaves its entry buried under the new
    // route (its timer then no-ops at fire time via the same ownership check), and adopting a
    // buried entry would leave this overlay with nothing parked.
    const adoptable = isOverlayState(window.history.state) ? pendingUnwindTimers.pop() : undefined
    if (adoptable !== undefined) {
      window.clearTimeout(adoptable)
    } else {
      window.history.pushState({ nativeBackDismiss: true }, '')
    }

    function onPopState(e: PopStateEvent) {
      // An unwind pop from SOME overlay's teardown (possibly a sibling that just deregistered —
      // in which case we'd wrongly be "top") — never a user Back.
      if (consumeOverlaySelfPop(e)) return
      // Only the topmost parked overlay owns this pop — a lower one must stay open.
      if (!registration.isTop()) return
      dismissedByBack = true
      onCloseRef.current()
    }
    window.addEventListener('popstate', onPopState)

    return () => {
      registration.deregister()
      window.removeEventListener('popstate', onPopState)
      // A Back press already consumed our entry — nothing to unwind.
      if (dismissedByBack) return
      // Closed via the UI: schedule the balancing pop (adoptable within this tick, see header).
      // The ownership check moves to fire time; it skips when our entry is no longer current —
      // the closing click ALSO navigated (e.g. a MoreSheet nav tile closes then pushes a route),
      // and popping would undo that navigation. The buried entry is harmless — the route guard
      // skims past it.
      const id = window.setTimeout(() => {
        const at = pendingUnwindTimers.indexOf(id)
        if (at >= 0) pendingUnwindTimers.splice(at, 1)
        const owned =
          (window.history.state as { nativeBackDismiss?: unknown } | null)?.nativeBackDismiss === true
        if (owned) {
          beginOverlaySelfPop()
          window.history.back()
        }
      }, 0)
      pendingUnwindTimers.push(id)
    }
  }, [enabled])
}
