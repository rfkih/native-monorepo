/**
 * TransitionedRoutes — app-wide smooth route changes for a declarative <BrowserRouter> app, and
 * the one place that owns WHERE a navigation leaves the page (navigation contract, ADR 0075 rule N4).
 *
 * React Router only drives the View Transitions API from its data router (RouterProvider); under
 * <BrowserRouter> the `viewTransition` Link prop is a no-op. This wrapper supplies the same effect
 * framework-independently: it renders <Routes> against a DEFERRED location and, when the real
 * location moves, wraps the swap in document.startViewTransition so the old screen is snapshotted
 * and the new one animates in (choreography in index.css; chrome with its own
 * view-transition-name stays static).
 *
 * - REPLACE navigations (guard redirects like /me/payslips → /me at desktop width) swap
 *   instantly — animating both hops of a redirect chain reads as a stutter, not polish.
 * - No startViewTransition (Firefox/Safari, reduced-motion users get animation:none via
 *   CSS anyway) → plain state swap, exactly the pre-wrapper behavior.
 * - flushSync inside the transition callback is the documented pattern: the DOM must be
 *   fully updated within the callback so the API can capture the "new" snapshot.
 *
 * Scroll lives here rather than in a hook mounted next to it because BOTH of its moments are this
 * component's own: the outgoing offset is only readable while the old screen is still mounted
 * (just before the swap), and the incoming one must be set while the DOM already holds the new
 * screen (inside the transition callback, so the snapshot is taken at the right position). A hook
 * watching `useLocation()` would fire a commit too early — against the screen still on display.
 */
import { useEffect, useLayoutEffect, useState } from 'react'
import { flushSync } from 'react-dom'
import { Routes, useLocation, useNavigationType } from 'react-router-dom'
import {
  applyScrollAction,
  loadScrollMemory,
  navKindOf,
  recallScroll,
  rememberScroll,
  saveScrollMemory,
  scrollActionFor,
} from './scrollBehavior'

export function TransitionedRoutes({ children }: { children: React.ReactNode }) {
  const location = useLocation()
  const navigationType = useNavigationType()
  const [displayed, setDisplayed] = useState(location)

  // Take scroll restoration over from the browser. Its automatic restore fires on `popstate`,
  // when this component still shows the PREVIOUS screen and the document is the wrong height —
  // so it clamps to a meaningless offset and never corrects (measured: 1194px where 600px was
  // wanted). See scrollBehavior.ts. Also rehydrate the memory, and persist it before the page
  // goes away — `pagehide` fires on the Android WebView being backgrounded, where `unload`
  // does not.
  useEffect(() => {
    if ('scrollRestoration' in window.history) window.history.scrollRestoration = 'manual'
    loadScrollMemory()
    // A reload lands on an entry we may already know (react-router keeps `key` in history.state).
    applyScrollAction(
      scrollActionFor({
        navigationType: 'POP',
        hash: window.location.hash,
        saved: recallScroll(location.key),
      }),
    )
    window.addEventListener('pagehide', saveScrollMemory)
    return () => window.removeEventListener('pagehide', saveScrollMemory)
    // Mount only: this is the boot handover, not a per-navigation concern.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useLayoutEffect(() => {
    if (location === displayed) return

    // The outgoing screen is still mounted — the only moment its offset is readable.
    rememberScroll(displayed.key, window.scrollY)
    const action = scrollActionFor({
      navigationType: navKindOf(navigationType),
      hash: location.hash,
      saved: recallScroll(location.key),
    })

    const canAnimate =
      typeof document.startViewTransition === 'function' && navigationType !== 'REPLACE'
    if (!canAnimate) {
      // Not a derivable value: `displayed` must LAG `location` by exactly one commit so the
      // pre-navigation DOM exists to snapshot — this setState-in-effect is the mechanism, not
      // an oversight (the animated path below does the same inside the browser's VT callback).
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setDisplayed(location)
      applyScrollAction(action)
      return
    }
    document.startViewTransition(() => {
      flushSync(() => setDisplayed(location))
      // Inside the callback, so the "new" snapshot is captured at the destination's own offset
      // instead of animating in at the outgoing screen's.
      applyScrollAction(action)
    })
  }, [location, displayed, navigationType])

  return <Routes location={displayed}>{children}</Routes>
}
