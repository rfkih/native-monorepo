import { Suspense, lazy, useEffect, useRef } from 'react'
import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Shell } from '@/app/Shell'
import { Spinner } from '@/components/ui/Spinner'
import { BrandMark } from '@/components/Wordmark'
import { hasAnyRole, useAuth } from '@/lib/authContext'
import { usePageAccess } from '@/lib/pageAccess'
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
const CashFlow = lazy(() =>
  import('@/features/statements/CashFlow').then((m) => ({ default: m.CashFlow })),
)
const Budgets = lazy(() => import('@/features/budget/Budgets').then((m) => ({ default: m.Budgets })))
const BudgetDetail = lazy(() =>
  import('@/features/budget/BudgetDetail').then((m) => ({ default: m.BudgetDetail })),
)
const PosSwitch = lazy(() =>
  import('@/features/servicepos/PosSwitch').then((m) => ({ default: m.PosSwitch })),
)
const CatalogSwitch = lazy(() =>
  import('@/features/servicepos/PosSwitch').then((m) => ({ default: m.CatalogSwitch })),
)
const OrgTree = lazy(() =>
  import('@/features/org/OrgTree').then((m) => ({ default: m.OrgTree })),
)
const OrgUnitDetail = lazy(() =>
  import('@/features/org/OrgUnitDetail').then((m) => ({ default: m.OrgUnitDetail })),
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
const Me = lazy(() => import('@/features/me/Me').then((m) => ({ default: m.Me })))
const Customers = lazy(() =>
  import('@/features/ar/Customers').then((m) => ({ default: m.Customers })),
)
const InvoicesList = lazy(() =>
  import('@/features/ar/InvoicesList').then((m) => ({ default: m.InvoicesList })),
)
const InvoiceDetail = lazy(() =>
  import('@/features/ar/InvoiceDetail').then((m) => ({ default: m.InvoiceDetail })),
)
const NewInvoice = lazy(() =>
  import('@/features/ar/NewInvoice').then((m) => ({ default: m.NewInvoice })),
)
const ArAging = lazy(() => import('@/features/ar/ArAging').then((m) => ({ default: m.ArAging })))
const Vendors = lazy(() =>
  import('@/features/ap/Vendors').then((m) => ({ default: m.Vendors })),
)
const BillsList = lazy(() =>
  import('@/features/ap/BillsList').then((m) => ({ default: m.BillsList })),
)
const BillDetail = lazy(() =>
  import('@/features/ap/BillDetail').then((m) => ({ default: m.BillDetail })),
)
const NewBill = lazy(() => import('@/features/ap/NewBill').then((m) => ({ default: m.NewBill })))
const ApAging = lazy(() => import('@/features/ap/ApAging').then((m) => ({ default: m.ApAging })))
const BankAccounts = lazy(() =>
  import('@/features/bank/BankAccounts').then((m) => ({ default: m.BankAccounts })),
)
const BankReconcile = lazy(() =>
  import('@/features/bank/BankReconcile').then((m) => ({ default: m.BankReconcile })),
)
const TaxReport = lazy(() =>
  import('@/features/tax/TaxReport').then((m) => ({ default: m.TaxReport })),
)
const FixedAssets = lazy(() =>
  import('@/features/assets/FixedAssets').then((m) => ({ default: m.FixedAssets })),
)
const Deferrals = lazy(() =>
  import('@/features/assets/Deferrals').then((m) => ({ default: m.Deferrals })),
)
const Promotions = lazy(() =>
  import('@/features/promotions/Promotions').then((m) => ({ default: m.Promotions })),
)
const EarnRulesPage = lazy(() =>
  import('@/features/loyalty/EarnRulesPage').then((m) => ({ default: m.EarnRulesPage })),
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
  const { t } = useTranslation()
  const auth = useAuth()
  const fired = useRef(false)
  useEffect(() => {
    if (fired.current) return
    fired.current = true
    auth.login()
  }, [auth])
  // Branded splash for the second or two before the IdP redirect lands.
  return (
    <div className="grid min-h-screen place-items-center bg-paper">
      <div className="flex flex-col items-center gap-4">
        <span className="grid size-14 place-items-center rounded-2xl bg-gradient-to-br from-brand-600 to-brand-800 shadow-md">
          <BrandMark size={28} stroke="white" strokeWidth={2.4} />
        </span>
        <span className="font-display text-lg font-extrabold tracking-[-0.02em] text-ink">
          {t('app.name')}
        </span>
        <span className="flex items-center gap-2 text-sm text-ink-3">
          <Spinner />
          {t('common.loading')}
        </span>
      </div>
    </div>
  )
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
  // Per-login page grants (owner/manager bypass; others fetch /me/pages). Called unconditionally
  // (hooks rule); its result is only consulted once we reach the authenticated routing below.
  const pageAccess = usePageAccess()

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

  // Authenticated → wait for the signed-in company AND the page grants to load, then route.
  if (loading || !pageAccess.ready) {
    return <FullScreenSpinner />
  }

  const canDashboard = hasAnyRole(auth.roles, 'owner', 'manager')
  const canPos = hasAnyRole(auth.roles, 'owner', 'manager', 'cashier')
  const canEmployee = hasAnyRole(auth.roles, 'employee')

  if (!canDashboard && !canPos && !canEmployee) {
    return (
      <Suspense fallback={<CenteredSpinner />}>
        <AccessDenied />
      </Suspense>
    )
  }

  // POS surfaces additionally honour the per-login page grants (a cashier may keep the POS but
  // lose the kitchen display, etc.). /me is the always-available floor — never page-gated, so a
  // login can never be locked out of everything.
  const posAllowed = canPos && pageAccess.isAllowed('pos')
  const menuAllowed = canPos && pageAccess.isAllowed('menu')
  const kitchenAllowed = canPos && pageAccess.isAllowed('kitchen')
  const dashboardAllowed = canDashboard && pageAccess.isAllowed('dashboard')
  const reportsAllowed = canDashboard && pageAccess.isAllowed('reports')
  const orgAllowed = canDashboard && pageAccess.isAllowed('org')
  const groupsAllowed = canDashboard && pageAccess.isAllowed('groups')
  const closeAllowed = canDashboard && pageAccess.isAllowed('close')
  const teamAllowed = canDashboard && pageAccess.isAllowed('team')

  // Land on the FIRST page the login can actually open — avoids a redirect loop when a grant hides
  // the login's natural landing page. /me is the always-available floor.
  const home = dashboardAllowed
    ? '/'
    : reportsAllowed
      ? '/statements/income'
      : orgAllowed
        ? '/org'
        : groupsAllowed
          ? '/groups'
          : closeAllowed
            ? '/close'
            : teamAllowed
              ? '/team'
              : posAllowed
                ? '/pos'
                : menuAllowed
                  ? '/menu'
                  : kitchenAllowed
                    ? '/kitchen'
                    : '/me'

  return (
    <Suspense fallback={<CenteredSpinner />}>
      <Routes>
        {/* The POS is a full-screen "front office" — it renders OUTSIDE the sidebar/topbar shell.
            PosSwitch picks the per-vertical surface (restaurant Pos vs carwash ServicePos). */}
        {posAllowed && <Route path="/pos" element={<PosSwitch />} />}
        {menuAllowed && <Route path="/menu" element={<MenuManagement />} />}
        {/* /catalog is the carwash counterpart of /menu — gated identically (menuAllowed). */}
        {menuAllowed && <Route path="/catalog" element={<CatalogSwitch />} />}
        {kitchenAllowed && <Route path="/kitchen" element={<Kitchen />} />}

        {/* The employee self-service surface — full-screen, any business role may open it. */}
        <Route path="/me" element={<Me />} />

        {/* Everything else shares the back-office shell, mounted once via a layout route. */}
        <Route
          element={
            <Shell>
              <Outlet />
            </Shell>
          }
        >
          {canDashboard && <Route path="/onboarding" element={<OnboardingWizard />} />}
          {dashboardAllowed && (
            <Route
              path="/"
              element={company ? <Dashboard /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {reportsAllowed && (
            <Route
              path="/statements/income"
              element={company ? <IncomeStatement /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {reportsAllowed && (
            <Route
              path="/statements/balance-sheet"
              element={company ? <BalanceSheet /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {reportsAllowed && (
            <Route
              path="/statements/cash-flow"
              element={company ? <CashFlow /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/budgets"
              element={company ? <Budgets /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/budgets/:id"
              element={company ? <BudgetDetail /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/assets"
              element={company ? <FixedAssets /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/deferrals"
              element={company ? <Deferrals /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/promotions"
              element={company ? <Promotions /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/loyalty"
              element={company ? <EarnRulesPage /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/invoices"
              element={company ? <InvoicesList /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/invoices/new"
              element={company ? <NewInvoice /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/invoices/:id"
              element={company ? <InvoiceDetail /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/customers"
              element={company ? <Customers /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/ar/aging"
              element={company ? <ArAging /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/bills"
              element={company ? <BillsList /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/bills/new"
              element={company ? <NewBill /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/bills/:id"
              element={company ? <BillDetail /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/vendors"
              element={company ? <Vendors /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/ap/aging"
              element={company ? <ApAging /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/bank"
              element={company ? <BankAccounts /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/bank/:id"
              element={company ? <BankReconcile /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {canDashboard && (
            <Route
              path="/tax"
              element={company ? <TaxReport /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {orgAllowed && (
            <Route
              path="/org"
              element={company ? <OrgTree /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {orgAllowed && (
            <Route
              path="/org/:unitId"
              element={company ? <OrgUnitDetail /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {groupsAllowed && (
            <Route
              path="/groups"
              element={company ? <GroupConsolidation /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {closeAllowed && (
            <Route
              path="/close"
              element={company ? <PeriodClose /> : <Navigate to="/onboarding" replace />}
            />
          )}
          {teamAllowed && (
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
