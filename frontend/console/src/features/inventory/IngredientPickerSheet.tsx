/**
 * IngredientPickerSheet — pick one ingredient from the outlet's catalog in a bottom sheet (ADR
 * 0083, shared with the phone bill form in ADR 0084). Search-as-you-type over active ingredients,
 * each row with its moving-average unit cost so the picker already says what the line will cost.
 * The one Dialog primitive (ADR 0075 N3); the caller decides what a pick means.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Search, X } from 'lucide-react'
import { DialogOverlay } from '@/components/ui/Dialog'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import type { Ingredient } from './ingredientApi'
import { safeBottom } from '@/lib/safeArea'

const ICON_BUTTON =
  'grid size-11 shrink-0 place-items-center rounded-xl text-ink-2 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald'

/** Fixed bottom surfaces bypass the body's safe-area padding (index.css) — each pads itself. */
const SAFE_BOTTOM = safeBottom // lib/safeArea — the one definition of the nav-bar inset rule

export function IngredientPickerSheet({
  ingredients,
  locale,
  onPick,
  onClose,
}: {
  ingredients: Ingredient[]
  locale: string
  onPick: (ingredient: Ingredient) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const [q, setQ] = useState('')
  const needle = q.trim().toLocaleLowerCase(locale)
  const shown = ingredients
    .filter((i) => i.active && (needle === '' || i.name.toLocaleLowerCase(locale).includes(needle)))
    .sort((a, b) => a.name.localeCompare(b.name, locale))

  return (
    <DialogOverlay onClose={onClose} ariaLabel={t('inventoryPicker.sheet.title')} className="p-0">
      {(requestClose) => (
        <div className="flex max-h-[80dvh] flex-col">
          <div className="flex shrink-0 justify-center pb-1 pt-2.5 sm:hidden" aria-hidden="true">
            <div className="h-1 w-10 rounded-full bg-ink-300" />
          </div>
          <div className="flex shrink-0 items-center gap-2 px-[18px] pt-1.5">
            <div className="min-w-0 flex-1 text-base font-bold leading-tight text-ink">
              {t('inventoryPicker.sheet.title')}
            </div>
            <button
              type="button"
              onClick={requestClose}
              aria-label={t('common.close')}
              className={cn(ICON_BUTTON, '-mr-2.5')}
            >
              <X className="size-[19px]" aria-hidden="true" />
            </button>
          </div>
          <div className="shrink-0 px-[18px] pt-3">
            <label className="flex h-[42px] items-center gap-2.5 rounded-xl bg-hover px-3.5">
              <Search className="size-4 shrink-0 text-ink-3" strokeWidth={2} aria-hidden="true" />
              <input
                type="search"
                autoFocus
                value={q}
                onChange={(e) => setQ(e.target.value)}
                placeholder={t('inventoryPicker.sheet.search')}
                aria-label={t('inventoryPicker.sheet.search')}
                className="min-w-0 flex-1 bg-transparent text-sm font-medium text-ink placeholder:text-ink-400 focus:outline-none [&::-webkit-search-cancel-button]:appearance-none"
              />
            </label>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto px-[18px] pt-2" style={SAFE_BOTTOM(16)}>
            {shown.length === 0 ? (
              <p className="py-8 text-center text-sm text-ink-3">
                {t('inventoryPicker.sheet.empty')}
              </p>
            ) : (
              <div className="overflow-hidden rounded-xl border border-line bg-surface">
                {shown.map((ing) => (
                  <button
                    key={ing.id}
                    type="button"
                    onClick={() => onPick(ing)}
                    className="flex min-h-[48px] w-full items-center gap-3 border-b border-line/60 px-3 py-2 text-left transition-colors last:border-b-0 hover:bg-paper"
                  >
                    <span className="min-w-0 flex-1 truncate text-sm font-semibold text-ink">
                      {ing.name}
                    </span>
                    <span className="tnum shrink-0 font-mono text-xs text-ink-3">
                      {ing.unitCostMinor != null && ing.costCurrency
                        ? `${formatMoney(ing.unitCostMinor, ing.costCurrency, locale)}/${ing.unit}`
                        : t('inventoryPicker.sheet.costNone')}
                    </span>
                  </button>
                ))}
              </div>
            )}
            <Link
              to="/inventory"
              viewTransition
              className="mt-3 block text-center text-xs font-semibold text-ink-2 underline-offset-2 hover:underline"
            >
              {t('inventoryPicker.sheet.manage')}
            </Link>
          </div>
        </div>
      )}
    </DialogOverlay>
  )
}
