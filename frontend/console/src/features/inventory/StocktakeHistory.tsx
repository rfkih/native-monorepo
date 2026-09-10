/**
 * StocktakeHistory — riwayat stock opname (ADR 0046 + V42) as two screens: the outlet's past
 * counts (newest first, server cap 50) at `/inventory/history`, and one count's lines at
 * `/inventory/history/:stocktakeId` — sistem → hitung → selisih, PLUS "terpakai hari itu" (the
 * V42 per-day usage aggregate, fetched for the count's outlet-local day; usage accrues from the
 * feature's deployment forward, so older days show none).
 *
 * The bodies (`StocktakeHistoryList`, `StocktakeHistoryLines`) are shared with the overlay the
 * standalone opname host opens (`StocktakeHistorySheet`). Quantities go through the SAME display
 * helpers the catalog uses: a line carries the base unit it was counted in, so it is formatted
 * against the catalog's ingredient (8,4 kg, not 8.400 g) while that base still matches, and stays
 * in its own unit after a conversion or once the ingredient is gone (`lineBearing`). Money rule
 * (rule 8): shrinkage renders via formatMoney / formatSignedMoney.
 */
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { ChevronRight, TriangleAlert } from 'lucide-react'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { cn } from '@/lib/cn'
import { formatMoney, formatSignedMoney } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import { InventoryChrome } from './InventoryChrome'
import type { InventoryData } from './inventoryData'
import { useIngredientUsage, usageDayKey, type Ingredient } from './ingredientApi'
import { useStocktakeHistory, type IngredientStocktakeResponse } from './ingredientStocktakeApi'
import { lineBearing, variedCount } from './lib/stocktakeLines'
import { formatShownQty, formatSignedShownQty, shownUnit } from './lib/units'

/** Sign convention (server-fixed): parent `shrinkageMinor` POSITIVE = net loss. */
function shrinkTone(shrinkageMinor: number): string {
  return shrinkageMinor === 0 ? 'text-ink-3' : shrinkageMinor > 0 ? 'text-loss' : 'text-profit-ink'
}

export function StocktakeHistoryPage({
  data,
  companyName,
  currency,
  locale,
  stocktakeId,
}: {
  data: InventoryData
  companyName: string
  currency: string
  locale: string
  stocktakeId: string | null
}) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const history = useStocktakeHistory(data.session, true)
  const selected = stocktakeId
    ? ((history.data ?? []).find((s) => s.id === stocktakeId) ?? null)
    : null
  const dateTimeFmt = new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' })

  return (
    <InventoryChrome
      title={
        selected ? dateTimeFmt.format(new Date(selected.countedAt)) : t('stocktake.historyTitle')
      }
      subtitle={
        selected
          ? t('inventory.history.subtitleDetail')
          : t('inventory.history.subtitleList', { company: companyName })
      }
      backFallback={selected ? '/inventory/history' : '/inventory'}
    >
      <div className="mx-auto max-w-2xl px-4 pb-8 pt-1.5">
        {selected ? (
          <StocktakeHistoryLines
            session={data.session}
            stocktake={selected}
            byId={data.byId}
            currency={currency}
            locale={locale}
          />
        ) : (
          <>
            <p className="text-xs leading-relaxed text-ink-3 text-pretty">
              {t('inventory.history.intro')}
            </p>
            <div className="mt-3.5">
              <StocktakeHistoryList
                history={history}
                locale={locale}
                onOpen={(id) => navigate(`/inventory/history/${id}`)}
              />
            </div>
          </>
        )}
      </div>
    </InventoryChrome>
  )
}

