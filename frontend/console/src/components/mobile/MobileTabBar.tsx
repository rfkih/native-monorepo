/**
 * MobileTabBar — the phone bottom navigation (Native Console Android design).
 * Stateless: tabs arrive already translated and already filtered by role ∧ grant ∧ tier
 * (MobileTabBarGate owns that policy). Route tabs derive their active state from the
 * location; action tabs (the "More" sheet) pass `active` explicitly.
 *
 * A fixed element bypasses the body's safe-area padding (index.css), so the bar carries
 * its own safe-area-inset-bottom.
 */
import type { ComponentType } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useLocation } from 'react-router-dom'
import type { LucideProps } from 'lucide-react'
import { cn } from '@/lib/cn'

export interface MobileTab {
  key: string
  /** Already-translated label. */
  label: string
  icon: ComponentType<LucideProps>
  /** Either a route… */
  to?: string
  /** Exact-match active (the home tab, so `/` or `/me` doesn't light up everywhere). */
  end?: boolean
  /** …or an action (opens the More sheet). */
  onSelect?: () => void
  /** Active override for action tabs (e.g. while the sheet is open). */
  active?: boolean
}

function TabInner({ tab, active }: { tab: MobileTab; active: boolean }) {
  const Icon = tab.icon
  return (
    <>
      <span
        className={cn(
          'grid h-[30px] w-[60px] place-items-center rounded-full transition-colors',
          active ? 'bg-emerald-tint text-emerald-2' : 'text-ink-3',
        )}
      >
        <Icon className="size-[22px]" strokeWidth={active ? 2.1 : 1.8} aria-hidden />
      </span>
      <span
        className={cn(
          'text-[11.5px] leading-none',
          active ? 'font-bold text-emerald-2' : 'font-medium text-ink-3',
        )}
      >
        {tab.label}
      </span>
    </>
  )
}

export function MobileTabBar({ tabs }: { tabs: MobileTab[] }) {
  const { t } = useTranslation()
  const { pathname } = useLocation()

  const isActive = (tab: MobileTab) => {
    if (tab.to == null) return tab.active === true
    return tab.end ? pathname === tab.to : pathname === tab.to || pathname.startsWith(tab.to + '/')
  }

  const tabClass =
    'flex h-full flex-col items-center justify-center gap-[3px] focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald'

  return (
    <nav
      aria-label={t('mobile.tabs.aria')}
      className="fixed inset-x-0 bottom-0 z-30 border-t border-line bg-surface print:hidden"
      style={{ paddingBottom: 'var(--safe-area-inset-bottom, 0px)' }}
    >
      <div className="grid h-20 auto-cols-fr grid-flow-col items-stretch pt-1.5 pb-3">
        {tabs.map((tab) => {
          const active = isActive(tab)
          return tab.to != null ? (
            <Link
              key={tab.key}
              to={tab.to}
              aria-current={active ? 'page' : undefined}
              className={tabClass}
              onClick={() => window.scrollTo(0, 0)}
            >
              <TabInner tab={tab} active={active} />
            </Link>
          ) : (
            <button
              key={tab.key}
              type="button"
              aria-expanded={tab.active === true}
              onClick={tab.onSelect}
              className={tabClass}
            >
              <TabInner tab={tab} active={active} />
            </button>
          )
        })}
      </div>
    </nav>
  )
}
