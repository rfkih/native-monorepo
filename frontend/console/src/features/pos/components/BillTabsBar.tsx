/**
 * BillTabsBar — the bill CONTEXT STRIP (redesign P4, slimmed from 88px to 64px).
 *
 * The strip is the visible answer to "where does the next tap land": a pinned WALK-IN tab
 * (the local cart — active when no bill is selected) followed by one tab per open bill. The
 * old implicit cart-vs-bill mode is now plain navigation: selected tab = ticket destination.
 *
 * Phone (<sm): a single selector button (walk-in or the active bill) + the dashed new-bill
 * button, as before.
 */
import { useTranslation } from 'react-i18next'
import { ChevronDown, Plus, ShoppingBag } from 'lucide-react'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import { type BillSummaryResponse } from '../billsApi'

export function BillTabsBar({
  bills,
  activeBillId,
  locale,
  offline,
  walkInCount,
  walkInTotalMinor,
  currency,
  onWalkInClick,
  onTabClick,
  onNewBill,
  onSelectorClick,
}: {
  bills: BillSummaryResponse[]
  activeBillId: string | null
  locale: string
  /** Phase 5 (ADR 0028): starting a new bill/tab is a server round-trip — disabled offline. */
  offline: boolean
  /** The local walk-in cart, surfaced as the pinned first tab (redesign P4). */
  walkInCount: number
  walkInTotalMinor: number
  currency: string
  onWalkInClick: () => void
  onTabClick: (billId: string) => void
  onNewBill: () => void
  onSelectorClick: () => void
}) {
  const { t } = useTranslation()
  const activeBill = activeBillId ? bills.find((b) => b.id === activeBillId) : null
  const walkInActive = activeBillId === null

  return (
    <div className="h-16 shrink-0 border-b border-line bg-surface">
      {/* Desktop tabs (sm+) */}
      <div
        role="tablist"
        aria-label={t('bills.trayTitle')}
        className="hidden h-full items-stretch gap-1 overflow-x-auto px-4 sm:flex [-ms-overflow-style:none] [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
      >
        {/* Pinned walk-in tab — the local cart is always destination #1 */}
        <button
          type="button"
          role="tab"
          aria-selected={walkInActive}
          data-testid="pos-walkin-tab"
          onClick={onWalkInClick}
          className={cn(
            'flex shrink-0 items-center gap-2 px-4 transition-colors focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald',
            walkInActive
              ? 'border-b-[3px] border-emerald'
              : 'border-b-[3px] border-transparent hover:bg-emerald-tint/40',
          )}
        >
          <ShoppingBag
            className={cn('size-4 shrink-0', walkInActive ? 'text-emerald-2' : 'text-ink-3')}
            aria-hidden="true"
          />
          <span className="flex flex-col items-start">
            <span className={cn('text-[14px] font-bold leading-tight', walkInActive ? 'text-ink' : 'text-ink-2')}>
              {t('posShell.walkIn')}
              {walkInCount > 0 ? (
                <span className="ml-1.5 text-[12px] font-semibold text-ink-3">· {walkInCount}</span>
              ) : null}
            </span>
            <span
              className={cn(
                'tnum font-mono text-[11px] font-semibold leading-tight',
                walkInActive ? 'text-emerald-2' : 'text-ink-3',
              )}
            >
              {walkInCount > 0 ? formatMoney(walkInTotalMinor, currency, locale) : '—'}
            </span>
          </span>
        </button>

        {bills.map((bill) => {
          const isActive = bill.id === activeBillId
          return (
            <button
              key={bill.id}
              type="button"
              role="tab"
              aria-selected={isActive}
              onClick={() => onTabClick(bill.id)}
              aria-label={t('bills.activeBillAriaLabel', { label: bill.guestLabel })}
              className={cn(
                'flex shrink-0 flex-col justify-center px-4 transition-colors focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald',
                isActive
                  ? 'border-b-[3px] border-emerald'
                  : 'border-b-[3px] border-transparent hover:bg-emerald-tint/40',
              )}
            >
              <div className="flex items-center gap-1.5">
                <span className={cn('max-w-[120px] truncate text-[14px] font-bold leading-tight', isActive ? 'text-ink' : 'text-ink-2')}>
                  {bill.guestLabel}
                </span>
                {bill.lineCount > 0 ? (
                  <span className="text-[12px] font-semibold leading-tight text-ink-3">· {bill.lineCount}</span>
                ) : null}
              </div>
              <div
                className={cn(
                  'tnum font-mono text-[11px] font-semibold leading-tight',
                  isActive ? 'text-emerald-2' : 'text-ink-3',
                )}
              >
                {formatMoney(bill.runningTotalMinor, bill.currency, locale)}
              </div>
            </button>
          )
        })}

        <div className="flex-1" />

        {/* New bill button (dashed) — disabled offline (cash quick-sale only) */}
        <div className="flex items-center">
          <button
            type="button"
            onClick={onNewBill}
            disabled={offline}
            aria-label={t('bills.newBillAriaLabel')}
            title={offline ? t('offline.disabled.openBills') : undefined}
            className="grid size-11 place-items-center rounded-xl border-[1.5px] border-dashed border-emerald-line bg-surface text-emerald-2 transition-colors hover:bg-emerald-tint focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-surface"
          >
            <Plus className="size-5" aria-hidden="true" />
          </button>
        </div>
      </div>

      {/* Phone bill selector button (<sm) */}
      <div className="flex h-full items-center gap-2 px-4 sm:hidden">
        <button
          type="button"
          onClick={onSelectorClick}
          aria-label={t('bills.billSelectorAriaLabel', {
            label: activeBill?.guestLabel ?? t('posShell.walkIn'),
            total: activeBill ? formatMoney(activeBill.runningTotalMinor, activeBill.currency, locale) : '',
          })}
          className="flex min-w-0 flex-1 h-11 items-center gap-2 rounded-xl bg-emerald-tint border-[1.5px] border-emerald px-3 text-left focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
        >
          {activeBill ? (
            <>
              <span className="min-w-0 flex-1 truncate text-[14px] font-bold text-emerald-2">
                {activeBill.guestLabel}
              </span>
              <span className="tnum shrink-0 font-mono text-[13px] font-semibold text-emerald-2">
                {formatMoney(activeBill.runningTotalMinor, activeBill.currency, locale)}
              </span>
            </>
          ) : (
            <>
              <ShoppingBag className="size-4 shrink-0 text-emerald-2" aria-hidden="true" />
              <span className="min-w-0 flex-1 truncate text-[14px] font-bold text-emerald-2">
                {t('posShell.walkIn')}
              </span>
              {walkInCount > 0 ? (
                <span className="tnum shrink-0 font-mono text-[13px] font-semibold text-emerald-2">
                  {formatMoney(walkInTotalMinor, currency, locale)}
                </span>
              ) : null}
            </>
          )}
          <ChevronDown className="size-4 shrink-0 text-emerald-2" aria-hidden="true" />
        </button>
        <button
          type="button"
          onClick={onNewBill}
          disabled={offline}
          aria-label={t('bills.newBillAriaLabel')}
          title={offline ? t('offline.disabled.openBills') : undefined}
          className="grid size-11 shrink-0 place-items-center rounded-xl border-[1.5px] border-dashed border-emerald-line bg-surface text-emerald-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald disabled:cursor-not-allowed disabled:opacity-40"
        >
          <Plus className="size-[18px]" aria-hidden="true" />
        </button>
      </div>
    </div>
  )
}
