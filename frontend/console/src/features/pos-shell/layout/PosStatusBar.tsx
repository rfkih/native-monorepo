/**
 * PosStatusBar — the POS terminal's chrome band (redesign P4).
 *
 * A 56px INK-900 band in light theme (the one place the console goes dark — it frames the bright
 * catalog as the work area and reads as dedicated terminal hardware); in dark theme it sits on
 * the elevated surface with a hairline. Replaces the former 64px white utility row and its
 * 10-icon cluster: identity + outlet picker on the left, the always-visible connection pill in
 * the middle, and at most a few PINNED per-shift actions + the till-menu overflow on the right.
 *
 * pos-shell rule: stateless presentation. Every action, badge count, and the outlet picker come
 * in as props/slots — no hooks beyond i18n, no fetching.
 */
import { useTranslation } from 'react-i18next'
import { ArrowLeft, KeyRound, MoreVertical, Store, UserRound, Wifi, WifiOff } from 'lucide-react'
import { Link } from 'react-router-dom'
import { cn } from '@/lib/cn'

export interface StatusBarAction {
  key: string
  icon: React.ReactNode
  /** Already-translated label (callers own their i18n namespaces). */
  label: string
  onClick: () => void
  disabled?: boolean
  /** Tooltip while disabled (e.g. the offline reason). */
  disabledTitle?: string
  badge?: { count: number; tone: 'brand' | 'warning' } | null
  testId?: string
}

