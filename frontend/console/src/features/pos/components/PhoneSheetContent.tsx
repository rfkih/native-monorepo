/**
 * PhoneSheetContent.tsx — extracted VERBATIM from BillDetail.tsx (redesign P2, mechanical move only).
 */
import { useTranslation } from 'react-i18next'
import {
  Table2,
  X,
  ChefHat,
  SplitSquareHorizontal,
  Send,
} from 'lucide-react'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import type { } from '@/lib/session'
import type { MenuItem,
} from '../api'
import type { BillResponse, BillLineResponse } from '../billsApi'
import type { VirtualCategory } from '../lib/categories'
import type { BillLineGroup } from '../lib/billLineGroups'
import { BillLineItem } from './BillLineItem'
import { BillLineGroupItem } from './BillLineGroupItem'


// ---------------------------------------------------------------------------
// PhoneSheetContent — full-page content for <sm screens
// ---------------------------------------------------------------------------

export interface PhoneSheetProps {
  bill: BillResponse
  items: MenuItem[]
  visibleItems: MenuItem[]
  orderedCategories: VirtualCategory[]
  resolvedCategoryId: string
  activeCategoryId: string | null
  setActiveCategoryId: (id: string | null) => void
  tableLabel: string | null
  locale: string
  currency: string
  lineCount: number
  unpaidLines: BillLineResponse[]
  unpaidTotal: number
  grandTotal: number
  selectedLines: BillLineResponse[]
  selectedTotal: number
  selectedLineIds: Set<string>
  splitMode: boolean
  allLinesPaid: boolean
  appendLines: { isPending: boolean; isError: boolean; error: unknown }
  removeLine: { isPending: boolean; isError: boolean; error: unknown }
  isRemoving: boolean
  lineGroups: BillLineGroup[]
  paidLines: BillLineResponse[]
  onIncrementGroup: (g: BillLineGroup) => void
  onDecrementGroup: (g: BillLineGroup) => void
  onRemoveGroup: (g: BillLineGroup) => void
  busy: boolean
  onItemTap: (item: MenuItem) => void
  onToggleSplitMode: () => void
  onToggleLineSelect: (lineId: string) => void
  onRemoveLine: (lineId: string) => void
  onKot: () => void
  onCancel: () => void
  onPayModal: () => void
  onClose: () => void
  onBack: () => void
}

