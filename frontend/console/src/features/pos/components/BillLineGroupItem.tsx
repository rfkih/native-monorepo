/**
 * BillLineGroupItem.tsx — one grouped bill row (non-split, unpaid) with a −/+ quantity stepper.
 * Mirrors BillLineItem's markup/style; the stepper maps to the existing appendLines/removeLine
 * mutations (frontend-only grouping — see billLineGroups.ts).
 */
import { useTranslation } from 'react-i18next'
import { Minus, Plus, Trash2 } from 'lucide-react'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import type { BillLineGroup } from '../lib/billLineGroups'

// ---------------------------------------------------------------------------
// BillLineGroupItem
// ---------------------------------------------------------------------------

export function BillLineGroupItem({
  group,
  locale,
  currency,
  onIncrement,
  onDecrement,
  onRemove,
  busy,
  canRemove = true,
}: {
  group: BillLineGroup
  locale: string
  currency: string
  onIncrement: () => void
  onDecrement: () => void
  onRemove: () => void
  busy: boolean
  /** Open-bill lockdown: false hides the −/remove affordances (adding stays allowed — cashiers
   *  can still take orders; only TRIMMING the bill is owner/manager). */
  canRemove?: boolean
}) {
  const { t } = useTranslation()

  return (
    <li className="px-5 py-3 transition-colors">
      <div className="flex items-center gap-3">
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-medium text-ink">{group.nameSnapshot}</div>
          <div className="tnum mt-0.5 font-mono text-xs text-ink-3">
            {formatMoney(group.unitPriceMinor + group.modifierDeltaMinor, currency, locale)}
          </div>
          {/* Modifier pills */}
          {group.modifiers.length > 0 ? (
            <div className="mt-1 flex flex-wrap gap-1">
              {group.modifiers.map((mod) => (
                <span key={mod.optionId} className="text-[11px] text-ink-3">
                  {mod.nameSnapshot}
                </span>
              ))}
            </div>
          ) : null}
        </div>

        {/* Stepper — the − is a removeLine in disguise, so it follows the lockdown too. */}
        <div className="flex shrink-0 items-center gap-1.5">
          {canRemove ? (
            <button
              type="button"
              onClick={onDecrement}
              disabled={busy}
              aria-label={t('bills.decreaseQty')}
              className="grid size-10 place-items-center rounded-lg border border-line text-ink-3 hover:bg-hover hover:text-ink disabled:opacity-40 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
            >
              <Minus className="size-3.5" />
            </button>
          ) : null}
          <span className="tnum min-w-[1.5rem] text-center font-mono text-sm font-bold text-ink">
            {group.qty}
          </span>
          <button
            type="button"
            onClick={onIncrement}
            disabled={busy}
            aria-label={t('bills.increaseQty')}
            className="grid size-10 place-items-center rounded-lg border border-line text-ink-3 hover:bg-hover hover:text-ink disabled:opacity-40 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <Plus className="size-3.5" />
          </button>
        </div>

        <div className="tnum shrink-0 font-mono text-sm font-medium text-ink">
          {formatMoney(group.lineTotalMinor, currency, locale)}
        </div>

        {/* Remove whole group */}
        {canRemove ? (
          <button
            type="button"
            onClick={onRemove}
            disabled={busy}
            aria-label={t('bills.removeLine', { name: group.nameSnapshot })}
            className={cn(
              'grid size-9 shrink-0 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-hover hover:text-loss disabled:opacity-40 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
            )}
          >
            <Trash2 className="size-3.5" />
          </button>
        ) : null}
      </div>
    </li>
  )
}
