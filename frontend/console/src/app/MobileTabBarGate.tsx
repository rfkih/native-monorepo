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
 *  3. The "More" sheet lifecycle (closes on navigation and when leaving phone width).
 *
 * Tabs that a grant or tier hides simply drop; the home tab always targets App's `home`
 * cascade, so a revoked landing page never yields a dead tab.
 */
import { Suspense, lazy, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useLocation } from 'react-router-dom'
import { CalendarDays, House, ChartNoAxesColumn, Menu, ReceiptText, UsersRound, Wallet } from 'lucide-react'
import { MobileTabBar, type MobileTab } from '@/components/mobile/MobileTabBar'
import { useIsPhone } from '@/components/mobile/useIsPhone'
import { hasAnyRole, useAuth } from '@/lib/authContext'
import { usePageAccess } from '@/lib/pageAccess'
import { useTierAccess } from '@/lib/featureTier'
import { MoreSheet } from './MoreSheet'
import { shouldMountTabBar } from './tabBarPolicy'

// Lazy — keeps the stocktake + POS menu API code out of the main chunk until the tile is used.
const StandaloneStocktake = lazy(() =>
  import('@/features/stocktake/StandaloneStocktake').then((m) => ({
    default: m.StandaloneStocktake,
  })),
)

export function MobileTabBarGate({ home }: { home: string }) {
  const { t } = useTranslation()
  const isPhone = useIsPhone()
  const { pathname } = useLocation()
  const auth = useAuth()
  const pageAccess = usePageAccess()
  const tierAccess = useTierAccess()
  // The sheet is "open" only at the pathname it was opened on and only at phone width —
  // navigation or a flip past the boundary closes it by derivation, no effects needed.
  const [moreAnchor, setMoreAnchor] = useState<string | null>(null)
  const moreOpen = isPhone && moreAnchor === pathname
  const [stocktakeOpen, setStocktakeOpen] = useState(false)

  if (!isPhone || !shouldMountTabBar(pathname)) return null

  const canDashboard = hasAnyRole(auth.roles, 'owner', 'manager')
  const canEmployee = hasAnyRole(auth.roles, 'employee')

  let tabs: MobileTab[]
  if (canDashboard) {
    const reportsTab =
      pageAccess.isAllowed('reports') && tierAccess.allows('statements') && home !== '/statements/income'
    const teamTab = pageAccess.isAllowed('team') && tierAccess.allows('team') && home !== '/team'
    tabs = [
      { key: 'home', label: t('mobile.tabs.home'), icon: House, to: home, end: true },
      ...(reportsTab
        ? [{ key: 'reports', label: t('mobile.tabs.reports'), icon: ChartNoAxesColumn, to: '/statements/income' }]
        : []),
      ...(teamTab ? [{ key: 'team', label: t('mobile.tabs.team'), icon: UsersRound, to: '/team' }] : []),
      {
        key: 'more',
        label: t('mobile.tabs.more'),
        icon: Menu,
        onSelect: () => setMoreAnchor(moreOpen ? null : pathname),
        active: moreOpen,
      },
    ]
  } else if (canEmployee) {
    tabs = [
      { key: 'home', label: t('mobile.tabs.home'), icon: House, to: '/me', end: true },
      { key: 'payslips', label: t('mobile.tabs.payslips'), icon: ReceiptText, to: '/me/payslips' },
      { key: 'timeoff', label: t('mobile.tabs.timeoff'), icon: CalendarDays, to: '/me/timeoff' },
      { key: 'claims', label: t('mobile.tabs.claims'), icon: Wallet, to: '/me/expenses' },
    ]
  } else {
    // Cashier-only — every surface they own is an excluded path; no bar.
    return null
  }

  const posAllowed =
    hasAnyRole(auth.roles, 'owner', 'manager', 'cashier') &&
    pageAccess.isAllowed('pos') &&
    tierAccess.allows('pos')

  return (
    <>
      {/* Normal-flow spacer so the last content clears the fixed 80px bar. */}
      <div aria-hidden className="h-20 print:hidden" />
      <MobileTabBar tabs={tabs} />
      {moreOpen ? (
        <MoreSheet
          onClose={() => setMoreAnchor(null)}
          onOpenStocktake={
            posAllowed
              ? () => {
                  setMoreAnchor(null)
                  setStocktakeOpen(true)
                }
              : undefined
          }
        />
      ) : null}
      {stocktakeOpen && isPhone ? (
        <Suspense fallback={null}>
          <StandaloneStocktake onClose={() => setStocktakeOpen(false)} />
        </Suspense>
      ) : null}
    </>
  )
}
