/**
 * useBackGuard — hardware Back must never silently leave a page in the Android shells.
 *
 * The shells translate hardware Back into `webView.goBack()` (see MainActivity.kt), so the only
 * web-side interception point is the History API — the technique proven by useBackDismiss. This
 * hook generalizes it to routes: on every navigation it parks a sentinel entry (same URL, state
 * `{ ...routerState, backGuard: true }`) ON TOP of the route entry. A Back press then pops the
 * sentinel — the URL doesn't change, react-router doesn't navigate — and the guard answers with a
 * confirmation dialog instead. Confirm = `go(-2)` (sentinel was eagerly re-parked, see below);
 * Cancel = stay, stack already balanced.
 *
 * Protocol invariants (markers, registry, root detection) live in backGuardProtocol.ts. The
 * popstate handler's decision order:
 *   1. Guard-marked landing → a pop consumed an entry above a sentinel (overlay dismiss, or our
 *      own go(-2) arriving on the previous route's sentinel) → not ours to handle.
 *   2. Overlay-marked landing → live overlay closes itself; with no overlay open the entry is
 *      stale (its owner closed via a click that also navigated) → skim another back() past it.
 *   3. Bare route landing → a real Back press ate our sentinel: re-park it SYNCHRONOUSLY first
 *      (so a second press while the dialog is open pops the fresh sentinel and reads as Cancel —
 *      never a silent navigation behind the dialog), then show the leave/exit dialog.
 * Self-initiated pops (confirm's go(-2), the exit fallback's back(), skims) are counted in
 * `selfPopsRef` and never open a dialog; if one lands on an unguarded route entry (visited before
 * the guard was active) it just restores the sentinel invariant. Overlay teardown unwind pops are
 * recognized via consumeOverlaySelfPop (shared with useBackDismiss).
 *
 * Exit at the app root: `minimizeNativeShell()` backgrounds the app via the shells' NativeShell
 * plugin. On an APK predating the plugin the fallback consumes our sentinel and suspends the
 * guard briefly, so the NEXT press reaches the native layer (which backgrounds at history root) —
 * the BackGuard component shows a "press back again" hint for that window.
 *
 * The parking effect is idempotent with NO cleanup unwind (unlike useBackDismiss): it skips when a
 * protocol entry is already on top, which also makes it StrictMode- and reload-safe (the sentinel
 * state survives both). It also contains no setState: a dialog left open across a navigation is
 * derived-hidden instead — the open dialog carries the location.key it belongs to, and a different
 * current key renders it closed (react-hooks/set-state-in-effect).
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { minimizeNativeShell } from '@/lib/nativeShell'
import {
  consumeOverlaySelfPop,
  isAtRoot,
  isBackGuardEnabled,
  isGuardablePath,
  isGuardState,
  isOverlayState,
  isProtocolState,
  openOverlayCount,
} from './backGuardProtocol'

export type BackGuardDialogKind = 'leave' | 'exit'

/** How long the exit fallback keeps the guard out of the way of the follow-up Back press. */
const EXIT_FALLBACK_SUSPEND_MS = 2500

function parkSentinel(): void {
  const s: unknown = window.history.state
  if (isProtocolState(s)) return
  window.history.pushState({ ...((s as object | null) ?? {}), backGuard: true }, '')
}

