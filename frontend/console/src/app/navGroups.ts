/**
 * useNavGroups — the console's grouped navigation data, shared so the desktop sidebar/drawer and
 * the phone "More" sheet (MobileTabBarGate) render the exact same role ∧ grant ∧ tier-filtered
 * tree. The grouping follows the Native Console Web design (pattern 1a): collapsible groups
 * instead of two long lists.
 *
 * Preset role-based access model Phase 2: every group/item below is additionally gated by the
 * capability preset (`lib/rolePreset.ts`), which mirrors the gateway's `RoutingConfig` capability
 * arrays EXACTLY (`visible = role-preset ∧ grant ∧ tier`, an independent AND with the pre-existing
 * page-grant/tier filters — see the drop-step at the bottom of this file). Most groups map to a
 * single capability 1:1 (see each group's inline comment); a few groups mix items from TWO
 * capabilities because the underlying console PAGE itself calls both API surfaces (e.g. `/groups`
 * reads org-service's OPS-gated consolidation-group membership AND finance-service's FINANCE-gated
 * consolidated P&L) — those items are gated individually, not at the group level.
 */
import type { ComponentType } from 'react'
import { useTranslation } from 'react-i18next'
import {
  ArrowLeftRight,
  Banknote,
  BookOpen,
  Building2,
  CalendarCheck,
  CalendarClock,
  CalendarDays,
  Clock,
  FileText,
  Gift,
  HandCoins,
  History,
  IdCard,
  Landmark,
  Laptop,
  Layers,
  LayoutDashboard,
  LineChart,
  type LucideProps,
  Network,
  NotebookText,
  Package,
  Percent,
  Printer,
  QrCode,
  Radio,
  Receipt,
  Scale,
  SlidersHorizontal,
  Tag,
  Target,
  Truck,
  Users,
  UsersRound,
  Wallet,
} from 'lucide-react'
import { effectiveRoles, useAuth } from '@/lib/authContext'
import { usePageAccess, type PageKey } from '@/lib/pageAccess'
import { useTierAccess, type FeatureKey } from '@/lib/featureTier'
import { canFinance, canHr, canOps, canPayroll, canPos, canReports } from '@/lib/rolePreset'

export type Icon = ComponentType<LucideProps>
export type NavItem = {
  to: string
  label: string
  icon: Icon
  end?: boolean
  page?: PageKey
  /** P1 tier-mode: hidden in FREE unless the feature's minimum tier is FREE (lib/featureTier.ts).
   *  Composes with `page` as an independent AND — see the drop-step below. Omit for an item that
   *  is never tier-gated (e.g. the always-visible `/settings/features` escape hatch). */
  feature?: FeatureKey
}
/** `key` is the stable accordion identity (headings are translated); `icon` renders on the
 *  collapsible group head — leaves render label-only (Native Console Web design, pattern 1a). */
export type NavGroup = { key: string; heading: string; icon: Icon; items: NavItem[] }

