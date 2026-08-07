/**
 * useNavGroups — the console's grouped navigation data, shared so the desktop sidebar/drawer and
 * the phone "More" sheet (MobileTabBarGate) render the exact same role ∧ grant ∧ tier-filtered
 * tree. The 29 destinations and their gating semantics are unchanged from the original Shell
 * list; the grouping follows the Native Console Web design (pattern 1a): nine collapsible groups
 * instead of two long lists.
 */
import type { ComponentType } from 'react'
import { useTranslation } from 'react-i18next'
import {
  ArrowLeftRight,
  BookOpen,
  Building2,
  CalendarCheck,
  CalendarClock,
  Clock,
  FileText,
  Gift,
  HandCoins,
  History,
  Landmark,
  Laptop,
  Layers,
  LayoutDashboard,
  LineChart,
  type LucideProps,
  Network,
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
import { hasAnyRole, useAuth } from '@/lib/authContext'
import { usePageAccess, type PageKey } from '@/lib/pageAccess'
import { useTierAccess, type FeatureKey } from '@/lib/featureTier'

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

  const canDashboard = hasAnyRole(auth.roles, 'owner', 'manager')
  const canPos = hasAnyRole(auth.roles, 'owner', 'manager', 'cashier')
  const isOwner = hasAnyRole(auth.roles, 'owner')
  const pageAccess = usePageAccess()
  const tierAccess = useTierAccess()

  // Grouped nav — the whole sidebar is dashboard-only; a cashier never mounts the Shell. Each item
  // that maps to a grantable page is hidden when the login's grants exclude it (owner bypasses).
  // Each item tagged with a `feature` is additionally hidden when the company's tier does not
  // unlock it (P1 tier-mode) — the two gates compose as an independent AND, see the drop-step
  // below. `dashboard` is FREE, so it is tagged but never actually hidden by tier (plan Risk 2).
  // Pattern 1a (Native Console Web design): the same 29 links, regrouped from two long lists into
  // nine collapsible groups of three-to-four so only one group is open at a time in the sidebar.
  const rawGroups: NavGroup[] = canDashboard
    ? [
        {
          key: 'summary',
          heading: t('nav.groupSummary'),
          icon: LayoutDashboard,
          items: [
            {
              to: '/',
              label: t('nav.dashboard'),
              icon: LayoutDashboard,
              end: true,
              page: 'dashboard',
              feature: 'dashboard',
            },
          ],
        },
        {
          key: 'reports',
          heading: t('nav.groupReports'),
          icon: LineChart,
          items: [
            {
              to: '/statements/income',
              label: t('nav.income'),
              icon: LineChart,
              page: 'reports',
              feature: 'statements',
            },
            {
              to: '/statements/balance-sheet',
              label: t('nav.balanceSheet'),
              icon: Scale,
              page: 'reports',
              feature: 'statements',
            },
            {
              to: '/statements/cash-flow',
              label: t('nav.cashFlow'),
              icon: ArrowLeftRight,
              page: 'reports',
              feature: 'statements',
            },
            {
              to: '/expenses',
              label: t('nav.expenses'),
              icon: Wallet,
              page: 'expenses',
              feature: 'expenses',
            },
          ],
        },
        {
          key: 'receivables',
          heading: t('nav.groupAr'),
          icon: Receipt,
          items: [
            { to: '/invoices', label: t('nav.invoices'), icon: Receipt, feature: 'accounting' },
            { to: '/customers', label: t('nav.customers'), icon: Users, feature: 'accounting' },
            { to: '/ar/aging', label: t('nav.arAging'), icon: Clock, feature: 'accounting' },
          ],
        },
        {
          key: 'payables',
          heading: t('nav.groupAp'),
          icon: FileText,
          items: [
            { to: '/bills', label: t('nav.bills'), icon: FileText, feature: 'accounting' },
            { to: '/vendors', label: t('nav.vendors'), icon: Truck, feature: 'accounting' },
            { to: '/ap/aging', label: t('nav.apAging'), icon: History, feature: 'accounting' },
          ],
        },
        {
          key: 'cashTax',
          heading: t('nav.groupCashTax'),
          icon: Landmark,
          items: [
            { to: '/bank', label: t('nav.bank'), icon: Landmark, feature: 'accounting' },
            { to: '/tax', label: t('nav.tax'), icon: Percent, feature: 'accounting' },
            {
              to: '/opening-balances',
              label: t('nav.openingBalances'),
              icon: BookOpen,
              feature: 'accounting',
            },
          ],
        },
        {
          key: 'sales',
          heading: t('nav.groupSales'),
          icon: Tag,
          items: [
            { to: '/promotions', label: t('nav.promotions'), icon: Tag, feature: 'promotions' },
            { to: '/loyalty', label: t('nav.loyalty'), icon: Gift, feature: 'promotions' },
            { to: '/channels', label: t('nav.channels'), icon: Radio, feature: 'channels' },
            {
              to: '/platform-settlements',
              label: t('nav.platformSettlements'),
              icon: HandCoins,
              feature: 'channels',
            },
          ],
        },
        {
          key: 'planning',
          heading: t('nav.groupPlanning'),
          icon: Target,
          items: [
            { to: '/budgets', label: t('nav.budget'), icon: Target, feature: 'accounting' },
            { to: '/assets', label: t('nav.assets'), icon: Laptop, feature: 'accounting' },
            {
              to: '/deferrals',
              label: t('nav.deferrals'),
              icon: CalendarClock,
              feature: 'accounting',
            },
          ],
        },
        {
          key: 'organization',
          heading: t('nav.groupOrg'),
          icon: Network,
          items: [
            { to: '/org', label: t('nav.org'), icon: Network, page: 'org', feature: 'orgStructure' },
            {
              to: '/groups',
              label: t('nav.groups'),
              icon: Layers,
              page: 'groups',
              feature: 'orgStructure',
            },
            {
              to: '/close',
              label: t('nav.close'),
              icon: CalendarCheck,
              page: 'close',
              feature: 'orgStructure',
            },
            { to: '/team', label: t('nav.team'), icon: UsersRound, page: 'team', feature: 'team' },
          ],
        },
        {
          key: 'settings',
          heading: t('nav.groupSettings'),
          icon: SlidersHorizontal,
          items: [
            { to: '/onboarding', label: t('nav.onboarding'), icon: Building2 },
            { to: '/settings/printer', label: t('nav.printer'), icon: Printer, feature: 'printer' },
            // Owner-only (ADR 0045) — a payments-integrity decision, not a plan-tier feature, so
            // deliberately UNTAGGED (no `page`, no `feature`) like the escape hatch below.
            ...(isOwner
              ? [{ to: '/settings/payments', label: t('nav.payments'), icon: QrCode }]
              : []),
            // The escape hatch (plan Risk 1): owner-only, and deliberately UNTAGGED (no `page`, no
            // `feature`) so it is never hidden by the grant or tier filters below — a FREE-tier
            // owner must always be able to find the toggle back to FULL.
            ...(isOwner
              ? [{ to: '/settings/features', label: t('nav.features'), icon: SlidersHorizontal }]
              : []),
          ],
        },
      ]
    : []

  // Drop items whose page is not granted OR whose tier is not unlocked, then drop any group left
  // empty. `visible = grant ∧ tier` here (role already gates the whole rawGroups tree above).
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

  return { groups, canDashboard, canPos, isOwner }
}
