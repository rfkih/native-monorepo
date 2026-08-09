import { Suspense, lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Spinner } from '@/components/ui/Spinner'
import { Button } from '@/components/ui/Button'
import { Wordmark } from '@/components/Wordmark'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { useAuth } from '@/lib/authContext'

// Route-level code splitting, reusing the console's OWN feature screens unchanged — same lazy
// import paths + named exports as frontend/console/src/app/App.tsx. No fork, no copy: `@`
// resolves into ../console/src (see vite.config.ts), so this is byte-identical code.
const Me = lazy(() => import('@/features/me/Me').then((m) => ({ default: m.Me })))
const MyExpenses = lazy(() =>
  import('@/features/expenses/MyExpenses').then((m) => ({ default: m.MyExpenses })),
)
const MePayslipsScreen = lazy(() =>
  import('@/features/me/MePayslipsScreen').then((m) => ({ default: m.MePayslipsScreen })),
)
const MeTimeoffScreen = lazy(() =>
  import('@/features/me/MeTimeoffScreen').then((m) => ({ default: m.MeTimeoffScreen })),
)
const MeAccount = lazy(() =>
  import('@/features/me/MeAccount').then((m) => ({ default: m.MeAccount })),
)

function CenteredSpinner() {
  return (
    <div className="grid min-h-screen place-items-center bg-paper text-brand-600">
      <Spinner />
    </div>
  )
}

/**
 * The Employee app's front door (ADR 0049 P5) — a dedicated, standalone origin for the console's
 * `/me` self-service surface, reached by a plain personal OIDC login (NOT the per-outlet `device`
 * login the Business-app till uses). There is no POS, no back-office, and no personal elevation
 * here: `auth.elevate()` is never called, so the dormant elevation UserManager inside
 * `@/lib/auth` simply never activates on this origin — `Shell` is never mounted either.
 *
 * Unauthenticated → a minimal branded sign-in screen (explicit login, mirroring the console's own
 * "login is user-initiated" rule — no auto-bounce to the IdP).
 * Authenticated → the same five self-service routes the console mounts, at the same paths, so a
 * bookmark/deep-link from either app works identically.
 */
export function App() {
  const auth = useAuth()
  const { t } = useTranslation()

  // Covers BOTH the initial silent-restore check and the `/auth/callback` redirect landing —
  // `auth.ready` only flips true once oidc-client-ts has fully resolved either path (see
  // @/lib/auth's OidcAuthProvider), so there is nothing else to special-case here.
  if (!auth.ready) {
    return <CenteredSpinner />
  }

  if (!auth.authenticated) {
    return (
      <div className="grid min-h-screen place-items-center bg-paper px-6">
        <div className="flex w-full max-w-sm flex-col items-center gap-6 text-center">
          <Wordmark />
          <p className="text-sm text-ink-3">{t('me.subtitle')}</p>
          <Button size="xl" className="w-full" onClick={() => auth.login()}>
            {t('appWelcome.signIn')}
          </Button>
          <LanguageSwitcher />
        </div>
      </div>
    )
  }

  return (
    <Suspense fallback={<CenteredSpinner />}>
      <Routes>
        <Route path="/me" element={<Me />} />
        <Route path="/me/expenses" element={<MyExpenses />} />
        <Route path="/me/payslips" element={<MePayslipsScreen />} />
        <Route path="/me/timeoff" element={<MeTimeoffScreen />} />
        <Route path="/me/account" element={<MeAccount />} />
        <Route path="*" element={<Navigate to="/me" replace />} />
      </Routes>
    </Suspense>
  )
}
