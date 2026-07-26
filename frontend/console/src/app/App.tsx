import { Suspense, lazy, useEffect, useRef } from 'react'
import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { Shell } from '@/app/Shell'
import { Spinner } from '@/components/ui/Spinner'
import { hasAnyRole, useAuth } from '@/lib/authContext'
import { useSession } from '@/lib/session'

// Route-level code splitting — keeps Recharts (dashboard) out of the POS path.
const OnboardingWizard = lazy(() =>
  import('@/features/onboarding/OnboardingWizard').then((m) => ({ default: m.OnboardingWizard })),
)
const Dashboard = lazy(() =>
  import('@/features/dashboard/Dashboard').then((m) => ({ default: m.Dashboard })),
)
const IncomeStatement = lazy(() =>
  import('@/features/statements/IncomeStatement').then((m) => ({ default: m.IncomeStatement })),
)
const BalanceSheet = lazy(() =>
  import('@/features/statements/BalanceSheet').then((m) => ({ default: m.BalanceSheet })),
)
const Pos = lazy(() => import('@/features/pos/Pos').then((m) => ({ default: m.Pos })))
const OrgTree = lazy(() =>
  import('@/features/org/OrgTree').then((m) => ({ default: m.OrgTree })),
)
const GroupConsolidation = lazy(() =>
  import('@/features/groups/GroupConsolidation').then((m) => ({ default: m.GroupConsolidation })),
)
const PeriodClose = lazy(() =>
  import('@/features/close/PeriodClose').then((m) => ({ default: m.PeriodClose })),
)
const Team = lazy(() =>
  import('@/features/team/Team').then((m) => ({ default: m.Team })),
)
const AccessDenied = lazy(() =>
  import('@/features/auth/AccessDenied').then((m) => ({ default: m.AccessDenied })),
)
const MenuManagement = lazy(() =>
  import('@/features/menu/MenuManagement').then((m) => ({ default: m.MenuManagement })),
)
const Kitchen = lazy(() =>
  import('@/features/kitchen/Kitchen').then((m) => ({ default: m.Kitchen })),
)
const Signup = lazy(() =>
  import('@/features/signup/Signup').then((m) => ({ default: m.Signup })),
)
const Landing = lazy(() =>
  import('@/features/landing/Landing').then((m) => ({ default: m.Landing })),
)

function CenteredSpinner() {
  return (
    <div className="grid place-items-center py-24 text-brand-600">
      <Spinner />
    </div>
  )
}

function FullScreenSpinner() {
  return (
    <div className="grid min-h-screen place-items-center text-brand-600">
      <Spinner />
    </div>
  )
}

/** The /login route — immediately starts the Keycloak login redirect (a bookmarkable entry point). */
function LoginLauncher() {
  const auth = useAuth()
  const fired = useRef(false)
  useEffect(() => {
    if (fired.current) return
    fired.current = true
    auth.login()
  }, [auth])
  return <FullScreenSpinner />
}

/** True for routes that must be reachable without authentication. */
const PUBLIC_PATHS = new Set(['/signup'])

/**
 * Role-gated routing (one app, two surfaces + a public front door):
 *  - UNAUTHENTICATED → the public marketing site: "/" landing, /login (Keycloak redirect launcher),
 *    /signup (self-service registration). No auto-bounce to the IdP — login is user-initiated.
 *  - owner / manager → dashboard (+ onboarding) AND may open the POS.
 *  - cashier → ONLY the POS; every other path redirects to /pos (the dashboard is never mounted).
 *  - neither role → an access-denied screen.
 *  - /signup → always rendered before any auth check (public self-service registration).
 *
 * This is the UI half of the separation; the gateway enforces the same boundary on the API
 * (a cashier token cannot reach the finance/dashboard routes), so a bypassed guard still fails closed.
 */
export function App() {
  const auth = useAuth()
  const { company, loading } = useSession()
  const { pathname } = useLocation()

  // Public routes render immediately — no auth redirect, no role check, no spinner.
  if (PUBLIC_PATHS.has(pathname)) {
    return (
      <Suspense fallback={<CenteredSpinner />}>
        <Routes>
          <Route path="/signup" element={<Signup />} />
        </Routes>
      </Suspense>
    )
  }

  // Wait for the OIDC provider to resolve the session (silent restore or redirect callback).
  if (!auth.ready) {
    return <FullScreenSpinner />
  }

  // Unauthenticated → the public marketing site. Login is EXPLICIT: the landing "Sign in" button
  // and the /login route trigger the Keycloak redirect. A deep-link to any protected path lands
  // here (and the gateway independently rejects tokenless API calls), so guards still fail closed.
  if (!auth.authenticated) {
    return (
      <Suspense fallback={<CenteredSpinner />}>
        <Routes>
          <Route path="/" element={<Landing />} />
          <Route path="/login" element={<LoginLauncher />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    )
  }

  // Authenticated → wait for the signed-in company to load, then route the role-gated app.
  if (loading) {
    return <FullScreenSpinner />
  }

  const canDashboard = hasAnyRole(auth.roles, 'owner', 'manager')
  const canPos = hasAnyRole(auth.roles, 'owner', 'manager', 'cashier')

  if (!canDashboard && !canPos) {
    return (
      <Suspense fallback={<CenteredSpinner />}>
        <AccessDenied />
      </Suspense>
    )
  }

  const home = canDashboard ? '/' : '/pos'

  return (
    <Suspense fallback={<CenteredSpinner />}>
      <Routes>
        {/* The POS is a full-screen "front office" — it renders OUTSIDE the sidebar/topbar shell. */}
        {canPos && <Route path="/pos" element={<Pos />} />}
        {canPos && <Route path="/menu" element={<MenuManagement />} />}
        {canPos && <Route path="/kitchen" element={<Kitchen />} />}

        {/* Everything else shares the back-office shell, mounted once via a layout route. */}
        <Route
          element={
            <Shell>
              <Outlet />
            </Shell>
          }
        >
          {canDashboard && <Route path="/onboarding" element={<OnboardingWizard />} />}
          {canDashboard && (
            <Route
              path="/"
              element={company ? <Dashboard /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/statements/income"
              element={company ? <IncomeStatement /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/statements/balance-sheet"
              element={company ? <BalanceSheet /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/org"
              element={company ? <OrgTree /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/groups"
              element={company ? <GroupConsolidation /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/close"
              element={company ? <PeriodClose /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/team"
              element={company ? <Team /> : <Navigate to="/onboarding" replace />}
            />
          )}
          <Route path="*" element={<Navigate to={home} replace />} />
        </Route>
      </Routes>
    </Suspense>
  )
}
