import { Suspense, lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
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
const Pos = lazy(() => import('@/features/pos/Pos').then((m) => ({ default: m.Pos })))
const AccessDenied = lazy(() =>
  import('@/features/auth/AccessDenied').then((m) => ({ default: m.AccessDenied })),
)

function CenteredSpinner() {
  return (
    <div className="grid place-items-center py-24 text-emerald">
      <Spinner />
    </div>
  )
}

/**
 * Role-gated routing (one app, two surfaces):
 *  - owner / manager → dashboard (+ onboarding) AND may open the POS.
 *  - cashier → ONLY the POS; every other path redirects to /pos (the dashboard is never mounted).
 *  - neither role → an access-denied screen.
 *
 * This is the UI half of the separation; the gateway enforces the same boundary on the API
 * (a cashier token cannot reach the finance/dashboard routes), so a bypassed guard still fails closed.
 */
export function App() {
  const auth = useAuth()
  const { company, loading } = useSession()

  // Wait for the auth redirect to resolve and the signed-in company to load before routing.
  if (!auth.ready || loading) {
    return (
      <div className="grid min-h-screen place-items-center text-emerald">
        <Spinner />
      </div>
    )
  }

  const canDashboard = hasAnyRole(auth.roles, 'owner', 'manager')
  const canPos = hasAnyRole(auth.roles, 'owner', 'manager', 'cashier')

  if (!canDashboard && !canPos) {
    return (
      <Shell>
        <Suspense fallback={<CenteredSpinner />}>
          <AccessDenied />
        </Suspense>
      </Shell>
    )
  }

  const home = canDashboard ? '/' : '/pos'

  return (
    <Shell>
      <Suspense fallback={<CenteredSpinner />}>
        <Routes>
          {canDashboard && <Route path="/onboarding" element={<OnboardingWizard />} />}
          {canDashboard && (
            <Route
              path="/"
              element={company ? <Dashboard /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canPos && <Route path="/pos" element={<Pos />} />}
          <Route path="*" element={<Navigate to={home} replace />} />
        </Routes>
      </Suspense>
    </Shell>
  )
}
