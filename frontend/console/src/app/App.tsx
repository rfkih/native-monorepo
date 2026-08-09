import { Suspense, lazy, useEffect, useRef, useState } from 'react'
import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { LogOut } from 'lucide-react'
import { Shell } from '@/app/Shell'
import { TransitionedRoutes } from '@/app/TransitionedRoutes'
import { MobileTabBarGate } from '@/app/MobileTabBarGate'
import { SettingsChrome } from '@/components/SettingsChrome'
import { Spinner } from '@/components/ui/Spinner'
import { AppSkeleton, PosSkeleton } from '@/components/ui/Skeleton'
import { BrandMark, Wordmark } from '@/components/Wordmark'
import { OfflineBanner } from '@/features/pos/offline/OfflineBanner'
import { effectiveRoles, hasAnyRole, useAuth } from '@/lib/authContext'
import { isNativeShell } from '@/lib/escpos/transport'
import { usePageAccess } from '@/lib/pageAccess'
import { useSession } from '@/lib/session'

/**
 * Warms the POS route chunk as soon as a POS-capable login lands ANYWHERE — the lazy() split
 * otherwise makes the till's very first open pay the full chunk download + parse behind a
 * spinner (UX audit: >4.5 s cold first paint). Rendered conditionally (posAllowed), so a
 * back-office-only login still never fetches the POS code.
 */
function PrefetchPosChunk() {
  useEffect(() => {
    void import('@/features/servicepos/PosSwitch')
  }, [])
  return null
}

/**
 * Warms the lazy route chunks once the first screen has painted and the main thread is
 * idle — a route change then swaps instantly under the view transition instead of flashing
 * the Suspense spinner mid-navigation (the "not smooth" complaint on the Android app, where
 * there is deliberately no service worker to soften a cold chunk fetch).
 *
 * Two tiers: the HOT set immediately at idle (first screens a login opens), then EVERYTHING
 * else the login's roles can reach at a later idle — the whole app is ~1–2 MB of
 * immutable-cached chunks, fetched once per deploy, so trading that for zero mid-navigation
 * spinners is the right deal on a business device.
 */
