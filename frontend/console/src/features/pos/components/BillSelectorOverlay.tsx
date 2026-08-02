/**
 * BillSelectorOverlay.tsx — extracted VERBATIM from Pos.tsx (redesign P2, mechanical move only).
 * Markup and behavior unchanged; closures became props where needed.
 */
import { useTranslation } from 'react-i18next'
import {
  Plus,
  X,
} from 'lucide-react'
import type { } from '@/lib/session'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import { type BillSummaryResponse } from '../billsApi'
import type { } from '../lib/categories'
import type { } from '@/features/loyalty/api'


// ---------------------------------------------------------------------------
// BillSelectorOverlay — phone full-screen bill list
// ---------------------------------------------------------------------------

export function BillSelectorOverlay({
  bills,
  activeBillId,
  locale,
  onSelect,
  onNewBill,
  onClose,
}: {
  bills: BillSummaryResponse[]
  activeBillId: string | null
  locale: string
  onSelect: (billId: string) => void
  onNewBill: () => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  return (
    <div
      className="fixed inset-0 z-50 flex flex-col bg-paper"
      role="dialog"
      aria-modal="true"
      aria-label={t('bills.trayTitle')}
    >
      <div className="flex items-center justify-between border-b border-line bg-surface px-4 py-3">
        <h2 className="font-display text-lg font-semibold text-ink">{t('bills.trayTitle')}</h2>
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
        {bills.length === 0 ? (
          <p className="py-10 text-center text-sm text-ink-3">{t('bills.noBills')}</p>
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
      <div className="border-t border-line p-4">
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
  )
}
