/**
 * IngredientDetail — one ingredient's screen (phone: the `/inventory/:id` route; desktop: the
 * two-pane page's right rail). "Stok sekarang" leads, then the sentence the days-left figure
 * makes of it, the unit fix-up card when the base unit is too coarse to cook with, the three
 * actions (Terima stok, Atur jumlah, Ubah), the item's numbers as label/value rows, and its last
 * three opname counts read from the outlet's history (`lib/stocktakeLines.ts`).
 */
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { Pencil, TriangleAlert } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { cn } from '@/lib/cn'
import { formatMoney, formatSignedMoney } from '@/lib/money'
import { MicroLabel } from './InventoryChrome'
import { formatDays, formatRateQty } from './format'
import type { InventoryData } from './inventoryData'
import { useStocktakeHistory } from './ingredientStocktakeApi'
import type { CatalogRow } from './lib/catalogView'
import { lastCountsFor, lineBearing } from './lib/stocktakeLines'
import { formatShownQty, formatSignedShownQty, shownUnit, shownUnitCostMinor } from './lib/units'
import { needsUnitConversion } from './lib/unitConversion'

export function IngredientDetail({
  data,
  row,
  locale,
  currency,
  variant,
  onReceive,
  onSet,
}: {
  data: InventoryData
  row: CatalogRow
  locale: string
  currency: string
  variant: 'page' | 'rail'
  onReceive: () => void
  onSet: () => void
}) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { ingredient } = row
  const unit = shownUnit(ingredient)
  const rateQty = row.rate != null ? formatRateQty(row.rate, ingredient, locale) : null
  const costPerShown = shownUnitCostMinor(ingredient)
  const costCurrency = ingredient.costCurrency ?? currency
  const conv = needsUnitConversion(ingredient)
  const history = useStocktakeHistory(data.session, true)
  const counts = lastCountsFor(history.data ?? [], ingredient.id)
  const rail = variant === 'rail'

  // The one sentence under the figure — what the days say, in the row's own tone.
  let note: { text: string; tone: string }
  if (row.cls === 'zero') {
    note = {
      text:
        rateQty != null
          ? t('inventory.detail.noteZero', { qty: rateQty, unit })
          : t('inventory.detail.noteZeroNoRate'),
      tone: 'text-loss',
    }
  } else if (row.days == null) {
    note = {
      text: conv ? t('inventory.detail.noteUnitUnused') : t('inventory.detail.noteNoUsage'),
      tone: 'text-ink-3',
    }
  } else {
    note = {
      text: t('inventory.detail.noteDays', {
        days: formatDays(row.days, locale),
        qty: rateQty,
        unit,
      }),
      tone: row.cls === 'low' ? 'text-amber' : 'text-ink-2',
    }
  }

  const facts: { label: string; value: string; faint?: boolean; warn?: boolean }[] = [
    {
      label: t('inventory.detail.factValue'),
      value:
        ingredient.unitCostMinor != null && Number.isFinite(ingredient.stockValueMinor)
          ? formatMoney(ingredient.stockValueMinor, costCurrency, locale)
          : t('inventory.detail.factValueNone'),
      faint: ingredient.unitCostMinor == null,
    },
    {
      label: t('inventory.detail.factCost', { unit }),
      value:
        costPerShown != null
          ? formatMoney(costPerShown, costCurrency, locale)
          : t('inventory.detail.factCostNone'),
      faint: costPerShown == null,
    },
    {
      label: t('inventory.detail.factUsedToday'),
      value:
        row.usedToday > 0
          ? `${formatShownQty(row.usedToday, ingredient, locale)} ${unit}`
          : t('inventory.detail.factUsedTodayNone'),
      faint: row.usedToday === 0,
    },
    {
      label: t('inventory.detail.factRate'),
      value:
        rateQty != null
          ? t('inventory.catalog.rateLine', { qty: rateQty, unit })
          : t('inventory.detail.factRateNone'),
      faint: rateQty == null,
    },
    {
      label: t('inventory.detail.factPack'),
      value:
        ingredient.packSize != null
          ? `${formatShownQty(ingredient.packSize, ingredient, locale)} ${unit}`
          : t('inventory.detail.factPackNone'),
      faint: ingredient.packSize == null,
    },
    { label: t('inventory.detail.factBase'), value: ingredient.unit, warn: conv },
    {
      label: t('inventory.detail.factShown'),
      value: ingredient.displayUnit ?? t('inventory.detail.factShownSame'),
      faint: ingredient.displayUnit == null,
    },
  ]

  const dateFmt = new Intl.DateTimeFormat(locale, { dateStyle: 'medium' })
  const px = rail ? 'px-[22px]' : 'px-4'

  return (
    <div className={cn(rail ? 'pb-7' : 'pb-8')}>
      <div className={cn(px, rail ? 'pt-5' : 'pt-3')}>
        {rail ? (
          <div className="font-display text-lg font-bold leading-tight tracking-display text-ink">
            {ingredient.name}
          </div>
        ) : null}
        <div className={cn('text-xs font-semibold text-ink-3', rail && 'mt-3.5')}>
          {t('inventory.detail.stockNow')}
        </div>
        <div className="mt-[7px] flex items-baseline gap-2">
          <span
            className={cn(
              'tnum font-mono font-bold leading-none tracking-display',
              rail ? 'text-2xl' : 'text-2xl',
              row.cls === 'zero' ? 'text-loss' : 'text-ink',
            )}
          >
            {formatShownQty(ingredient.stockQty, ingredient, locale)}
          </span>
          <span className="text-sm font-semibold text-ink-3">{unit}</span>
        </div>
        <div className={cn('mt-2 text-xs font-semibold leading-snug', note.tone)}>{note.text}</div>
      </div>

      {conv ? (
        <button
          type="button"
          onClick={() => navigate(`/inventory/${ingredient.id}/convert`)}
          className={cn(
            'mt-4 flex w-[calc(100%-2rem)] items-start gap-2.5 rounded-2xl border border-warning-line bg-amber-tint p-3.5 text-left transition-transform active:scale-[.99] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald',
            rail ? 'mx-[22px] w-[calc(100%-44px)]' : 'mx-4',
          )}
        >
          <TriangleAlert
            className="mt-0.5 size-[17px] shrink-0 text-amber"
            strokeWidth={2}
            aria-hidden="true"
          />
          <span className="min-w-0 flex-1">
            <span className="block text-xs font-bold leading-snug text-amber">
              {t('inventory.detail.convTitle', { unit: ingredient.unit })}
            </span>
            <span className="mt-1 block text-xs leading-relaxed text-amber-2">
              {t('inventory.detail.convBody', { unit: ingredient.unit })}
            </span>
            <span className="mt-[7px] block text-xs font-bold text-ink">
              {t('inventory.detail.convAction')}
            </span>
          </span>
        </button>
      ) : null}

      <div className={cn(px, 'mt-[18px] flex gap-2')}>
        <Button size={rail ? 'sm' : 'lg'} className="min-w-0 flex-1" onClick={onReceive}>
          {t('inventory.detail.receive')}
        </Button>
        <Button size={rail ? 'sm' : 'lg'} variant="outline" className="shrink-0" onClick={onSet}>
          {t('inventory.detail.set')}
        </Button>
        <Button
          size={rail ? 'sm' : 'lg'}
          variant="outline"
          className="shrink-0 px-0"
          style={{ width: rail ? 40 : 48 }}
          aria-label={t('inventory.detail.edit')}
          title={t('inventory.detail.edit')}
          onClick={() => navigate(`/inventory/${ingredient.id}/edit`)}
        >
          <Pencil className="size-[17px]" strokeWidth={1.9} aria-hidden="true" />
        </Button>
      </div>

      <div className={cn(px, 'mt-6')}>
        <MicroLabel>{t('inventory.detail.facts')}</MicroLabel>
        <dl className="mt-2.5">
          {facts.map((f) => (
            <div
              key={f.label}
              className="flex items-baseline gap-2.5 border-t border-line/60 py-[9px]"
            >
              <dt className="min-w-0 flex-1 text-xs font-medium leading-snug text-ink-3">
                {f.label}
              </dt>
              <dd
                className={cn(
                  'tnum shrink-0 font-mono text-xs font-semibold leading-snug',
                  f.warn ? 'text-amber' : f.faint ? 'text-ink-400' : 'text-ink',
                )}
              >
                {f.value}
              </dd>
            </div>
          ))}
        </dl>
      </div>

      <div className={cn(px, 'mt-6')}>
        <div className="flex items-center justify-between gap-2.5">
          <MicroLabel>{t('inventory.detail.lastCounts')}</MicroLabel>
          <button
            type="button"
            onClick={() => navigate('/inventory/history')}
            className="shrink-0 text-2xs font-bold text-profit-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            {t('inventory.detail.allHistory')}
          </button>
        </div>
        <div className="mt-2.5">
          {counts.length === 0 ? (
            <div className="border-t border-line/60 py-3 text-xs text-ink-400">
              {history.isLoading ? '' : t('inventory.detail.noCounts')}
            </div>
          ) : (
            counts.map((c) => {
              const v = c.line.varianceQty
              const tone = v === 0 ? 'text-ink-3' : v > 0 ? 'text-profit-ink' : 'text-loss'
              // A count taken before a unit conversion stays in the unit it was counted in.
              const bearing = lineBearing(c.line, data.byId)
              const countUnit = shownUnit(bearing)
              return (
                <div
                  key={c.stocktakeId}
                  className="flex items-center gap-2.5 border-t border-line/60 py-2.5"
                >
                  <span className="flex min-w-0 flex-1 flex-col gap-[3px]">
                    <span className="text-xs font-semibold text-ink">
                      {dateFmt.format(new Date(c.countedAt))}
                    </span>
                    <span className="tnum font-mono text-2xs text-ink-3">
                      {t('inventory.detail.countLine', {
                        system: formatShownQty(c.line.systemQty, bearing, locale),
                        counted: formatShownQty(c.line.countedQty, bearing, locale),
                        unit: countUnit,
                      })}
                    </span>
                  </span>
                  <span className="flex shrink-0 flex-col items-end gap-[3px] text-right">
                    <span className={cn('tnum font-mono text-xs font-bold', tone)}>
                      {formatSignedShownQty(v, bearing, locale)} {countUnit}
                    </span>
                    {c.line.unitCostMinor != null && v !== 0 ? (
                      <span className={cn('tnum font-mono text-2xs', tone)}>
                        {formatSignedMoney(
                          c.line.varianceValueMinor,
                          c.currency ?? currency,
                          locale,
                        )}
                      </span>
                    ) : null}
                  </span>
                </div>
              )
            })
          )}
        </div>
      </div>
    </div>
  )
}
