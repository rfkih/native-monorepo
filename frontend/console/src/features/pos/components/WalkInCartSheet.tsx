/**
 * WalkInCartSheet — the editable review of the WALK-IN cart (Pos.tsx local `cart` state), which
 * otherwise had no line editor at all ("walk-in has no sheet", see SummaryBar). Each line gets a
 * − [qty] + stepper and a remove button; − at qty 1 removes the item. Purely local-state mutation
 * (no backend) — the cart is a client array until Charge. Opened from the dock's expand chevron.
 */
import { useTranslation } from 'react-i18next'
import { Minus, Plus, Trash2 } from 'lucide-react'
import { MobileSheet } from '@/components/mobile/MobileSheet'
import { formatMoney } from '@/lib/money'
import { lineKey } from '../lib/lineKey'
import type { MenuItem } from '../api'

export interface WalkInCartLine {
  menuItemId: string
  qty: number
  selectedOptionIds: string[]
  effectiveUnitPriceMinor: number
  selectedOptionNames: string[]
}

const STEP_BTN =
  'grid size-10 place-items-center rounded-lg border border-line text-ink-3 transition-colors hover:bg-hover hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald'

export function WalkInCartSheet({
  cart,
  items,
  currency,
  locale,
  onInc,
  onDec,
  onRemove,
  onClear,
  onClose,
}: {
  cart: WalkInCartLine[]
  items: MenuItem[]
  currency: string
  locale: string
  onInc: (key: string) => void
  onDec: (key: string) => void
  onRemove: (key: string) => void
  onClear: () => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const nameOf = (id: string) => items.find((i) => i.id === id)?.name ?? ''
  const total = cart.reduce((s, l) => s + l.effectiveUnitPriceMinor * l.qty, 0)

  return (
    <MobileSheet onClose={onClose} ariaLabel={t('posShell.currentOrder')}>
      <div className="flex min-h-0 flex-1 flex-col">
        {/* Header */}
        <div className="flex shrink-0 items-center justify-between px-4 pb-3 pt-1">
          <h2 className="text-[17px] font-bold text-ink">{t('posShell.currentOrder')}</h2>
          {cart.length > 0 ? (
            <button
              type="button"
              onClick={onClear}
              className="rounded-lg px-2 py-1 text-xs font-medium text-ink-3 underline transition-colors hover:text-loss"
            >
              {t('pos.clearCart')}
            </button>
          ) : null}
        </div>

        {/* Lines */}
        <div className="min-h-0 flex-1 overflow-y-auto">
          {cart.length === 0 ? (
            <p className="px-4 py-10 text-center text-sm text-ink-3">{t('pos.cartEmpty')}</p>
          ) : (
            <ul className="divide-y divide-line">
              {cart.map((l) => {
                const key = lineKey(l.menuItemId, l.selectedOptionIds)
                const name = nameOf(l.menuItemId)
                return (
                  <li key={key} className="px-4 py-3">
                    <div className="flex items-center gap-3">
                      <div className="min-w-0 flex-1">
                        <div className="truncate text-sm font-medium text-ink">{name}</div>
                        <div className="tnum mt-0.5 font-mono text-xs text-ink-3">
                          {formatMoney(l.effectiveUnitPriceMinor, currency, locale)}
                        </div>
                        {l.selectedOptionNames.length > 0 ? (
                          <div className="mt-1 flex flex-wrap gap-1">
                            {l.selectedOptionNames.map((n, i) => (
                              <span key={i} className="text-[11px] text-ink-3">
                                {n}
                              </span>
                            ))}
                          </div>
                        ) : null}
                      </div>

                      {/* − [qty] + */}
                      <div className="flex shrink-0 items-center gap-1.5">
                        <button
                          type="button"
                          onClick={() => onDec(key)}
                          aria-label={t('bills.decreaseQty')}
                          className={STEP_BTN}
                        >
                          <Minus className="size-3.5" />
                        </button>
                        <span className="tnum min-w-[1.5rem] text-center font-mono text-sm font-bold text-ink">
                          {l.qty}
                        </span>
                        <button
                          type="button"
                          onClick={() => onInc(key)}
                          aria-label={t('bills.increaseQty')}
                          className={STEP_BTN}
                        >
                          <Plus className="size-3.5" />
                        </button>
                      </div>

                      <button
                        type="button"
                        onClick={() => onRemove(key)}
                        aria-label={t('bills.removeLine', { name })}
                        className="grid size-9 shrink-0 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-hover hover:text-loss focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
                      >
                        <Trash2 className="size-3.5" />
                      </button>
                    </div>
                  </li>
                )
              })}
            </ul>
          )}
        </div>

        {/* Subtotal — pre-discount/coupon/loyalty/tax. The dock (SummaryBar) shows the quote GRAND
            total; labeling this "Total" too would show two disagreeing "Total"s once a discount/PB1/
            VAT applies. Payment always recomputes server-side. */}
        {cart.length > 0 ? (
          <div className="shrink-0 border-t border-line px-4 py-3">
            <div className="flex items-baseline justify-between">
              <span className="text-sm text-ink-3">{t('pos.subtotal')}</span>
              <span className="tnum font-mono text-base font-bold text-ink">
                {formatMoney(total, currency, locale)}
              </span>
            </div>
          </div>
        ) : null}
      </div>
    </MobileSheet>
  )
}
