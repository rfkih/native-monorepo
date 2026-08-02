/**
 * HeaderBar.tsx — extracted VERBATIM from ServicePos.tsx (redesign P2, mechanical move only).
 */
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import {
  ArrowLeft,
  Car,
  Gift,
  LogOut,
  Moon,
  RefreshCw,
  Scissors,
  Settings,
  Store,
  Sun,
} from 'lucide-react'
import type { CompanySession } from '@/lib/session'
import { useTheme } from '@/lib/theme'
import { useAuth, hasAnyRole } from '@/lib/authContext'
import { OutletPicker } from '@/components/OutletPicker'
import type { } from '@/features/loyalty/api'
import type { VerticalPosConfig } from './../config'


// ---------------------------------------------------------------------------
// HeaderBar — simplified terminal chrome (title + outlet picker + utilities)
// ---------------------------------------------------------------------------

export function HeaderBar({
  config,
  session,
  offline,
  queuedCount,
  rejectedCount,
  onOpenGiftCardSell,
  onOpenSyncCenter,
}: {
  config: VerticalPosConfig
  session: CompanySession
  offline: boolean
  queuedCount: number
  rejectedCount: number
  onOpenGiftCardSell: () => void
  onOpenSyncCenter: () => void
}) {
  const { t } = useTranslation()
  const { theme, toggle } = useTheme()
  const auth = useAuth()
  const canDashboard = hasAnyRole(auth.roles, 'owner', 'manager')
  // Inline ternary (not a helper function) — mirrors VerticalComingSoon.tsx's icon selection so the
  // react-hooks/static-components lint rule can see the component reference is render-stable.
  const Icon = config.vertical === 'carwash' ? Car : config.vertical === 'barbershop' ? Scissors : Store

  return (
    <div className="flex h-16 shrink-0 items-center gap-2.5 border-b border-line bg-surface px-4 sm:px-6">
      {canDashboard ? (
        <Link
          to="/"
          aria-label={t('a11y.backToDashboard')}
          className="grid size-9 shrink-0 place-items-center rounded-xl border border-line text-ink-3 transition-all hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          <ArrowLeft className="size-4" />
        </Link>
      ) : null}

      <span className="hidden items-center gap-2 md:flex">
        <span className="grid size-8 shrink-0 place-items-center rounded-xl bg-gradient-to-br from-brand-500 to-brand-700 text-white">
          <Icon className="size-[16px]" />
        </span>
        <span className="hidden truncate font-display text-[15px] font-bold leading-tight text-ink lg:block">
          {session.name}
        </span>
      </span>

      <OutletPicker />

      <div className="flex-1" />

      <div className="flex items-center gap-1.5">
        {canDashboard ? (
          <Link
            to="/catalog"
            aria-label={t(`${config.i18nNs}.manageCatalog`)}
            title={t(`${config.i18nNs}.manageCatalog`)}
            className="grid size-10 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <Settings className="size-4" />
          </Link>
        ) : null}
        {/* Gift card sell — a distinct till action, not a cart line (ADR 0027); unreachable offline
            (Phase 5, ADR 0028). */}
        <button
          type="button"
          onClick={onOpenGiftCardSell}
          disabled={offline}
          aria-label={t('pos.loyalty.giftCard.sellTitle')}
          title={offline ? t('offline.disabled.giftCard') : t('pos.loyalty.giftCard.sellTitle')}
          className="grid size-10 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:border-line disabled:hover:bg-surface disabled:hover:text-ink-3"
        >
          <Gift className="size-4" aria-hidden="true" />
        </button>
        {/* Sync center (Phase 5, ADR 0028) — badge = queued + rejected */}
        <button
          type="button"
          onClick={onOpenSyncCenter}
          aria-label={t('offline.syncCenterButton')}
          title={t('offline.syncCenterButton')}
          className="relative grid size-10 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          <RefreshCw className="size-4" aria-hidden="true" />
          {queuedCount + rejectedCount > 0 ? (
            <span className="absolute -right-1 -top-1 grid h-4 min-w-4 place-items-center rounded-full bg-amber px-1 text-[9px] font-bold text-ink">
              {queuedCount + rejectedCount}
            </span>
          ) : null}
        </button>
        <button
          type="button"
          onClick={toggle}
          aria-label={t('a11y.toggleTheme')}
          className="grid size-10 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-emerald-line hover:bg-emerald-tint hover:text-emerald-2"
        >
          {theme === 'dark' ? <Sun className="size-4" /> : <Moon className="size-4" />}
        </button>
        <button
          type="button"
          onClick={auth.logout}
          aria-label={t('nav.logout')}
          className="grid size-10 shrink-0 place-items-center rounded-xl border border-line bg-surface text-ink-3 transition-all hover:border-tint-loss hover:bg-tint-loss hover:text-loss focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          <LogOut className="size-4" />
        </button>
        <span className="grid size-10 shrink-0 place-items-center rounded-full bg-emerald-tint font-semibold text-[13px] text-emerald-2">
          {session.name.slice(0, 2).toUpperCase()}
        </span>
      </div>
    </div>
  )
}