export function StocktakeHistoryList({
  history,
  locale,
  onOpen,
}: {
  history: ReturnType<typeof useStocktakeHistory>
  locale: string
  onOpen: (stocktakeId: string) => void
}) {
  const { t } = useTranslation()
  const dateTimeFmt = new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' })
  const rows = history.data ?? []

  if (history.isLoading) return <ListSkeleton rows={5} className="rounded-none border-0" />
  if (history.isError) {
    return (
      <div className="py-8 text-center">
        <TriangleAlert className="mx-auto mb-2 size-5 text-loss" aria-hidden="true" />
        <p className="text-sm text-loss">{t('stocktake.historyError')}</p>
      </div>
    )
  }
  if (rows.length === 0) {
    return <p className="py-8 text-center text-sm text-ink-3">{t('stocktake.historyEmpty')}</p>
  }

  return (
    <ul>
      {rows.map((st) => {
        const varied = variedCount(st)
        return (
          <li key={st.id}>
            <button
              type="button"
              onClick={() => onOpen(st.id)}
              className="flex min-h-[66px] w-full items-center gap-3 border-t border-line/60 px-1 py-3 text-left transition-colors active:bg-ink-50 focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald"
            >
              <span className="flex min-w-0 flex-1 flex-col gap-1">
                <span className="text-sm font-semibold leading-tight text-ink">
                  {dateTimeFmt.format(new Date(st.countedAt))}
                </span>
                <span className="text-xs font-medium text-ink-3">
                  {varied === 0
                    ? t('inventory.history.allMatch')
                    : t('inventory.history.varied', { count: varied })}
                </span>
              </span>
              <span className="flex shrink-0 items-center gap-2">
                {st.currency != null ? (
                  <span
                    className={cn(
                      'tnum font-mono text-sm font-bold',
                      shrinkTone(st.shrinkageMinor),
                    )}
                  >
                    {st.shrinkageMinor === 0
                      ? formatMoney(0, st.currency, locale)
                      : formatSignedMoney(-st.shrinkageMinor, st.currency, locale)}
                  </span>
                ) : null}
                <ChevronRight
                  className="size-[15px] text-ink-400"
                  strokeWidth={2.2}
                  aria-hidden="true"
                />
              </span>
            </button>
          </li>
        )
      })}
    </ul>
  )
}

/** One stocktake's lines: sistem → terhitung → selisih + "terpakai hari itu" (V42 usage). */
export function StocktakeHistoryLines({
  session,
  stocktake,
  byId,
  currency,
  locale,
}: {
  session: CompanySession
  stocktake: IngredientStocktakeResponse
  byId: ReadonlyMap<string, Ingredient>
  currency: string
  locale: string
}) {
  const { t } = useTranslation()
  // Usage for the stocktake's OUTLET-LOCAL day (Asia/Jakarta — the server's attribution zone).
  const dayKey = usageDayKey(new Date(stocktake.countedAt))
  const usageQuery = useIngredientUsage(session, dayKey, true)
  const usedById = new Map((usageQuery.data ?? []).map((u) => [u.ingredientId, u.qtyUsed]))
  const money = stocktake.currency ?? currency

  return (
    <div>
      <div className="flex items-baseline gap-2">
        <span
          className={cn(
            'tnum font-mono text-xl font-bold tracking-[-0.02em]',
            shrinkTone(stocktake.shrinkageMinor),
          )}
        >
          {stocktake.shrinkageMinor === 0
            ? formatMoney(0, money, locale)
            : formatSignedMoney(-stocktake.shrinkageMinor, money, locale)}
        </span>
        <span className="text-xs font-semibold text-ink-3">
          {stocktake.shrinkageMinor === 0
            ? t('inventory.history.balanced')
            : stocktake.shrinkageMinor > 0
              ? t('inventory.history.shrinkBooked')
              : t('inventory.history.foundMore')}
        </span>
      </div>
      <ul className="mt-4">
        {stocktake.lines.map((line) => {
          const bearing = lineBearing(line, byId)
          const unit = shownUnit(bearing)
          const used = usedById.get(line.ingredientId) ?? 0
          const v = line.varianceQty
          const tone = v === 0 ? 'text-ink-3' : v > 0 ? 'text-profit-ink' : 'text-loss'
          return (
            <li
              key={line.ingredientId}
              className="flex items-center gap-2.5 border-t border-line/60 py-[11px]"
            >
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <div className="truncate text-xs font-semibold text-ink">{line.name}</div>
                <div className="tnum font-mono text-[10.5px] text-ink-3">
                  {t('inventory.detail.countLine', {
                    system: formatShownQty(line.systemQty, bearing, locale),
                    counted: formatShownQty(line.countedQty, bearing, locale),
                    unit,
                  })}
                </div>
                {used > 0 ? (
                  <div className="text-[10.5px] font-medium text-ink-400">
                    {t('inventory.history.usedThatDay', {
                      qty: formatShownQty(used, bearing, locale),
                      unit,
                    })}
                  </div>
                ) : null}
              </div>
              <div className="flex shrink-0 flex-col items-end gap-[3px] text-right">
                <div className={cn('tnum font-mono text-xs font-bold', tone)}>
                  {formatSignedShownQty(v, bearing, locale)} {unit}
                </div>
                {line.unitCostMinor != null && v !== 0 ? (
                  <div className={cn('tnum font-mono text-[10.5px]', tone)}>
                    {formatSignedMoney(line.varianceValueMinor, money, locale)}
                  </div>
                ) : null}
              </div>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
