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
 * in or out. Stacked overlays each park their own entry and pop LIFO, exactly as expected.
 */
import { useEffect, useRef } from 'react'

export function useBackDismiss(onClose: () => void) {
  // Keep the latest onClose without re-running the park effect (which would re-park an entry each
  // render). Synced in its own effect — writing a ref during render is disallowed (react-hooks/refs)
  // and unnecessary here, since onCloseRef is only read later in the popstate/teardown handlers.
  const onCloseRef = useRef(onClose)
  useEffect(() => {
    onCloseRef.current = onClose
  }, [onClose])
  // Set true only when WE call history.back() during teardown, so the resulting popstate is not
  // mistaken for a user Back. A ref (not a closure var) because it must survive React 18 StrictMode's
  // setup → cleanup → setup double-invoke on the same fiber — otherwise the dev-only extra back()
  // would be read as a real Back and close the overlay the instant it opens.
  const selfPopRef = useRef(false)

  useEffect(() => {
    if (typeof window === 'undefined' || !window.history) return
    let dismissedByBack = false

    window.history.pushState({ nativeBackDismiss: true }, '')

    function onPopState() {
      if (selfPopRef.current) {
        // The pop we ourselves triggered while unwinding — consume the flag and ignore it.
        selfPopRef.current = false
        return
      }
      dismissedByBack = true
      onCloseRef.current()
    }
    window.addEventListener('popstate', onPopState)

    return () => {
      window.removeEventListener('popstate', onPopState)
      // Closed via the UI, not Back: pop the entry we parked so the history stack stays balanced
      // (otherwise entries pile up across open/close cycles and Back no-ops once per stale entry).
      // Two cases skip the pop:
      //   • dismissedByBack — a Back press already consumed our entry.
      //   • our entry is no longer the current one — the closing click ALSO navigated (e.g. a
      //     MoreSheet nav tile calls onClose() then pushes a route). history.state is then the new
      //     route's, not our marker; popping here would undo that navigation. Leave our entry buried
      //     (harmless) instead.
      const owned = (window.history.state as { nativeBackDismiss?: unknown } | null)?.nativeBackDismiss === true
      if (!dismissedByBack && owned) {
        selfPopRef.current = true
        window.history.back()
      }
    }
  }, [])
}
