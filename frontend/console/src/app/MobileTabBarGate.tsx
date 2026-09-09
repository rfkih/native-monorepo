/**
 * MobileTabBarGate — the single mount point of the phone bottom navigation (Native Console
 * Android design). Decides three things:
 *
 *  1. WHERE the bar exists: only below the 640px phone cutoff, and never on the full-screen
 *     till surfaces (`shouldMountTabBar`) — the POS has its own phone chrome (ADR 0043).
 *  2. WHICH persona's tabs render — derived from the same role ∧ grant ∧ tier gates the Shell
 *     nav uses, never invented: owner/manager get the back-office tabs, an employee-only login
 *     gets the self-service tabs, a cashier-only login gets no bar (all their surfaces are
 *     excluded paths).
 *
 * It used to own the "More" sheet's lifecycle too. More is a ROUTE now (ADR 0078), so the sheet
 * state, and the two overlays it launched, moved to the page that offers them.
 *
 * Tabs that a grant or tier hides simply drop; the home tab always targets App's `home`
 * cascade, so a revoked landing page never yields a dead tab.
 */
import { useTranslation } from 'react-i18next'
import { useLocation } from 'react-router-dom'
import {
  CalendarDays,
  FileText,
  House,
  ChartNoAxesColumn,
  Menu,
  ReceiptText,
  UsersRound,
  Wallet,
} from 'lucide-react'
import { MobileTabBar, type MobileTab } from '@/components/mobile/MobileTabBar'
import { useIsPhone } from '@/components/mobile/useIsPhone'
import { effectiveRoles, hasAnyRole, useAuth } from '@/lib/authContext'
import { usePageAccess } from '@/lib/pageAccess'
import { useTierAccess } from '@/lib/featureTier'
import { canFinance, canHr, canOps, canPayroll, canReports } from '@/lib/rolePreset'
import { OFFICE_TAB_ROUTES, officeMiddleTabs, shouldMountTabBar, type OfficeTabKey } from './tabBarPolicy'

export function MobileTabBarGate({ home }: { home: string }) {
  const { t } = useTranslation()
  const isPhone = useIsPhone()
  const { pathname } = useLocation()
  const auth = useAuth()
  const pageAccess = usePageAccess()
  const tierAccess = useTierAccess()
  if (!isPhone || !shouldMountTabBar(pathname)) return null

  // ADR 0049 P3b — the back-office persona reads the MERGED (elevated) roles, mirroring App.tsx's
  // per-capability booleans: an elevated device terminal on phone width gets the office tab set,
  // not the bare cashier one. Byte-identical for a normal `user` login (elevatedRoles is always
  // `[]`). Preset role-based access model Phase 2 — `officeOk` is the union of every non-POS
  // capability (owner/manager/accountant/hr all reach SOME Shell-wrapped page on phone and need a
  // way back to it; Shell's own hamburger button is hidden below the phone cutoff, so the tab
  // bar's "More" entry is their ONLY navigation at this width).
  const roles = effectiveRoles(auth.roles, auth.elevatedRoles)
  const opsOk = canOps(roles)
  const officeOk = opsOk || canReports(roles) || canFinance(roles) || canHr(roles) || canPayroll(roles)
  const canEmployee = hasAnyRole(auth.roles, 'employee')

  let tabs: MobileTab[]
  if (officeOk) {
    // Which of the four candidates this login can actually open — the same role ∧ grant ∧ tier
    // test each route already enforces. `officeMiddleTabs` owns the ORDER, the two-slot cap and
    // the "never duplicate the home tab" rule (tabBarPolicy, unit-tested).
    // Receivables/payables ageing have no PageKey of their own (they are not separately
    // grantable), so role + tier is their whole gate.
    const financeTier = canFinance(roles) && tierAccess.allows('accounting')
    const middle = officeMiddleTabs(
      {
        reports: canReports(roles) && pageAccess.isAllowed('reports') && tierAccess.allows('statements'),
        team: opsOk && pageAccess.isAllowed('team') && tierAccess.allows('team'),
        ar: financeTier,
        ap: financeTier,
      },
      home,
    )
    const MIDDLE_TAB: Record<OfficeTabKey, Omit<MobileTab, 'key'>> = {
      reports: { label: t('mobile.tabs.reports'), icon: ChartNoAxesColumn, to: OFFICE_TAB_ROUTES.reports },
      team: { label: t('mobile.tabs.team'), icon: UsersRound, to: OFFICE_TAB_ROUTES.team },
      ar: { label: t('mobile.tabs.receivables'), icon: ReceiptText, to: OFFICE_TAB_ROUTES.ar },
      ap: { label: t('mobile.tabs.payables'), icon: FileText, to: OFFICE_TAB_ROUTES.ap },
    }
    tabs = [
      { key: 'home', label: t('mobile.tabs.home'), icon: House, to: home, end: true },
      ...middle.map((key) => ({ key, ...MIDDLE_TAB[key] })),
      // A route, not an action (ADR 0078) — More is a destination, so it gets a history entry and
      // keeps its scroll position instead of self-dismissing on every use.
      { key: 'more', label: t('mobile.tabs.more'), icon: Menu, to: '/more' },
    ]
  } else if (canEmployee) {
    tabs = [
      { key: 'home', label: t('mobile.tabs.home'), icon: House, to: '/me', end: true },
      { key: 'payslips', label: t('mobile.tabs.payslips'), icon: ReceiptText, to: '/me/payslips' },
      { key: 'timeoff', label: t('mobile.tabs.timeoff'), icon: CalendarDays, to: '/me/timeoff' },
      { key: 'claims', label: t('mobile.tabs.claims'), icon: Wallet, to: '/me/expenses' },
    ]
  } else {
    // POS-only (cashier/chef/waitress) — every surface they own is an excluded path; no bar.
    return null
  }

  return (
    <>
      {/* Normal-flow spacer so the last content clears the fixed 80px bar. */}
      <div aria-hidden className="h-20 print:hidden" />
      <MobileTabBar tabs={tabs} />
    </>
  )
}