export function useBackGuard(homePath: string): {
  dialog: BackGuardDialogKind | null
  hintVisible: boolean
  cancel: () => void
  confirmLeave: () => void
  confirmExit: () => void
} {
  const location = useLocation()
  // Activation is stable for the life of the page (shell bridge / dev override are load-time facts).
  const enabled = useMemo(() => isBackGuardEnabled(), [])

  const [dialogAt, setDialogAt] = useState<{ kind: BackGuardDialogKind; key: string } | null>(null)
  // Effective value is render-derived: a navigation while the dialog was open (redirect,
  // programmatic nav) gives the location a new key, which hides the stale dialog with no
  // effect-time setState.
  const dialog = enabled && dialogAt !== null && dialogAt.key === location.key ? dialogAt.kind : null

  const [hintVisible, setHintVisible] = useState(false)
  // Mirrors the EFFECTIVE dialog for the popstate handler. Written directly in open/close (the
  // handler must see the value within the same tick); the effect re-syncs it when a navigation
  // derived-hides the dialog (ref writes in effects are fine — it's setState that cascades).
  const dialogRef = useRef<BackGuardDialogKind | null>(null)
  useEffect(() => {
    dialogRef.current = dialog
  }, [dialog])

  // Pops we initiated (confirm go(-2), exit fallback back(), stale-entry skims) — consumed one per
  // popstate so they are never mistaken for user presses. A ref: must survive StrictMode remounts.
  const selfPopsRef = useRef(0)
  const suspendedUntilRef = useRef(0)
  // Synced in effects — writing a ref during render is disallowed (react-hooks/refs); both are
  // only read later inside the popstate handler.
  const homePathRef = useRef(homePath)
  useEffect(() => {
    homePathRef.current = homePath
  }, [homePath])
  const locationKeyRef = useRef(location.key)

  // Park a sentinel above every route entry the moment we arrive on it.
  useEffect(() => {
    if (!enabled) return
    locationKeyRef.current = location.key
    if (!isGuardablePath(location.pathname)) return
    parkSentinel()
  }, [enabled, location])

  const openDialog = useCallback((kind: BackGuardDialogKind) => {
    dialogRef.current = kind
    setDialogAt({ kind, key: locationKeyRef.current })
  }, [])
  const closeDialog = useCallback(() => {
    dialogRef.current = null
    setDialogAt(null)
  }, [])

  useEffect(() => {
    if (!enabled || typeof window === 'undefined' || !window.history) return

    function onPopState(e: PopStateEvent) {
      // An overlay teardown's own unwind pop — never a user Back, regardless of where it lands
      // (guards the rare stack order where an overlay entry sits below our sentinel). Consuming
      // here is idempotent per event, so the overlay handlers still read it as consumed too.
      // Checked BEFORE our own self-pop counter so an interleaved overlay unwind can't burn a
      // pending guard pop's token.
      if (consumeOverlaySelfPop(e)) return

      const isSelf = selfPopsRef.current > 0
      if (isSelf) selfPopsRef.current -= 1

      // Landed on a guard sentinel: the popped entry sat above one (an overlay's entry, or the
      // route entry our own go(-2) removed). Nothing to do — the router already showed the
      // sentinel's URL and the sentinel keeps guarding it.
      if (isGuardState(e.state)) return

      // Landed on an overlay-marked entry.
      if (isOverlayState(e.state)) {
        if (openOverlayCount() > 0) return // a live overlay's own hook handles this pop
        // Stale buried entry — skim past it so the press isn't silently swallowed.
        selfPopsRef.current += 1
        window.history.back()
        return
      }

      // Bare route entry from here on.
      if (!isGuardablePath(window.location.pathname)) return
      if (Date.now() < suspendedUntilRef.current) return // exit fallback window — let it through

      if (isSelf) {
        // Our own pop finished on a route that predates the guard — restore the invariant quietly.
        parkSentinel()
        return
      }

      // A real Back press consumed the sentinel. Re-park before anything else (see header).
      parkSentinel()
      if (dialogRef.current !== null) {
        // Back while the dialog is open = Cancel (the Android convention for dialogs).
        closeDialog()
        return
      }
      openDialog(isAtRoot(window.location.pathname, e.state, homePathRef.current) ? 'exit' : 'leave')
    }

    window.addEventListener('popstate', onPopState)
    return () => window.removeEventListener('popstate', onPopState)
  }, [enabled, openDialog, closeDialog])

  const cancel = closeDialog

  const confirmLeave = useCallback(() => {
    closeDialog()
    // Pop our re-parked sentinel AND the route entry in one atomic step; lands on the previous
    // route's sentinel (ignored above), so the router renders the previous page exactly once.
    selfPopsRef.current += 1
    window.history.go(-2)
  }, [closeDialog])

  const confirmExit = useCallback(() => {
    closeDialog()
    void minimizeNativeShell().then((minimized) => {
      if (minimized) return
      // Old APK (no NativeShell plugin) or dev browser: consume our sentinel and stand down
      // briefly so the next press reaches the environment (native shell backgrounds at root).
      selfPopsRef.current += 1
      window.history.back()
      suspendedUntilRef.current = Date.now() + EXIT_FALLBACK_SUSPEND_MS
      setHintVisible(true)
      window.setTimeout(() => {
        setHintVisible(false)
        // Still here after the window closed — restore the sentinel invariant.
        if (Date.now() >= suspendedUntilRef.current && isGuardablePath(window.location.pathname)) {
          parkSentinel()
        }
      }, EXIT_FALLBACK_SUSPEND_MS)
    })
  }, [closeDialog])

  return { dialog, hintVisible, cancel, confirmLeave, confirmExit }
}