export function PosStatusBar({
  businessName,
  outletPicker,
  showBack = true,
  offline,
  queuedCount,
  onConnectionClick,
  pinned,
  onOverflowClick,
  overflowOpen,
  operator,
  showOperatorSignIn = false,
  onOperatorSignInClick,
}: {
  businessName: string
  /** The OutletPicker element (a stateful cross-feature component — slotted in, not imported). */
  outletPicker: React.ReactNode
  /** Service cashiers have no dashboard access — their band hides the back link (role gate). */
  showBack?: boolean
  offline: boolean
  /** Queued + rejected offline sales — shown inside the connection pill. */
  queuedCount: number
  /** Opens the sync center — the pill is the one always-visible connection affordance. */
  onConnectionClick: () => void
  /** Max ~3 per-shift actions (tables, parked …). Everything else lives in the till menu. */
  pinned: StatusBarAction[]
  onOverflowClick: () => void
  overflowOpen: boolean
  /**
   * ADR 0049 P3b — the signed-in operator on a Business-app device terminal (name + role, rule 6).
   * Undefined/null on a normal `user` login, where this whole affordance never renders.
   */
  operator?: { displayName: string; role: string } | null
  /** True only on a device terminal with no operator signed in yet — renders the "Sign in"
   * affordance instead of the chip. */
  showOperatorSignIn?: boolean
  onOperatorSignInClick?: () => void
}) {
  const { t } = useTranslation()
  return (
    <div
      className={cn(
        'flex h-14 shrink-0 items-center gap-2.5 px-4 max-sm:gap-1.5 max-sm:px-2 sm:px-5',
        'bg-ink-900 text-white dark:border-b dark:border-line dark:bg-surface dark:text-ink',
      )}
    >
      {/* Back to dashboard (role-gated for service cashiers) */}
      {showBack ? (
        <Link
          to="/"
          aria-label={t('a11y.backToDashboard')}
          className="grid size-9 shrink-0 place-items-center rounded-xl text-white/60 transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-300 dark:text-ink-3 dark:hover:bg-hover dark:hover:text-ink"
        >
          <ArrowLeft className="size-4" />
        </Link>
      ) : null}

      {/* Identity (md+) */}
      <span className="hidden items-center gap-2 md:flex">
        <span className="grid size-8 shrink-0 place-items-center rounded-xl bg-gradient-to-br from-brand-500 to-brand-700 text-white">
          <Store className="size-4" />
        </span>
        <span className="hidden max-w-[160px] truncate font-display text-[14px] font-bold leading-tight lg:block">
          {businessName}
        </span>
      </span>

      {/* Outlet picker (slotted) — the ONLY shrinkable element on phone: everything to its
          right (connection pill, pinned actions, the overflow button) is shrink-0, so a long
          outlet name must compress instead of pushing the overflow trigger off a 360px screen
          (owner report: the till menu was unreachable on the S23). */}
      <div className="min-w-0 shrink">{outletPicker}</div>

      <div className="min-w-0 flex-1" />

      {/* Connection pill — the terminal's always-visible online/offline state (ADR 0028). */}
      <button
        type="button"
        onClick={onConnectionClick}
        data-testid="pos-connection-pill"
        aria-label={t('offline.syncCenterButton')}
        className={cn(
          'flex h-9 shrink-0 items-center gap-1.5 rounded-full px-3 text-[12px] font-semibold transition-colors',
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-300',
          offline || queuedCount > 0
            ? 'bg-warning/15 text-warning ring-1 ring-inset ring-warning/40'
            : 'text-white/50 ring-1 ring-inset ring-white/15 hover:bg-white/10 hover:text-white/80 dark:text-ink-3 dark:ring-line dark:hover:bg-hover',
        )}
      >
        {offline ? <WifiOff className="size-3.5" aria-hidden /> : <Wifi className="size-3.5" aria-hidden />}
        <span className="hidden sm:inline">
          {offline
            ? t('posShell.offlineQueued', { n: queuedCount })
            : queuedCount > 0
              ? t('posShell.onlineQueued', { n: queuedCount })
              : t('posShell.online')}
        </span>
        {queuedCount > 0 ? (
          <span className="grid h-4 min-w-4 place-items-center rounded-full bg-warning px-1 text-[9px] font-bold text-ink sm:hidden">
            {queuedCount}
          </span>
        ) : null}
      </button>

      {/* ADR 0049 P3b — the signed-in operator (device terminal only). Mirrors the connection
          pill's compact-on-phone idiom (icon-only below sm, icon + text at sm+) so it stays a
          fixed, small shrink-0 element and never reintroduces the 360px overflow bug (the outlet
          picker above is the only element expected to shrink). */}
      {operator ? (
        <span
          data-testid="pos-operator-chip"
          title={`${operator.displayName} · ${operator.role}`}
          className="flex h-9 shrink-0 items-center gap-1.5 rounded-full px-2.5 text-[12px] font-semibold text-white/70 ring-1 ring-inset ring-white/15 max-sm:px-2 dark:text-ink-2 dark:ring-line"
        >
          <UserRound className="size-3.5 shrink-0" aria-hidden="true" />
          <span className="hidden max-w-[110px] truncate sm:inline">
            {t('posShell.operatorChip', { name: operator.displayName, role: operator.role })}
          </span>
        </span>
      ) : showOperatorSignIn ? (
        <button
          type="button"
          onClick={onOperatorSignInClick}
          data-testid="pos-operator-signin"
          aria-label={t('posShell.operatorSignIn')}
          className={cn(
            'flex h-9 shrink-0 items-center gap-1.5 rounded-full px-2.5 text-[12px] font-semibold transition-colors max-sm:px-2',
            'bg-warning/15 text-warning ring-1 ring-inset ring-warning/40 hover:bg-warning/25',
            'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-300',
          )}
        >
          <KeyRound className="size-3.5 shrink-0" aria-hidden="true" />
          <span className="hidden sm:inline">{t('posShell.operatorSignIn')}</span>
        </button>
      ) : null}

      {/* Pinned per-shift actions */}
      {pinned.map((a) => (
        <button
          key={a.key}
          type="button"
          onClick={a.onClick}
          disabled={a.disabled}
          data-testid={a.testId}
          aria-label={a.label}
          title={a.disabled ? (a.disabledTitle ?? a.label) : a.label}
          className={cn(
            'relative grid size-10 shrink-0 place-items-center rounded-xl transition-colors',
            'text-white/60 hover:bg-white/10 hover:text-white dark:text-ink-3 dark:hover:bg-hover dark:hover:text-ink',
            'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-300',
            'disabled:cursor-not-allowed disabled:opacity-35 disabled:hover:bg-transparent',
          )}
        >
          {a.icon}
          {a.badge && a.badge.count > 0 ? (
            <span
              className={cn(
                'absolute -right-0.5 -top-0.5 grid h-4 min-w-4 place-items-center rounded-full px-1 text-[9px] font-bold',
                a.badge.tone === 'warning' ? 'bg-warning text-ink' : 'bg-brand-500 text-white',
              )}
            >
              {a.badge.count}
            </span>
          ) : null}
        </button>
      ))}

      {/* Till menu (overflow) */}
      <button
        type="button"
        onClick={onOverflowClick}
        aria-label={t('posShell.tillMenu')}
        aria-expanded={overflowOpen}
        data-testid="pos-till-menu"
        className={cn(
          'grid size-10 shrink-0 place-items-center rounded-xl transition-colors',
          'text-white/60 hover:bg-white/10 hover:text-white dark:text-ink-3 dark:hover:bg-hover dark:hover:text-ink',
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-300',
          overflowOpen && 'bg-white/10 text-white dark:bg-hover dark:text-ink',
        )}
      >
        <MoreVertical className="size-4" />
      </button>
    </div>
  )
}