export function useNavGroups(): {
  groups: NavGroup[]
  canDashboard: boolean
  canPos: boolean
  isOwner: boolean
} {
  const { t } = useTranslation()
  const auth = useAuth()

  // ADR 0049 P3b — mirrors App.tsx's per-capability booleans exactly (merged/elevated roles): this
  // is the SHARED source for both the desktop Shell sidebar and MoreSheet's phone nav list, so
  // without this an elevated device could open a back-office ROUTE directly but find no nav links
  // to reach any OTHER one. `canPos` deliberately stays on the BASE roles (unaffected by
  // elevation) — see rolePreset.ts's doc. Normal `user` login: byte-identical (`elevatedRoles` is
  // always `[]`).
  const roles = effectiveRoles(auth.roles, auth.elevatedRoles)
  const opsOk = canOps(roles)
  const reportsOk = canReports(roles)
  const financeOk = canFinance(roles)
  const hrOk = canHr(roles)
  const payrollOk = canPayroll(roles)
  const posOk = canPos(auth.roles)
  // `canDashboard` kept in the return shape for the (currently unused-by-consumers) shape parity —
  // it is exactly the OPS bundle: owner/manager, unchanged meaning from before Phase 2.
  const canDashboard = opsOk
  const isOwner = roles.includes('owner')
  const pageAccess = usePageAccess()
  const tierAccess = useTierAccess()
  const officeOk = opsOk || reportsOk || financeOk || hrOk || payrollOk

  // Grouped nav — the whole sidebar is back-office-only; a POS-only login (cashier/chef/waitress)
  // never mounts the Shell. Each item that maps to a grantable page is hidden when the login's
  // grants exclude it (owner bypasses). Each item tagged with a `feature` is additionally hidden
  // when the company's tier does not unlock it (P1 tier-mode) — the two gates compose as an
  // independent AND, see the drop-step below. `dashboard` is FREE, so it is tagged but never
  // actually hidden by tier (plan Risk 2).
  //
  // Preset role-based access model Phase 2: every group is ALSO gated by its capability (see the
  // file header) — most groups map 1:1 to a single capability; `reports`/`sales`/`organization`
  // mix items from two capabilities because their underlying PAGE calls two API surfaces, so those
  // specific items carry their OWN capability check instead of gating the whole group.
  const rawGroups: NavGroup[] = officeOk
    ? [
        {
          key: 'summary',
          heading: t('nav.groupSummary'),
          icon: LayoutDashboard,
          // OPS (owner/manager dashboard).
          items: opsOk
            ? [
                {
                  to: '/',
                  label: t('nav.dashboard'),
                  icon: LayoutDashboard,
                  end: true,
                  page: 'dashboard' as const,
                  feature: 'dashboard' as const,
                },
              ]
            : [],
        },
        {
          // POS catalog — the menu (items + prices) and the stock-item inventory an owner edits from
          // the desktop console. On a phone these are the MoreSheet's quick-access TILES, so this
          // group is filtered OUT of MoreSheet's "all pages" list to avoid showing them twice — the
          // desktop sidebar (which has no such tiles) is the surface this group exists for. Gate
          // mirrors the /menu + /inventory routes exactly: POS capability ∧ `menu` grant ∧ `products`
          // tier (see App.tsx `menuAllowed` and MoreSheet's tiles).
          key: 'catalog',
          heading: t('nav.groupCatalog'),
          icon: Package,
          items: posOk
            ? [
                {
                  to: '/menu',
                  label: t('nav.menu'),
                  icon: NotebookText,
                  page: 'menu' as const,
                  feature: 'products' as const,
                },
                {
                  to: '/inventory',
                  label: t('nav.inventory'),
                  icon: Package,
                  page: 'menu' as const,
                  feature: 'products' as const,
                },
              ]
            : [],
        },
        {
          key: 'reports',
          heading: t('nav.groupReports'),
          icon: LineChart,
          items: [
            // REPORTS — statements (owner/manager/accountant).
            ...(reportsOk
              ? [
                  {
                    to: '/statements/income',
                    label: t('nav.income'),
                    icon: LineChart,
                    page: 'reports' as const,
                    feature: 'statements' as const,
                  },
                  {
                    to: '/statements/balance-sheet',
                    label: t('nav.balanceSheet'),
                    icon: Scale,
                    page: 'reports' as const,
                    feature: 'statements' as const,
                  },
                  {
                    to: '/statements/cash-flow',
                    label: t('nav.cashFlow'),
                    icon: ArrowLeftRight,
                    page: 'reports' as const,
                    feature: 'statements' as const,
                  },
                ]
              : []),
            // Expense CLAIMS hit the HR-gated `/api/v1/expense-claims/**` at the gateway (owner/
            // manager/hr) — NOT the REPORTS bundle, despite living in this group visually.
            ...(hrOk
              ? [
                  {
                    to: '/expenses',
                    label: t('nav.expenses'),
                    icon: Wallet,
                    page: 'expenses' as const,
                    feature: 'expenses' as const,
                  },
                ]
              : []),
          ],
        },
        {
          key: 'receivables',
          heading: t('nav.groupAr'),
          icon: Receipt,
          // FINANCE (owner/accountant).
          items: financeOk
            ? [
                { to: '/invoices', label: t('nav.invoices'), icon: Receipt, feature: 'accounting' },
                { to: '/customers', label: t('nav.customers'), icon: Users, feature: 'accounting' },
                { to: '/ar/aging', label: t('nav.arAging'), icon: Clock, feature: 'accounting' },
              ]
            : [],
        },
        {
          key: 'payables',
          heading: t('nav.groupAp'),
          icon: FileText,
          // FINANCE (owner/accountant).
          items: financeOk
            ? [
                { to: '/bills', label: t('nav.bills'), icon: FileText, feature: 'accounting' },
                { to: '/vendors', label: t('nav.vendors'), icon: Truck, feature: 'accounting' },
                { to: '/ap/aging', label: t('nav.apAging'), icon: History, feature: 'accounting' },
              ]
            : [],
        },
        {
          key: 'cashTax',
          heading: t('nav.groupCashTax'),
          icon: Landmark,
          // FINANCE (owner/accountant).
          items: financeOk
            ? [
                { to: '/bank', label: t('nav.bank'), icon: Landmark, feature: 'accounting' },
                { to: '/tax', label: t('nav.tax'), icon: Percent, feature: 'accounting' },
                {
                  to: '/opening-balances',
                  label: t('nav.openingBalances'),
                  icon: BookOpen,
                  feature: 'accounting',
                },
              ]
            : [],
        },
        {
          key: 'sales',
          heading: t('nav.groupSales'),
          icon: Tag,
          items: [
            // OPS — promotions/loyalty/channels administration (owner/manager).
            ...(opsOk
              ? [
                  { to: '/promotions', label: t('nav.promotions'), icon: Tag, feature: 'promotions' as const },
                  { to: '/loyalty', label: t('nav.loyalty'), icon: Gift, feature: 'promotions' as const },
                  { to: '/channels', label: t('nav.channels'), icon: Radio, feature: 'channels' as const },
                ]
              : []),
            // Platform settlements post money (Dr cash-clearing/fee, Cr platform-receivable) — the
            // gateway gates it FINANCE (owner/accountant), not OPS, despite living in this group.
            ...(financeOk
              ? [
                  {
                    to: '/platform-settlements',
                    label: t('nav.platformSettlements'),
                    icon: HandCoins,
                    feature: 'channels' as const,
                  },
                ]
              : []),
          ],
        },
        {
          key: 'planning',
          heading: t('nav.groupPlanning'),
          icon: Target,
          // FINANCE (owner/accountant).
          items: financeOk
            ? [
                { to: '/budgets', label: t('nav.budget'), icon: Target, feature: 'accounting' },
                { to: '/assets', label: t('nav.assets'), icon: Laptop, feature: 'accounting' },
                {
                  to: '/deferrals',
                  label: t('nav.deferrals'),
                  icon: CalendarClock,
                  feature: 'accounting',
                },
              ]
            : [],
        },
        {
          key: 'organization',
          heading: t('nav.groupOrg'),
          icon: Network,
          items: [
            // OPS — org structure + team/logins + consolidation-group membership (owner/manager).
            // `/groups` lives here (not with the finance items below) because its BACKBONE — the
            // group list + membership — reads/writes org-service's OPS-gated /consolidation-groups;
            // an accountant (finance, not OPS) can't even load that list, so must NOT be routed here.
            // The consolidated FIGURES + close inside the page ARE finance and are gated to
            // owner/accountant WITHIN the component (canViewConsolidation); a manager manages
            // membership and sees a "finance access" note instead of the numbers (ADR 0052).
            ...(opsOk
              ? [
                  {
                    to: '/org',
                    label: t('nav.org'),
                    icon: Network,
                    page: 'org' as const,
                    feature: 'orgStructure' as const,
                  },
                  {
                    to: '/team',
                    label: t('nav.team'),
                    icon: UsersRound,
                    page: 'team' as const,
                    feature: 'team' as const,
                  },
                  {
                    to: '/groups',
                    label: t('nav.groups'),
                    icon: Layers,
                    page: 'groups' as const,
                    feature: 'orgStructure' as const,
                  },
                ]
              : []),
            // The period close posts/reads through finance-service — FINANCE (owner/accountant).
            ...(financeOk
              ? [
                  {
                    to: '/close',
                    label: t('nav.close'),
                    icon: CalendarCheck,
                    page: 'close' as const,
                    feature: 'orgStructure' as const,
                  },
                ]
              : []),
          ],
        },
        {
          key: 'people',
          heading: t('nav.groupPeople'),
          icon: IdCard,
          // HR (owner/manager/hr) — employee records + payroll + leave/overtime. `/people` mounts the
          // standalone PeoplePage (which resolves its own default business unit via useOrgUnits and
          // never calls the OPS-gated /users). Payroll is additionally gated PAYROLL (owner/hr only)
          // so a manager sees People-minus-payroll, matching the gateway exactly.
          items: hrOk
            ? [
                {
                  to: '/people?tab=employees',
                  label: t('nav.employees'),
                  icon: Users,
                  feature: 'hr' as const,
                },
                ...(payrollOk
                  ? [
                      {
                        to: '/people?tab=payroll',
                        label: t('nav.payroll'),
                        icon: Banknote,
                        feature: 'hr' as const,
                      },
                    ]
                  : []),
                {
                  to: '/people?tab=attendance',
                  label: t('nav.attendance'),
                  icon: CalendarDays,
                  feature: 'hr' as const,
                },
              ]
            : [],
        },
        {
          key: 'settings',
          heading: t('nav.groupSettings'),
          icon: SlidersHorizontal,
          // OPS (owner/manager) — company/device settings; owner-only items stay further gated by
          // `isOwner` beneath that, as before.
          items: opsOk
            ? [
                { to: '/onboarding', label: t('nav.onboarding'), icon: Building2 },
                { to: '/settings/printer', label: t('nav.printer'), icon: Printer, feature: 'printer' },
                // Owner-only (ADR 0045) — a payments-integrity decision, not a plan-tier feature, so
                // deliberately UNTAGGED (no `page`, no `feature`) like the escape hatch below.
                ...(isOwner
                  ? [{ to: '/settings/payments', label: t('nav.payments'), icon: QrCode }]
                  : []),
                // The escape hatch (plan Risk 1): owner-only, and deliberately UNTAGGED (no `page`,
                // no `feature`) so it is never hidden by the grant or tier filters below — a
                // FREE-tier owner must always be able to find the toggle back to FULL.
                ...(isOwner
                  ? [{ to: '/settings/features', label: t('nav.features'), icon: SlidersHorizontal }]
                  : []),
              ]
            : [],
        },
      ]
    : []

  // Drop items whose page is not granted OR whose tier is not unlocked, then drop any group left
  // empty. `visible = grant ∧ tier` here (role-preset capability already gates each item above).
  const groups: NavGroup[] = rawGroups
    .map((g) => ({
      ...g,
      items: g.items.filter(
        (it) =>
          (!it.page || pageAccess.isAllowed(it.page)) &&
          (!it.feature || tierAccess.allows(it.feature)),
      ),
    }))
    .filter((g) => g.items.length > 0)

  return { groups, canDashboard, canPos: posOk, isOwner }
}
