/**
 * StaffShell — the phone chrome for the Native Karyawan staff app (ADR 0049 P5 redesign): a
 * scrollable content area over a fixed 4-tab bottom navigation (Beranda / Cuti / Klaim / Slip).
 * Wraps the four TAB routes as a react-router layout route; pushed screens (claim detail, profile)
 * render outside it with their own back header and no tab bar, matching the mockup.
 */
import { NavLink, Outlet } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { CalendarDays, Home, ReceiptText, Wallet } from 'lucide-react'
import { cn } from '@/lib/cn'

const TABS = [
  { to: '/me', end: true, icon: Home, key: 'home' },
  { to: '/me/timeoff', end: false, icon: CalendarDays, key: 'timeoff' },
  { to: '/me/expenses', end: false, icon: ReceiptText, key: 'claims' },
  { to: '/me/payslips', end: false, icon: Wallet, key: 'payslips' },
] as const

export function StaffShell() {
  return (
    <div className="min-h-[100dvh] bg-paper">
      {/* Content clears the fixed tab bar (bar height + the phone's home-indicator safe area). */}
      <div className="pb-[calc(66px+env(safe-area-inset-bottom))]">
        <Outlet />
      </div>
      <StaffTabBar />
    </div>
  )
}

function StaffTabBar() {
  const { t } = useTranslation()
  return (
    <nav className="fixed inset-x-0 bottom-0 z-40 border-t border-line bg-surface/95 pb-[env(safe-area-inset-bottom)] backdrop-blur">
      <div className="mx-auto flex h-[66px] max-w-md items-stretch">
        {TABS.map(({ to, end, icon: Icon, key }) => (
          <NavLink
            key={key}
            to={to}
            end={end}
            viewTransition
            className={({ isActive }) =>
              cn(
                'flex flex-1 flex-col items-center justify-center gap-1 text-[11px] font-semibold transition-colors focus-visible:outline-2 focus-visible:outline-brand-500',
                isActive ? 'text-brand-700' : 'text-ink-3',
              )
            }
          >
            {({ isActive }) => (
              <>
                <Icon className="size-[22px]" strokeWidth={isActive ? 2.4 : 1.9} aria-hidden />
                <span>{t(`staff.tabs.${key}`)}</span>
              </>
            )}
          </NavLink>
        ))}
      </div>
    </nav>
  )
}
