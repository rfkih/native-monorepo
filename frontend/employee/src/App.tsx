import { Suspense, lazy, useState } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Spinner } from '@/components/ui/Spinner'
import { Button } from '@/components/ui/Button'
import { Field, TextInput } from '@/components/ui/Field'
import { Wordmark } from '@/components/Wordmark'
import { LanguageSwitcher } from '@/components/LanguageSwitcher'
import { useAuth } from '@/lib/authContext'
import { AppUpdatePrompt } from '@/components/AppUpdatePrompt'
import { BackGuard } from '@/components/mobile/BackGuard'
import { StaffShell } from './staff/StaffShell'
import { Beranda } from './staff/Beranda'
import { useIsSupervisor } from './staff/ui'

// Route-level code splitting for everything beyond the landing home. Screens live in ./staff and
// reuse the console's /me data hooks via the `@` alias (see vite.config.ts).
const TimeoffScreen = lazy(() =>
  import('./staff/TimeoffScreen').then((m) => ({ default: m.TimeoffScreen })),
)
const ClaimsScreen = lazy(() =>
  import('./staff/ClaimsScreen').then((m) => ({ default: m.ClaimsScreen })),
)
const ClaimDetailScreen = lazy(() =>
  import('./staff/ClaimDetailScreen').then((m) => ({ default: m.ClaimDetailScreen })),
)
const PayslipsScreen = lazy(() =>
  import('./staff/PayslipsScreen').then((m) => ({ default: m.PayslipsScreen })),
)
const ProfileScreen = lazy(() =>
  import('./staff/ProfileScreen').then((m) => ({ default: m.ProfileScreen })),
)
const MeAccount = lazy(() => import('@/features/me/MeAccount').then((m) => ({ default: m.MeAccount })))
const ApprovalsScreen = lazy(() =>
  import('./staff/ApprovalsScreen').then((m) => ({ default: m.ApprovalsScreen })),
)

function CenteredSpinner() {
  return (
    <div className="grid min-h-[100dvh] place-items-center bg-paper text-brand-600">
      <Spinner />
    </div>
  )
}

/**
 * The Native Karyawan staff app (ADR 0049 P5). A dedicated, phone-first self-service app over the
 * console's `/me` surface, reached by a plain personal OIDC login. A 4-tab shell (Beranda / Cuti /
 * Klaim / Slip) wraps the tab routes; claim detail and the profile are pushed screens without the
 * tab bar. No POS, no back-office, no personal elevation — `Shell` is never mounted.
 */
export function App() {
  const auth = useAuth()
  const isSup = useIsSupervisor()
  const { t } = useTranslation()
  // Optional login pre-fill (ADR 0054): a staff login is <companyCode>.<employeeId>. There is no
  // company context before authentication, so both are typed here (from what the manager gave the
  // employee) and composed into Keycloak's `login_hint`; left blank, the plain hosted login prompts
  // for the full username as before.
  const [companyCode, setCompanyCode] = useState('')
  const [employeeId, setEmployeeId] = useState('')

  if (!auth.ready) {
    return <CenteredSpinner />
  }

  if (!auth.authenticated) {
    const loginHint =
      companyCode.trim() && employeeId.trim()
        ? `${companyCode.trim().toLowerCase()}.${employeeId.trim()}`
        : undefined
    return (
      <div className="grid min-h-[100dvh] place-items-center bg-paper px-6">
        <div className="flex w-full max-w-sm flex-col items-center gap-6 text-center">
          <Wordmark />
          <p className="text-sm text-ink-3">{t('me.subtitle')}</p>
          <div className="w-full space-y-3 text-left">
            <Field
              label={t('appWelcome.companyCode')}
              hint={t('appWelcome.companyCodeHint')}
              htmlFor="company-code"
            >
              <TextInput
                id="company-code"
                value={companyCode}
                onChange={(e) => setCompanyCode(e.target.value)}
                autoCapitalize="none"
                autoCorrect="off"
              />
            </Field>
            <Field label={t('appWelcome.employeeId')} htmlFor="employee-id">
              <TextInput
                id="employee-id"
                value={employeeId}
                onChange={(e) => setEmployeeId(e.target.value)}
                autoCapitalize="none"
                autoCorrect="off"
              />
            </Field>
          </div>
          <Button size="xl" className="w-full" onClick={() => auth.login(loginHint)}>
            {t('appWelcome.signIn')}
          </Button>
          <LanguageSwitcher />
        </div>
      </div>
    )
  }

  return (
    <Suspense fallback={<CenteredSpinner />}>
      {/* Hardware-Back confirm guard (Android shell only — self-disables in browsers). */}
      <BackGuard homePath="/me" />
      {/* ADR 0062 staleness recovery — this app has no service worker, so a soft update prompt is
          the only nudge a long-lived cached bundle gets (no-ops when /version.json is absent). */}
      <div className="fixed inset-x-0 top-0 z-[70] flex flex-col print:hidden">
        <AppUpdatePrompt />
      </div>
      <Routes>
        {/* Tab screens — wrapped in the bottom-nav shell. */}
        <Route element={<StaffShell />}>
          <Route path="/me" element={<Beranda />} />
          <Route path="/me/timeoff" element={<TimeoffScreen />} />
          <Route path="/me/expenses" element={<ClaimsScreen />} />
          <Route path="/me/payslips" element={<PayslipsScreen />} />
        </Route>
        {/* Pushed screens — own back header, no tab bar. */}
        <Route path="/me/expenses/:id" element={<ClaimDetailScreen />} />
        <Route path="/me/profile" element={<ProfileScreen />} />
        <Route path="/me/account" element={<MeAccount />} />
        {/* Supervisor approvals — HR-capable logins only (the gateway enforces the same boundary). */}
        <Route
          path="/me/approvals"
          element={isSup ? <ApprovalsScreen /> : <Navigate to="/me" replace />}
        />
        <Route path="*" element={<Navigate to="/me" replace />} />
      </Routes>
    </Suspense>
  )
}
