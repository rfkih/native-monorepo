/**
 * BillTabsBar.tsx — extracted VERBATIM from Pos.tsx (redesign P2, mechanical move only).
 * Markup and behavior unchanged; closures became props where needed.
 */
import { useTranslation } from 'react-i18next'
import {
  ChevronDown,
  Plus,
} from 'lucide-react'
import type { } from '@/lib/session'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import { type BillSummaryResponse } from '../billsApi'
import type { } from '../lib/categories'
import type { } from '@/features/loyalty/api'


// ---------------------------------------------------------------------------
// BillTabsBar — 88px bar, desktop (sm+)
// ---------------------------------------------------------------------------

export function BillTabsBar({
  bills,
  activeBillId,
  locale,
  offline,
  onTabClick,
  onNewBill,
  onSelectorClick,
}: {
  bills: BillSummaryResponse[]
  activeBillId: string | null
  locale: string
  /** Phase 5 (ADR 0028): starting a new bill/tab is a server round-trip — disabled offline. */
  offline: boolean
  onTabClick: (billId: string) => void
  onNewBill: () => void
  onSelectorClick: () => void
}) {
  const { t } = useTranslation()
  const activeBill = activeBillId ? bills.find((b) => b.id === activeBillId) : null

  return (
    <div className="h-[88px] shrink-0 border-b border-line bg-surface">
      {/* Desktop tabs (sm+) */}
      <div
        role="tablist"
        aria-label={t('bills.trayTitle')}
        className="hidden h-full items-stretch gap-1 overflow-x-auto px-6 sm:flex [-ms-overflow-style:none] [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
      >
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
                'flex shrink-0 flex-col justify-center px-[18px] transition-colors focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald',
                isActive
                  ? 'border-b-[3px] border-emerald'
                  : 'border-b-[3px] border-transparent hover:bg-emerald-tint/40',
              )}
            >
              <div className="flex items-center gap-[7px]">
                <span
                  className={cn(
                    'text-[15px] font-bold',
                    isActive ? 'text-ink' : 'text-ink-2',
                  )}
                >
                  {bill.guestLabel}
                </span>
                {bill.lineCount > 0 ? (
                  <span
                    className={cn(
                      'text-[13px] font-semibold',
                      isActive ? 'text-ink-3' : 'text-ink-3',
                    )}
                  >
                    · {bill.lineCount}
                  </span>
                ) : null}
              </div>
              <div
                className={cn(
                  'tnum mt-[3px] font-mono text-[12px] font-semibold',
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
            className="grid size-[52px] place-items-center rounded-xl border-[1.5px] border-dashed border-emerald-line bg-surface text-emerald-2 transition-all hover:bg-emerald-tint focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-surface"
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
            label: activeBill?.guestLabel ?? t('bills.noBills'),
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
            <span className="flex-1 text-[14px] font-semibold text-emerald-2">
              {t('bills.noBillsHint')}
            </span>
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
