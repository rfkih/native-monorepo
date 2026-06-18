import { Suspense, lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { Shell } from '@/app/Shell'
import { Spinner } from '@/components/ui/Spinner'
import { useSession } from '@/lib/session'

// Route-level code splitting — keeps Recharts (dashboard) out of the onboarding path.
const OnboardingWizard = lazy(() =>
  import('@/features/onboarding/OnboardingWizard').then((m) => ({ default: m.OnboardingWizard })),
)
const Dashboard = lazy(() =>
  import('@/features/dashboard/Dashboard').then((m) => ({ default: m.Dashboard })),
)

export function App() {
  const { company } = useSession()
  return (
    <Shell>
      <Suspense
        fallback={
          <div className="grid place-items-center py-24 text-emerald">
            <Spinner />
          </div>
        }
      >
        <Routes>
          <Route path="/onboarding" element={<OnboardingWizard />} />
          <Route
            path="/"
            element={company ? <Dashboard /> : <Navigate to="/onboarding" replace />}
          />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </Shell>
  )
}
