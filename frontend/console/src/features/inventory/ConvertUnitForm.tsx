/**
 * ConvertUnitForm — moving an ingredient off a base unit too coarse to cook with (phone: the
 * `/inventory/:id/convert` route; desktop: the two-pane rail).
 *
 * A `pack` is a purchase container, not a unit of consumption: you buy sauce by the pack and use
 * it by the gram. With `pack` as the BASE unit the smallest quantity a recipe can express is one
 * whole pack, so the ingredient ends up in no recipe at all — invisible to HPP and to shortfall
 * detection alike. The picker no longer offers `pack`, but ingredients created before that (and
 * legacy `kg`-as-base rows) still need moving. One question — "1 pack = how many grams?" — with
 * the resulting stock previewed, so the factor is checkable before it is committed. The server
 * rescales stock, cost, recipe lines and the whole daily ledger in one transaction; total stock
 * VALUE is unchanged, because nothing was bought, sold or lost (`lib/unitConversion.ts`).
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ArrowRight } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { cn } from '@/lib/cn'
import type { CompanySession } from '@/lib/session'
import { MicroLabel } from './InventoryChrome'
import { INGREDIENT_UNIT_GROUPS, useConvertIngredientUnit, type Ingredient } from './ingredientApi'
import { previewConversion } from './lib/unitConversion'
import { formatShownQty, shownUnit } from './lib/units'

export function ConvertUnitForm({
  session,
  ingredient,
  locale,
  variant,
  onDone,
  onCancel,
}: {
  session: CompanySession
  ingredient: Ingredient
  locale: string
  variant: 'page' | 'rail'
  onDone: () => void
  onCancel: () => void
}) {
  const { t } = useTranslation()
  const convert = useConvertIngredientUnit(session)
  const [unitChoice, setUnitChoice] = useState('g')
  const [factorInput, setFactorInput] = useState('')

  const preview = previewConversion(unitChoice, factorInput, ingredient.stockQty)
  const touched = factorInput.trim() !== ''
  const before = `${formatShownQty(ingredient.stockQty, ingredient, locale)} ${shownUnit(ingredient)}`
  const rail = variant === 'rail'

  function handleSubmit() {
    if (!preview.ok) return
    convert.mutate(
      {
        id: ingredient.id,
        toUnit: preview.toUnit,
        toDisplayUnit: preview.toDisplayUnit,
        factor: preview.factor,
      },
      { onSuccess: onDone },
    )
  }

  return (
    <div
      className={cn(
        'flex flex-col gap-[18px]',
        rail ? 'px-[22px] pb-7 pt-5' : 'mx-auto max-w-[640px] px-4 pb-12 pt-3',
      )}
    >
      {rail ? (
        <div className="font-display text-lg font-bold leading-tight tracking-[-0.02em] text-ink">
          {t('inventory.convertUnit.title', { name: ingredient.name })}
        </div>
      ) : null}
      <p className="text-sm leading-relaxed text-ink-2 text-pretty">
        {t('inventory.convertUnit.intro', { unit: ingredient.unit })}
      </p>

      <div>
        <MicroLabel>{t('inventory.convertUnit.toUnitLabel')}</MicroLabel>
        <div
          className="mt-[9px] flex flex-col gap-2"
          role="radiogroup"
          aria-label={t('inventory.convertUnit.toUnitLabel')}
        >
          {INGREDIENT_UNIT_GROUPS.map((group) => (
            <div key={group.key} className="flex items-center gap-2.5">
              <span className="w-14 shrink-0 text-2xs font-semibold text-ink-3">
                {t(`inventory.unitGroup.${group.key}`)}
              </span>
              <div className="flex flex-1 flex-wrap gap-[7px]">
                {group.units.map((u) => {
                  const active = unitChoice === u
                  return (
                    <button
                      key={u}
                      type="button"
                      role="radio"
                      aria-checked={active}
                      onClick={() => setUnitChoice(u)}
                      className={cn(
                        'h-[42px] min-w-[54px] rounded-xl px-3.5 text-sm font-bold transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
                        active
                          ? 'bg-emerald text-on-emerald'
                          : 'border border-line bg-surface text-ink-2 hover:bg-hover',
                      )}
                    >
                      {u}
                    </button>
                  )
                })}
              </div>
            </div>
          ))}
        </div>
      </div>

      <div>
        <MicroLabel>
          {t('inventory.convertUnit.factorLabel', { from: ingredient.unit, to: unitChoice })}
        </MicroLabel>
        <input
          type="text"
          inputMode="numeric"
          value={factorInput}
          onChange={(e) => setFactorInput(e.target.value.replace(/[^\d]/g, ''))}
          placeholder="1000"
          aria-label={t('inventory.convertUnit.factorLabel', {
            from: ingredient.unit,
            to: unitChoice,
          })}
          className={cn(
            'tnum mt-[7px] h-[52px] w-full rounded-[14px] border-[1.5px] bg-surface px-4 text-right font-mono text-xl font-bold text-ink placeholder:text-ink-400 focus:outline-none focus:ring-4 focus:ring-emerald/15',
            touched && !preview.ok ? 'border-loss' : 'border-ink',
          )}
        />
        {touched && !preview.ok ? (
          <p className="mt-1.5 text-xs text-loss">
            {t(`inventory.convertUnit.errors.${preview.reason}`)}
          </p>
        ) : null}
      </div>

      {/* The preview is the whole safety mechanism: a mistyped factor is invisible as a number and
          obvious as a resulting stock figure. */}
      <div className="rounded-2xl bg-tint-profit/40 px-4 py-[15px]">
        <MicroLabel>{t('inventory.convert.result')}</MicroLabel>
        <div className="mt-[9px] flex items-center gap-2.5">
          <span className="tnum font-mono text-sm font-semibold text-ink-3">{before}</span>
          <ArrowRight
            className="size-[15px] shrink-0 text-ink-400"
            strokeWidth={2.2}
            aria-hidden="true"
          />
          <span
            className={cn(
              'tnum font-mono text-xl font-bold tracking-[-0.02em]',
              preview.ok ? 'text-ink' : 'text-ink-400',
            )}
          >
            {preview.ok
              ? `${new Intl.NumberFormat(locale).format(preview.newStockQty)} ${preview.toUnit}`
              : t('inventory.convert.invalid')}
          </span>
        </div>
        <p className="mt-[11px] text-xs leading-relaxed text-ink-2 text-pretty">
          {t('inventory.convert.resultNote')}
        </p>
      </div>

      {convert.isError ? (
        <p className="text-xs text-loss" role="alert">
          {t('inventory.convertUnit.errors.failed')}
        </p>
      ) : null}

      <div className="flex gap-2">
        <Button
          variant="outline"
          size="xl"
          className="shrink-0 px-5"
          onClick={onCancel}
          disabled={convert.isPending}
        >
          {t('common.cancel')}
        </Button>
        <Button
          size="xl"
          className="flex-1"
          onClick={handleSubmit}
          disabled={!preview.ok || convert.isPending}
        >
          {convert.isPending ? <Spinner /> : t('inventory.convertUnit.submit')}
        </Button>
      </div>
    </div>
  )
}
