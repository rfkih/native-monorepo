/**
 * BillSelectorOverlay — the ORDER SWITCHER (redesign P4, grown from the phone-only bill list).
 *
 * Opened from the ticket dock's destination pill on every viewport: a pinned WALK-IN row (the
 * local cart), one row per open bill, and shortcuts to the table floor and the parked tray.
 * The switcher is how the cashier answers "which order am I ringing?" — the old implicit
 * cart-vs-bill mode as explicit navigation.
 */
import { useTranslation } from 'react-i18next'
import { ClipboardList, Plus, ShoppingBag, Table2, X } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import { type BillSummaryResponse } from '../billsApi'

export function BillSelectorOverlay({
  bills,
  activeBillId,
  locale,
  walkInCount,
  walkInTotalMinor,
  currency,
  onWalkIn,
  onOpenFloor,
  onOpenParked,
  onSelect,
  onNewBill,
  onClose,
}: {
  bills: BillSummaryResponse[]
  activeBillId: string | null
  locale: string
  /** The local walk-in cart, pinned as the first destination (redesign P4). */
  walkInCount: number
  walkInTotalMinor: number
  currency: string
  onWalkIn: () => void
  /** Null while offline (the floor/parked need a connection — ADR 0028). */
  onOpenFloor: (() => void) | null
  onOpenParked: (() => void) | null
  onSelect: (billId: string) => void
  onNewBill: () => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  useBackDismiss(onClose)
  const walkInActive = activeBillId === null
  return (
    <div
      className="fixed inset-0 z-50 flex flex-col bg-paper sm:bg-black/40 sm:p-6 sm:backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('posShell.switcherTitle')}
    >
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden bg-paper sm:mx-auto sm:my-auto sm:max-h-[80vh] sm:w-full sm:max-w-md sm:rounded-2xl sm:border sm:border-line sm:shadow-lg">
        <div className="flex items-center justify-between border-b border-line bg-surface px-4 py-3">
          <h2 className="font-display text-lg font-semibold text-ink">{t('posShell.switcherTitle')}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('common.close')}
            className="grid size-9 place-items-center rounded-xl text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <X className="size-4" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-4">
          {/* Pinned walk-in destination */}
          <button
            type="button"
            data-testid="switcher-walkin"
            onClick={onWalkIn}
            className={cn(
              'mb-2 w-full rounded-xl border px-4 py-3 text-left transition-all focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
              walkInActive
                ? 'border-emerald bg-emerald-tint'
                : 'border-line bg-surface hover:border-emerald-line hover:bg-emerald-tint/30',
            )}
          >
            <div className="flex items-center justify-between">
              <span className="flex items-center gap-2 font-semibold text-ink">
                <ShoppingBag className="size-4 text-emerald-2" aria-hidden="true" />
                {t('posShell.walkIn')}
              </span>
              <span className="tnum font-mono text-sm font-semibold text-ink">
                {walkInCount > 0 ? formatMoney(walkInTotalMinor, currency, locale) : '—'}
              </span>
            </div>
            <div className="mt-1 text-xs text-ink-3">{t('bills.lineCount', { n: walkInCount })}</div>
          </button>

          {bills.length === 0 ? (
            <p className="py-8 text-center text-sm text-ink-3">{t('bills.noBills')}</p>
          ) : (
            <div className="space-y-2">
              {bills.map((bill) => (
                <button
                  key={bill.id}
                  type="button"
                  onClick={() => onSelect(bill.id)}
                  className={cn(
                    'w-full rounded-xl border px-4 py-3 text-left transition-all focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
                    bill.id === activeBillId
                      ? 'border-emerald bg-emerald-tint'
                      : 'border-line bg-surface hover:border-emerald-line hover:bg-emerald-tint/30',
                  )}
                >
                  <div className="flex items-center justify-between">
                    <span className="font-semibold text-ink">{bill.guestLabel}</span>
                    <span className="tnum font-mono text-sm font-semibold text-ink">
                      {formatMoney(bill.runningTotalMinor, bill.currency, locale)}
                    </span>
                  </div>
                  <div className="mt-1 text-xs text-ink-3">
                    {t('bills.lineCount', { n: bill.lineCount })}
                  </div>
                </button>
              ))}
            </div>
          )}
        </div>

        <div className="space-y-2 border-t border-line p-4">
          {/* Floor / parked shortcuts (hidden offline — both need a connection, ADR 0028) */}
          {onOpenFloor || onOpenParked ? (
            <div className="flex gap-2">
              {onOpenFloor ? (
                <button
                  type="button"
                  onClick={onOpenFloor}
                  className="flex flex-1 items-center justify-center gap-2 rounded-xl border border-line bg-surface py-2.5 text-[13px] font-semibold text-ink-2 hover:border-emerald-line hover:bg-emerald-tint/40 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
                >
                  <Table2 className="size-4" aria-hidden="true" />
                  {t('posShell.switcherFloor')}
                </button>
              ) : null}
              {onOpenParked ? (
                <button
                  type="button"
                  onClick={onOpenParked}
                  className="flex flex-1 items-center justify-center gap-2 rounded-xl border border-line bg-surface py-2.5 text-[13px] font-semibold text-ink-2 hover:border-emerald-line hover:bg-emerald-tint/40 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
                >
                  <ClipboardList className="size-4" aria-hidden="true" />
                  {t('posShell.switcherParked')}
                </button>
              ) : null}
            </div>
          ) : null}
          <button
            type="button"
            onClick={onNewBill}
            className="flex w-full items-center justify-center gap-2 rounded-xl border-[1.5px] border-dashed border-emerald-line bg-surface py-3 text-[15px] font-semibold text-emerald-2 hover:bg-emerald-tint focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <Plus className="size-5" aria-hidden="true" />
            {t('bills.newBill')}
          </button>
        </div>
      </div>
    </div>
  )
}
