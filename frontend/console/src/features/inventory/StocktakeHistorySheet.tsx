/**
 * StocktakeHistorySheet — riwayat stock opname (ADR 0046 + V42). Lists the outlet's past
 * ingredient stocktakes (newest first, server cap 50); tapping one opens its lines: sistem →
 * terhitung → selisih, PLUS "terpakai hari itu" — how much of each ingredient the day's sales
 * consumed by recipe (the V42 per-day usage aggregate, fetched for the stocktake's outlet-local
 * day). Usage accrues from the feature's deployment forward — older days show no terpakai figure.
 *
 * Money rule (rule 8): shrinkage renders via formatMoney. Strings rule (rule 9): i18n keys only.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ArrowLeft, ClipboardList, TriangleAlert, X } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { useScrollLock } from '@/components/mobile/useScrollLock'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import { useIngredientUsage, usageDayKey } from './ingredientApi'
import {
  useStocktakeHistory,
  type IngredientStocktakeLineResponse,
  type IngredientStocktakeResponse,
} from './ingredientStocktakeApi'

export function StocktakeHistorySheet({
  session,
  currency,
  locale,
  onClose,
}: {
  session: CompanySession
  currency: string
  locale: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  useBackDismiss(onClose)
  useScrollLock()
  const history = useStocktakeHistory(session, true)
  const [selected, setSelected] = useState<IngredientStocktakeResponse | null>(null)

  const rows = history.data ?? []
  const dateTimeFmt = new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' })

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 p-0 backdrop-blur-sm sm:items-center sm:p-4"
      role="dialog"
      aria-modal="true"
      aria-label={t('stocktake.historyTitle')}
    >
      <div className="reveal flex max-h-full w-full max-w-lg flex-col overflow-hidden rounded-t-2xl border border-line bg-surface shadow-lg sm:rounded-2xl">
        <div className="flex shrink-0 items-center gap-2 border-b border-line px-5 py-4">
          {selected ? (
            <button
              type="button"
              onClick={() => setSelected(null)}
              aria-label={t('common.back')}
              className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover"
            >
              <ArrowLeft className="size-4" aria-hidden />
            </button>
          ) : (
            <ClipboardList className="size-5 text-emerald-2" aria-hidden />
          )}
          <h2 className="min-w-0 flex-1 truncate font-display text-lg font-semibold text-ink">
            {selected
              ? dateTimeFmt.format(new Date(selected.countedAt))
              : t('stocktake.historyTitle')}
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('common.close')}
            className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover"
          >
            <X className="size-4" aria-hidden />
          </button>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 py-3">
          {selected ? (
            <StocktakeHistoryDetail
              session={session}
              stocktake={selected}
              currency={currency}
              locale={locale}
            />
          ) : history.isLoading ? (
            <ListSkeleton rows={5} className="rounded-none border-0" />
          ) : history.isError ? (
            <div className="py-8 text-center">
              <TriangleAlert className="mx-auto mb-2 size-5 text-loss" aria-hidden />
              <p className="text-sm text-loss">{t('stocktake.historyError')}</p>
            </div>
          ) : rows.length === 0 ? (
            <p className="py-8 text-center text-sm text-ink-3">{t('stocktake.historyEmpty')}</p>
          ) : (
            <ul className="space-y-2">
              {rows.map((st) => {
                const varied = st.lines.filter((l) => l.varianceQty !== 0).length
                const tone =
                  st.shrinkageMinor === 0 ? 'text-ink-3' : st.shrinkageMinor > 0 ? 'text-loss' : 'text-profit-ink'
                return (
                  <li key={st.id}>
                    <button
                      type="button"
                      onClick={() => setSelected(st)}
                      className="flex w-full items-center gap-3 rounded-xl border border-line bg-paper px-3 py-2.5 text-left transition-colors hover:border-emerald-line"
                    >
                      <div className="min-w-0 flex-1">
                        <div className="truncate text-sm font-medium text-ink">
                          {dateTimeFmt.format(new Date(st.countedAt))}
                        </div>
                        <div className="mt-0.5 text-xs text-ink-3">
                          {t('stocktake.historyVariedCount', {
                            formatted: new Intl.NumberFormat(locale).format(varied),
                          })}
                        </div>
                      </div>
                      {st.currency != null ? (
                        <span className={cn('tnum shrink-0 font-mono text-sm font-semibold', tone)}>
                          {formatMoney(Math.abs(st.shrinkageMinor), st.currency, locale)}
                        </span>
                      ) : null}
                    </button>
                  </li>
                )
              })}
            </ul>
          )}
        </div>
      </div>
    </div>
  )
}

/** One stocktake's lines: sistem → terhitung → selisih + "terpakai hari itu" (V42 usage). */
function StocktakeHistoryDetail({
  session,
  stocktake,
  currency,
  locale,
}: {
  session: CompanySession
  stocktake: IngredientStocktakeResponse
  currency: string
  locale: string
}) {
  const { t } = useTranslation()
  // Usage for the stocktake's OUTLET-LOCAL day (Asia/Jakarta — the server's attribution zone).
  const dayKey = usageDayKey(new Date(stocktake.countedAt))
  const usageQuery = useIngredientUsage(session, dayKey, true)
  const usedById = new Map((usageQuery.data ?? []).map((u) => [u.ingredientId, u.qtyUsed]))
  const nf = new Intl.NumberFormat(locale)
  const signed = new Intl.NumberFormat(locale, { signDisplay: 'exceptZero' })

  return (
    <ul className="divide-y divide-line">
      {stocktake.lines.map((line: IngredientStocktakeLineResponse) => {
        const used = usedById.get(line.ingredientId) ?? 0
        const tone =
          line.varianceQty === 0 ? 'text-ink-3' : line.varianceQty > 0 ? 'text-profit-ink' : 'text-loss'
        return (
          <li key={line.ingredientId} className="flex items-center gap-3 py-2">
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm font-medium text-ink">{line.name}</div>
              <div className="tnum mt-0.5 text-xs text-ink-3">
                {t('stocktake.lineCounts', {
                  system: nf.format(line.systemQty),
                  counted: nf.format(line.countedQty),
                })}{' '}
                {line.unit}
              </div>
              {used > 0 ? (
                <div className="tnum mt-0.5 text-[11px] text-ink-3">
                  {t('stocktake.usedThatDay', { qty: nf.format(used), unit: line.unit })}
                </div>
              ) : null}
            </div>
            <div className="shrink-0 text-right">
              <div className={cn('tnum font-mono text-sm font-semibold', tone)}>
                {signed.format(line.varianceQty)}
              </div>
              {line.unitCostMinor != null ? (
                <div className={cn('tnum font-mono text-[11px]', tone)}>
                  {formatMoney(
                    Math.abs(line.varianceValueMinor),
                    stocktake.currency ?? currency,
                    locale,
                  )}
                </div>
              ) : null}
            </div>
          </li>
        )
      })}
    </ul>
  )
}