export function PhoneSheetContent({
  bill,
  tableLabel,
  locale,
  currency,
  lineCount,
  unpaidLines,
  unpaidTotal,
  grandTotal,
  selectedLines,
  selectedTotal,
  selectedLineIds,
  splitMode,
  allLinesPaid,
  isRemoving,
  lineGroups,
  paidLines,
  onIncrementGroup,
  onDecrementGroup,
  onRemoveGroup,
  busy,
  onToggleSplitMode,
  onToggleLineSelect,
  onRemoveLine,
  onKot,
  onCancel,
  onPayModal,
  onClose,
}: PhoneSheetProps) {
  const { t } = useTranslation()

  return (
    <>
      {/* Drag handle */}
      <div className="flex shrink-0 flex-col items-center pt-3 pb-1">
        <div className="h-1 w-11 rounded-full bg-line" aria-hidden="true" />
      </div>

      {/* Header */}
      <div className="flex shrink-0 items-center gap-2 border-b border-line px-4 py-3">
        <h2 className="font-display text-base font-bold text-ink">{bill.guestLabel}</h2>
        {tableLabel ? (
          <span className="flex items-center gap-1 text-sm text-ink-3">
            <Table2 className="size-3.5" aria-hidden="true" />
            {tableLabel}
          </span>
        ) : null}
        <div className="flex-1" />
        <button
          type="button"
          onClick={onKot}
          disabled={lineCount === 0}
          aria-label={t('kot.title')}
          className="grid size-9 place-items-center rounded-xl border border-line text-ink-3 hover:bg-hover disabled:opacity-40"
        >
          <ChefHat className="size-4" />
        </button>
        {unpaidLines.length > 1 ? (
          <button
            type="button"
            onClick={onToggleSplitMode}
            aria-pressed={splitMode}
            aria-label={t('bills.splitToggle')}
            className={cn(
              'grid size-9 place-items-center rounded-xl border',
              splitMode ? 'border-emerald bg-emerald-tint text-emerald-2' : 'border-line text-ink-3 hover:bg-hover',
            )}
          >
            <SplitSquareHorizontal className="size-4" />
          </button>
        ) : null}
        <button
          type="button"
          onClick={onClose}
          aria-label={t('common.close')}
          className="grid size-9 place-items-center rounded-xl text-ink-3 hover:bg-hover"
        >
          <X className="size-5" />
        </button>
      </div>

      {/* Lines (scrollable) */}
      <div className="min-h-0 flex-1 overflow-y-auto">
        {lineCount === 0 ? (
          <p className="px-4 py-10 text-center text-sm text-ink-3">{t('bills.noLines')}</p>
        ) : (
          <ul className="divide-y divide-line">
            {splitMode
              ? bill.lines.map((line) => (
                  <BillLineItem
                    key={line.id}
                    line={line}
                    locale={locale}
                    currency={currency}
                    splitMode={splitMode}
                    selected={selectedLineIds.has(line.id)}
                    onToggleSelect={() => onToggleLineSelect(line.id)}
                    onRemove={() => onRemoveLine(line.id)}
                    isRemoving={isRemoving}
                  />
                ))
              : [
                  ...lineGroups.map((g) => (
                    <BillLineGroupItem
                      key={g.key}
                      group={g}
                      locale={locale}
                      currency={currency}
                      onIncrement={() => onIncrementGroup(g)}
                      onDecrement={() => onDecrementGroup(g)}
                      onRemove={() => onRemoveGroup(g)}
                      busy={busy}
                    />
                  )),
                  ...paidLines.map((line) => (
                    <BillLineItem
                      key={line.id}
                      line={line}
                      locale={locale}
                      currency={currency}
                      splitMode={false}
                      selected={false}
                      onToggleSelect={() => {}}
                      onRemove={() => {}}
                      isRemoving={false}
                    />
                  )),
                ]}
          </ul>
        )}
      </div>

      {/* Totals + Footer */}
      <div className="shrink-0 border-t border-line">
        {lineCount > 0 ? (
          <div className="border-b border-line px-4 py-3">
            <div className="flex items-baseline justify-between text-sm">
              <span className="text-ink-3">{t('pos.total')}</span>
              <span className="tnum font-mono font-bold text-ink">
                {formatMoney(
                  bill.lines.some((l) => l.paid) ? unpaidTotal : grandTotal,
                  currency,
                  locale,
                )}
              </span>
            </div>
          </div>
        ) : null}

        {splitMode && selectedLines.length > 0 ? (
          <div className="border-b border-line bg-emerald-tint px-4 py-2.5">
            <div className="flex items-baseline justify-between text-sm">
              <span className="font-semibold text-emerald-2">
                {t('bills.splitSelected', { n: selectedLines.length })}
              </span>
              <span className="tnum font-mono font-bold text-emerald-2">
                {formatMoney(selectedTotal, currency, locale)}
              </span>
            </div>
          </div>
        ) : null}

        <div className="flex gap-2 px-4 py-4">
          {splitMode ? (
            <button
              type="button"
              disabled={selectedLines.length === 0}
              onClick={onPayModal}
              className="tnum h-14 flex-1 rounded-xl bg-emerald px-4 font-mono text-sm font-bold text-on-emerald disabled:opacity-40"
            >
              {t('bills.paySplit', { n: selectedLines.length })} ·{' '}
              {formatMoney(selectedTotal, currency, locale)}
            </button>
          ) : (
            <>
              <button
                type="button"
                onClick={onKot}
                disabled={lineCount === 0}
                className="flex h-14 items-center gap-2 rounded-xl border border-emerald-line bg-emerald-tint px-4 text-[14px] font-bold text-emerald-2 disabled:opacity-40"
              >
                <Send className="size-4" aria-hidden="true" />
                {t('bills.sendN', { n: unpaidLines.length })}
              </button>
              <button
                type="button"
                disabled={unpaidLines.length === 0}
                onClick={onPayModal}
                className="tnum h-14 flex-1 rounded-xl bg-emerald px-4 font-mono text-[14px] font-bold text-on-emerald disabled:opacity-40"
              >
                {allLinesPaid
                  ? t('bills.allPaid')
                  : t('bills.payTotal', { total: formatMoney(unpaidTotal, currency, locale) })}
              </button>
            </>
          )}
        </div>
        <div className="pb-4 text-center">
          <button
            type="button"
            onClick={onCancel}
            className="text-xs text-ink-3 underline hover:text-loss"
          >
            {t('bills.cancelBill')}
          </button>
        </div>
      </div>
    </>
  )
}
