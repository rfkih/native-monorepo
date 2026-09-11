/**
 * PosPhoneHeader — the phone till's identity band (Native Till Android v2).
 *
 * The tablet's 56px ink band frames a bright catalog as dedicated terminal hardware. On a 412px
 * phone that band was doing a different job badly: ten shrink-0 controls competing for the width
 * that the outlet name needed, and a dark strip eating the top of a screen whose scarcest resource
 * is height. So the phone gets its own header — white, two lines, four controls:
 *
 *     Warung Kemang                        [• Online] [inbox 4] [⋮]
 *     Kemang ▾ · Cashier Rina
 *
 * What the band used to carry and this one does not — leaving the till, printer status, the
 * operator chip — moves into the till menu behind the ⋮, where it is one tap away instead of one
 * permanent icon. The outlet picker stays reachable: it IS the first half of the identity line.
 *
 * pos-shell rule: stateless presentation. Actions, counts, and the picker come in as props/slots.
 */
import { useTranslation } from 'react-i18next'
import { MoreVertical, Wifi, WifiOff } from 'lucide-react'
import { cn } from '@/lib/cn'

export function PosPhoneHeader({
  businessName,
  outletPicker,
  identity,
  offline,
  queuedCount,
  onConnectionClick,
  parkedCount,
  onParkedClick,
  parkedDisabled = false,
  parkedDisabledTitle,
  onOverflowClick,
  overflowOpen,
}: {
  businessName: string
  /** The OutletPicker element in its `subtitle` variant — slotted, not imported (it is stateful). */
  outletPicker: React.ReactNode
  /** Who is ringing — "Cashier Rina", or the login's username. Already translated. */
  identity: string
  offline: boolean
  /** Queued + rejected offline sales, shown inside the connection pill. */
  queuedCount: number
  onConnectionClick: () => void
  /** Parked + self-order tickets waiting to be picked up (ADR 0029). */
  parkedCount: number
  onParkedClick: () => void
  parkedDisabled?: boolean
  parkedDisabledTitle?: string
  onOverflowClick: () => void
  overflowOpen: boolean
}) {
  const { t } = useTranslation()
  const flagged = offline || queuedCount > 0

  return (
    <div className="flex h-[52px] shrink-0 items-center gap-1 border-b border-line bg-surface pl-3.5 pr-1.5">
      {/* Identity — the only shrinkable element; everything right of it is a fixed control, so a
          long business or outlet name must compress rather than push the ⋮ off a 360px screen. */}
      <div className="flex min-w-0 flex-1 flex-col justify-center">
        <span className="truncate font-display text-base font-bold leading-tight tracking-display text-ink">
          {businessName}
        </span>
        {/* A div, not a span: the outlet picker slots a positioned <div> in here (its dropdown
            anchor), and phrasing content cannot legally hold it. */}
        <div className="mt-px flex min-w-0 items-center gap-1 text-xs font-medium leading-tight text-ink-3">
          <div className="min-w-0 shrink">{outletPicker}</div>
          {identity ? (
            <>
              <span aria-hidden="true">·</span>
              <span className="shrink-0 truncate">{identity}</span>
            </>
          ) : null}
        </div>
      </div>

      {/* Connection pill — the terminal's always-visible online/offline state (ADR 0028). */}
      <button
        type="button"
        onClick={onConnectionClick}
        data-testid="pos-connection-pill"
        aria-label={t('offline.syncCenterButton')}
        className={cn(
          'flex h-9 shrink-0 items-center gap-1.5 rounded-full border px-2.5 text-xs font-semibold transition-colors',
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
          flagged
            ? 'border-warning-line bg-tint-warning text-amber'
            : 'border-line bg-surface text-ink-2 hover:bg-hover',
        )}
      >
        <span
          className={cn('size-[7px] shrink-0 rounded-full', flagged ? 'bg-warning' : 'bg-profit')}
          aria-hidden="true"
        />
        {offline ? (
          <WifiOff className="size-3.5 shrink-0" aria-hidden="true" />
        ) : (
          <Wifi className="size-3.5 shrink-0" aria-hidden="true" />
        )}
        {queuedCount > 0 ? <span className="tnum font-mono">{queuedCount}</span> : null}
      </button>

      {/* Incoming: parked tickets + the self-order QR ones that land here on their own. */}
      <button
        type="button"
        onClick={onParkedClick}
        disabled={parkedDisabled}
        data-testid="pos-parked"
        aria-label={t('posShell.parkedOrders')}
        title={parkedDisabled ? (parkedDisabledTitle ?? t('posShell.parkedOrders')) : undefined}
        className={cn(
          'relative grid size-11 shrink-0 place-items-center rounded-xl text-ink-2 transition-colors hover:bg-hover',
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
          'disabled:cursor-not-allowed disabled:opacity-35 disabled:hover:bg-transparent',
        )}
      >
        <svg
          width="19"
          height="19"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <rect x="6" y="3" width="12" height="18" rx="2" />
          <path d="M11 18h2" />
        </svg>
        {parkedCount > 0 ? (
          <span className="tnum absolute right-1 top-1 grid h-[17px] min-w-[17px] place-items-center rounded-full bg-emerald px-1 font-mono text-2xs font-bold text-on-emerald">
            {parkedCount}
          </span>
        ) : null}
      </button>

      {/* Till menu — everything the band no longer pins. */}
      <button
        type="button"
        onClick={onOverflowClick}
        aria-label={t('posShell.tillMenu')}
        aria-expanded={overflowOpen}
        data-testid="pos-till-menu"
        className={cn(
          'grid size-11 shrink-0 place-items-center rounded-xl text-ink-2 transition-colors hover:bg-hover',
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
          overflowOpen && 'bg-hover text-ink',
        )}
      >
        <MoreVertical className="size-[19px]" aria-hidden="true" />
      </button>
    </div>
  )
}