function PrefetchRouteChunks({
  canDashboard,
  canPos,
}: {
  canDashboard: boolean
  canPos: boolean
}) {
  useEffect(() => {
    const warmHot = () => {
      void import('@/features/me/Me')
      void import('@/features/expenses/MyExpenses')
      void import('@/features/me/MePayslipsScreen')
      void import('@/features/me/MeTimeoffScreen')
      if (canDashboard) {
        void import('@/features/dashboard/Dashboard')
        void import('@/features/statements/IncomeStatement')
        void import('@/features/expenses/ExpensesHub')
        void import('@/features/team/Team')
        void import('@/features/close/PeriodClose')
      }
    }
    const warmRest = () => {
      if (canPos) {
        void import('@/features/menu/MenuManagement')
        void import('@/features/inventory/IngredientManagement')
        void import('@/features/servicepos/PosSwitch')
        void import('@/features/kitchen/Kitchen')
        void import('@/features/settings/PrinterSettings')
        void import('@/features/pos/StandaloneRegister')
        void import('@/features/stocktake/StandaloneStocktake')
      }
      if (canDashboard) {
        void import('@/features/statements/BalanceSheet')
        void import('@/features/statements/CashFlow')
        void import('@/features/budget/Budgets')
        void import('@/features/org/OrgTree')
        void import('@/features/org/OrgUnitDetail')
        void import('@/features/ar/Customers')
        void import('@/features/ar/InvoicesList')
        void import('@/features/ar/ArAging')
        void import('@/features/ap/Vendors')
        void import('@/features/ap/BillsList')
        void import('@/features/ap/ApAging')
        void import('@/features/bank/BankAccounts')
        void import('@/features/tax/TaxReport')
        void import('@/features/assets/FixedAssets')
        void import('@/features/promotions/Promotions')
        void import('@/features/channels/Channels')
      }
    }
    const w = window as Window & {
      requestIdleCallback?: (cb: () => void, opts?: { timeout: number }) => number
    }
    let restTimer: ReturnType<typeof setTimeout> | undefined
    if (typeof w.requestIdleCallback === 'function') {
      w.requestIdleCallback(warmHot, { timeout: 3000 })
      restTimer = setTimeout(() => w.requestIdleCallback!(warmRest, { timeout: 5000 }), 4000)
    } else {
      const hotTimer = setTimeout(warmHot, 1500)
      restTimer = setTimeout(warmRest, 5000)
      return () => {
        clearTimeout(hotTimer)
        clearTimeout(restTimer)
      }
    }
    return () => clearTimeout(restTimer)
  }, [canDashboard, canPos])
  return null
}

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
const IngredientManagement = lazy(() =>
  import('@/features/inventory/IngredientManagement').then((m) => ({
    default: m.IngredientManagement,
  })),
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
const AppWelcome = lazy(() =>
  import('@/features/landing/AppWelcome').then((m) => ({ default: m.AppWelcome })),
)
const Me = lazy(() => import('@/features/me/Me').then((m) => ({ default: m.Me })))
const PrinterSettings = lazy(() =>
  import('@/features/settings/PrinterSettings').then((m) => ({ default: m.PrinterSettings })),
)
const FeaturesSettings = lazy(() =>
  import('@/features/settings/FeaturesSettings').then((m) => ({ default: m.FeaturesSettings })),
)
const PaymentSettings = lazy(() =>
  import('@/features/payments/PaymentSettings').then((m) => ({ default: m.PaymentSettings })),
)
const MyExpenses = lazy(() =>
  import('@/features/expenses/MyExpenses').then((m) => ({ default: m.MyExpenses })),
)
const MePayslipsScreen = lazy(() =>
  import('@/features/me/MePayslipsScreen').then((m) => ({ default: m.MePayslipsScreen })),
)
const MeTimeoffScreen = lazy(() =>
  import('@/features/me/MeTimeoffScreen').then((m) => ({ default: m.MeTimeoffScreen })),
)
const ExpensesHub = lazy(() =>
  import('@/features/expenses/ExpensesHub').then((m) => ({ default: m.ExpensesHub })),
)
const CategoriesAdmin = lazy(() =>
  import('@/features/expenses/CategoriesAdmin').then((m) => ({ default: m.CategoriesAdmin })),
)
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
const Channels = lazy(() =>
  import('@/features/channels/Channels').then((m) => ({ default: m.Channels })),
)
const PlatformSettlements = lazy(() =>
  import('@/features/platform/PlatformSettlements').then((m) => ({
    default: m.PlatformSettlements,
  })),
)
const CustomerDisplay = lazy(() =>
  import('@/features/pos/display/CustomerDisplay').then((m) => ({ default: m.CustomerDisplay })),
)
const OpeningBalances = lazy(() =>
  import('@/features/openingBalances/OpeningBalances').then((m) => ({
    default: m.OpeningBalances,
  })),
)

function CenteredSpinner() {
  return (
    <div className="grid place-items-center py-24 text-brand-600">
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

/**
 * The /onboarding route decides its chrome ONCE, when it mounts: a first-ever company (no company
 * in the session yet) gets the standalone full-page wizard; adding another company keeps the shell.
 *
 * The decision is captured in one-shot state, NOT re-derived per render: creating the first company
 * sets the session company MID-FLOW (before the wizard's success panel), and a live condition would
 * remount the wizard at that instant — wiping the success state the user is looking at.
 */
function OnboardingRoute() {
  const { company } = useSession()
  const [firstRun] = useState(() => company == null)
  if (firstRun) {
    return <OnboardingStandalone />
  }
  return (
    <Shell>
      <Suspense fallback={<CenteredSpinner />}>
        <OnboardingWizard />
      </Suspense>
    </Shell>
  )
}

/**
 * First-run onboarding — the wizard is the ONLY thing on screen. A brand-new owner has no company
 * yet, so the shell's sidebar/topbar would be pure noise (every destination just redirects back
 * here). Minimal chrome: the wordmark for orientation and sign-out as the one escape hatch.
 */
function OnboardingStandalone() {
  const { t } = useTranslation()
  const auth = useAuth()
  return (
    <div className="min-h-screen bg-paper">
      <header className="mx-auto flex h-16 w-full max-w-[1100px] items-center justify-between px-5">
        <Wordmark />
        <button
          type="button"
          onClick={auth.logout}
          className={
            'flex items-center gap-2 rounded-lg px-2.5 py-1.5 text-sm font-semibold text-ink-3 ' +
            'transition-colors hover:text-ink focus-visible:outline-2 focus-visible:outline-brand-500'
          }
        >
          <LogOut className="size-4" />
          <span className="hidden sm:inline">{t('nav.logout')}</span>
        </button>
      </header>
      <main className="px-4 pb-16 pt-6 sm:pt-10">
        <Suspense fallback={<CenteredSpinner />}>
          <OnboardingWizard />
        </Suspense>
      </main>
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

  // The skeleton every boot gate below shows: shell-shaped normally, till-shaped when the entry
  // path is the POS (the Android till's cold start). The static index.html boot skeleton makes
  // the SAME pathname call (its inline script sets data-boot="pos"), so the pre-JS ghost, these
  // gates, and each surface's own data skeletons hand off without changing shape — boot reads as
  // ONE surface instead of skeleton → spinner → skeleton (the "not smooth first load" complaint).
  const bootFallback = pathname.startsWith('/pos') ? <PosSkeleton /> : <AppSkeleton />

  // Wait for the OIDC provider to resolve the session (silent restore or redirect callback).
  if (!auth.ready) {
    return bootFallback
  }

  // Unauthenticated → the public marketing site. Login is EXPLICIT: the landing "Sign in" button
  // and the /login route trigger the Keycloak redirect. A deep-link to any protected path lands
  // here (and the gateway independently rejects tokenless API calls), so guards still fail closed.
  if (!auth.authenticated) {
    return (
      <Suspense fallback={<CenteredSpinner />}>
        <TransitionedRoutes>
          {/* Inside the Android shell the marketing site reads wrong — a logged-out app
              opens on the branded welcome (sign in / create account), not a sales page. */}
          <Route path="/" element={isNativeShell() ? <AppWelcome /> : <Landing />} />
          <Route path="/login" element={<LoginLauncher />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </TransitionedRoutes>
      </Suspense>
    )
  }

  // Authenticated → wait for the signed-in company AND the page grants to load, then route.
  if (loading || !pageAccess.ready) {
    return bootFallback
  }

  // ADR 0049 P3b — the MERGED role set (outlet/base roles ∪ any personal elevation). A normal
  // `user` login always has `elevatedRoles = []`, so this is byte-identical to `auth.roles` there;
  // on an ELEVATED device terminal it additionally carries the elevation's owner/manager role,
  // lighting up every existing `{canDashboard && <Route/>}` block below with no per-route change.
  // `canPos`/`canEmployee` deliberately stay on the BASE `auth.roles` — elevation only ever ADDS
  // back-office reach, it never changes what the outlet credential itself can do.
  const roles = effectiveRoles(auth.roles, auth.elevatedRoles)
  const canDashboard = hasAnyRole(roles, 'owner', 'manager')
  const canPos = hasAnyRole(auth.roles, 'owner', 'manager', 'cashier')
  const canEmployee = hasAnyRole(auth.roles, 'employee')
  // The /settings/features escape hatch (P1 tier-mode) — owner-only, server-enforced too (the
  // org-service PUT independently re-checks the role); a manager token never sees the route mount.
  const isOwner = hasAnyRole(roles, 'owner')

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
  const expensesAllowed = canDashboard && pageAccess.isAllowed('expenses')

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
              : expensesAllowed
                ? '/expenses'
                : posAllowed
                  ? '/pos'
                  : menuAllowed
                    ? '/menu'
                    : kitchenAllowed
                      ? '/kitchen'
                    : '/me'

  return (
    <>
      {/* App-global, mounted once here (not inside Shell) so it is visible on every authenticated
          screen INCLUDING the full-screen POS surfaces, which render outside the dashboard shell
          (Phase 5 offline mode, ADR 0028). Renders nothing when there is nothing to say. */}
      <OfflineBanner />
      {posAllowed && <PrefetchPosChunk />}
      <PrefetchRouteChunks canDashboard={canDashboard} canPos={canPos} />
      <Suspense fallback={bootFallback}>
        <TransitionedRoutes>
          {/* The POS is a full-screen "front office" — it renders OUTSIDE the sidebar/topbar shell.
              PosSwitch picks the per-vertical surface (restaurant Pos vs carwash ServicePos). */}
          {posAllowed && <Route path="/pos" element={<PosSwitch />} />}
          {/* Phase 6 (ADR 0029): a second-screen, customer-facing view — same role gate as /pos
              itself (a display is meaningless without a POS terminal driving it). */}
          {posAllowed && <Route path="/pos/customer-display" element={<CustomerDisplay />} />}
          {menuAllowed && <Route path="/menu" element={<MenuManagement />} />}
          {/* Inventory (stock-item) catalog behind the stock opname (ADR 0046) — same product-
              management surface as /menu, gated identically. Internally "ingredient". */}
          {menuAllowed && <Route path="/inventory" element={<IngredientManagement />} />}
          {/* /catalog is the carwash counterpart of /menu — gated identically (menuAllowed). */}
          {menuAllowed && <Route path="/catalog" element={<CatalogSwitch />} />}
          {kitchenAllowed && <Route path="/kitchen" element={<Kitchen />} />}
  
          {/* The employee self-service surface — full-screen, any business role may open it. */}
          <Route path="/me" element={<Me />} />
          {/* My expenses (ADR 0030, Phase E6) — same gate as /me itself: never page-restricted, so
              a login can never be locked out of submitting its own reimbursement claims. */}
          <Route path="/me/expenses" element={<MyExpenses />} />
          {/* Phone-only section screens (Native Console Android) — same open gate as /me; at
              tablet width and up each bounces back to the inline /me sections. */}
          <Route path="/me/payslips" element={<MePayslipsScreen />} />
          <Route path="/me/timeoff" element={<MeTimeoffScreen />} />

          {/* Printer settings (ADR 0039) — a per-DEVICE thermal-printer pairing, so it is NOT
              page-gated: whoever sets up a till (cashier or manager) must be able to connect it.
              SettingsChrome: these pages render outside the Shell (cashiers never mount it), so
              they carry their own minimal back bar (UX audit: they were navigation dead-ends). */}
          <Route
            path="/settings/printer"
            element={
              <SettingsChrome>
                <PrinterSettings />
              </SettingsChrome>
            }
          />

          {/* Features toggle (P1 tier-mode, `~/.claude/plans/umkm-tier-mode.md`) — a sibling of
              /settings/printer, but OWNER-ONLY (flipping a company-wide setting is a real authz
              decision, unlike a per-device pairing): the route itself never mounts for a
              non-owner, mirroring the nav item's owner-only visibility in Shell.tsx. */}
          {isOwner && <Route path="/settings/features" element={<FeaturesSettings />} />}

          {/* QRIS payment settings (ADR 0045) — company-wide default + per-outlet mode override.
              Owner-only (a payments-integrity decision, not a plan-tier feature): mirrors
              /settings/features' registration exactly, one level below it. */}
          {isOwner && <Route path="/settings/payments" element={<PaymentSettings />} />}

          {/* Onboarding picks its chrome ONCE, on entry (see OnboardingRoute): first company →
              full-page standalone wizard (no shell to wander off into); adding another company →
              the normal shell around it. */}
          {canDashboard && <Route path="/onboarding" element={<OnboardingRoute />} />}
  
          {/* Everything else shares the back-office shell, mounted once via a layout route. */}
          <Route
            element={
              <Shell>
                <Outlet />
              </Shell>
            }
          >
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
                path="/channels"
                element={company ? <Channels /> : <Navigate to="/onboarding" replace />}
              />
            )}
            {canDashboard && (
              <Route
                path="/platform-settlements"
                element={company ? <PlatformSettlements /> : <Navigate to="/onboarding" replace />}
              />
            )}
            {canDashboard && (
              <Route
                path="/opening-balances"
                element={company ? <OpeningBalances /> : <Navigate to="/onboarding" replace />}
              />
            )}
            {expensesAllowed && (
              <Route
                path="/expenses"
                element={company ? <ExpensesHub /> : <Navigate to="/onboarding" replace />}
              />
            )}
            {expensesAllowed && (
              <Route
                path="/expenses/categories"
                element={company ? <CategoriesAdmin /> : <Navigate to="/onboarding" replace />}
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
        </TransitionedRoutes>
      </Suspense>
      {/* Phone bottom navigation (Native Console Android) — mounts below 640px on every
          authenticated non-till surface; persona and mount policy live inside the gate. */}
      <MobileTabBarGate home={home} />
    </>
  )
}
