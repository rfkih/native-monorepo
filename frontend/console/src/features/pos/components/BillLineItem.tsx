/**
 * BillLineItem.tsx — extracted VERBATIM from BillDetail.tsx (redesign P2, mechanical move only).
 */
import { useTranslation } from 'react-i18next'
import {
  Trash2,
  Check,
} from 'lucide-react'
import { Badge } from '@/components/ui/Badge'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import type { } from '@/lib/session'
import type { BillLineResponse } from '../billsApi'
import type { } from '../lib/categories'


// ---------------------------------------------------------------------------
// BillLineItem
// ---------------------------------------------------------------------------

export function BillLineItem({
  line,
  locale,
  currency,
  splitMode,
  selected,
  onToggleSelect,
  onRemove,
  isRemoving,
  canRemove = true,
}: {
  line: BillLineResponse
  locale: string
  currency: string
  splitMode: boolean
  selected: boolean
  onToggleSelect: () => void
  onRemove: () => void
  isRemoving: boolean
  /** Open-bill lockdown: false hides the remove affordance (cashier — owner/manager only). */
  canRemove?: boolean
}) {
  const { t } = useTranslation()
  const isPaid = line.paid

  return (
    <li
      className={cn(
        'px-5 py-3 transition-colors',
        isPaid && 'bg-ink-50/40',
        splitMode && !isPaid && selected && 'bg-emerald-tint',
      )}
    >
      <div className="flex items-center gap-3">
        {splitMode ? (
          isPaid ? (
            <span className="grid size-5 shrink-0 place-items-center rounded-full bg-profit" aria-hidden="true">
              <Check className="size-3 text-white" />
            </span>
          ) : (
            <input
              type="checkbox"
              checked={selected}
              onChange={onToggleSelect}
              aria-label={t('bills.selectLine', { name: line.nameSnapshot })}
              className="size-4 shrink-0 cursor-pointer accent-emerald focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
            />
          )
        ) : null}

        <div className="min-w-0 flex-1">
          <div className={cn('truncate text-sm font-medium', isPaid ? 'text-ink-3 line-through' : 'text-ink')}>
            {line.nameSnapshot}
          </div>
          <div className="tnum mt-0.5 font-mono text-xs text-ink-3">
            {line.qty > 1 ? `${line.qty} × ` : ''}
            {formatMoney(line.unitPriceMinor + line.modifierDeltaMinor, currency, locale)}
          </div>
          {/* Modifier pills */}
          {line.modifiers.length > 0 ? (
            <div className="mt-1 flex flex-wrap gap-1">
              {line.modifiers.map((mod) => (
                <span key={mod.optionId} className="text-[11px] text-ink-3">
                  {mod.nameSnapshot}
                </span>
              ))}
            </div>
          ) : null}
        </div>

        <div className={cn('tnum shrink-0 font-mono text-sm font-medium', isPaid ? 'text-ink-3' : 'text-ink')}>
          {formatMoney(line.lineTotalMinor, currency, locale)}
        </div>

        {/* Paid badge (non-split) */}
        {!splitMode && isPaid ? (
          <Badge tone="emerald" className="shrink-0 px-1.5 py-0 text-[10px]">
            {t('bills.paid')}
          </Badge>
        ) : null}

        {/* Remove button — only for unpaid in non-split mode, and only when the actor may trim
            lines (open-bill lockdown: owner/manager; the server 403s regardless). */}
        {!splitMode && !isPaid && canRemove ? (
          <button
            type="button"
            onClick={onRemove}
            disabled={isRemoving}
            aria-label={t('bills.removeLine', { name: line.nameSnapshot })}
            className="grid size-7 shrink-0 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-hover hover:text-loss focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <Trash2 className="size-3.5" />
          </button>
        ) : null}
      </div>
    </li>
  )
}
