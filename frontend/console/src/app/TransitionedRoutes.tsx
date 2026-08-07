/**
 * TransitionedRoutes — app-wide smooth route changes for a declarative <BrowserRouter> app.
 *
 * React Router only drives the View Transitions API from its data router (RouterProvider);
 * under <BrowserRouter> the `viewTransition` Link prop is a no-op. This wrapper supplies the
 * same effect framework-independently: it renders <Routes> against a DEFERRED location and,
 * when the real location moves, wraps the swap in document.startViewTransition so the old
 * screen is snapshotted and the new one animates in (choreography in index.css; chrome with
 * its own view-transition-name stays static).
 *
 * - REPLACE navigations (guard redirects like /me/payslips → /me at desktop width) swap
 *   instantly — animating both hops of a redirect chain reads as a stutter, not polish.
 * - No startViewTransition (Firefox/Safari, reduced-motion users get animation:none via
 *   CSS anyway) → plain state swap, exactly the pre-wrapper behavior.
 * - flushSync inside the transition callback is the documented pattern: the DOM must be
 *   fully updated within the callback so the API can capture the "new" snapshot.
 */
import { useLayoutEffect, useState } from 'react'
import { flushSync } from 'react-dom'
import { Routes, useLocation, useNavigationType } from 'react-router-dom'

export function TransitionedRoutes({ children }: { children: React.ReactNode }) {
  const location = useLocation()
  const navigationType = useNavigationType()
  const [displayed, setDisplayed] = useState(location)

  useLayoutEffect(() => {
    if (location === displayed) return
    const canAnimate =
      typeof document.startViewTransition === 'function' && navigationType !== 'REPLACE'
    if (!canAnimate) {
      // Not a derivable value: `displayed` must LAG `location` by exactly one commit so the
      // pre-navigation DOM exists to snapshot — this setState-in-effect is the mechanism, not
      // an oversight (the animated path below does the same inside the browser's VT callback).
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setDisplayed(location)
      return
    }
    document.startViewTransition(() => {
      flushSync(() => setDisplayed(location))
    })
  }, [location, displayed, navigationType])

  return <Routes location={displayed}>{children}</Routes>
}
