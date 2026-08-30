/**
 * StocktakeSheet — the stock opname (ADR 0038 phase 3 flow, re-aimed at INGREDIENTS by ADR
 * 0046). Lists the outlet's active ingredients (bahan) pre-filled with the current system
 * quantity; the operator corrects what differs from the physical count. Submitting POSTs
 * every line to the server, which adjusts each ingredient's stock to the count and books
 * valued net shrinkage for ingredients that carry a cost — the response is shown as a
 * summary (red = net loss, green = net gain, neutral = balanced), exactly like
 * RegisterSheet's over/short verdict. A count where NO line carries a cost posts nothing
 * (`currency` null on the response) and the summary says so instead of showing money.
 *
 * Reached from the till menu (features/pos-shell/layout/TillMenuSheet) and chained from the
 * register-close verdict — a stocktake can run any time the outlet is online.
 *
 * Money rule (rule 8): unit cost / variance value render via formatMoney; quantities via the
 * Intl-backed helpers in ./lib/qty (never a raw toString). Strings rule (rule 9): i18n keys only.
 *
 * ADR 0068 part 3 — before a count is submitted, every line runs through stocktakeVarianceGuard
 * (../lib/stocktakeVarianceGuard); a line that looks implausible (a ×1000 g/kg slip, an extra
 * zero…) pauses submit behind a confirm dialog naming the suspicious line(s) — the SAME "are you
 * sure?" shape as RegisterSheet's close-cash mismatch confirm, one step later in the flow.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { ChevronDown, ClipboardCheck, TriangleAlert, X } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { ListSkeleton, Skeleton } from '@/components/ui/Skeleton'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import { useItemSales, type ItemSalesResponse } from '@/features/pos/api'
import { localDayBounds } from '@/features/pos/salesHistoryApi'
import {
  useIngredients,
  useIngredientUsage,
  usageDayKey,
  type Ingredient,
} from '@/features/inventory/ingredientApi'
import {
  useSubmitIngredientStocktake,
  type IngredientStocktakeLineResponse,
  type IngredientStocktakeResponse,
} from '@/features/inventory/ingredientStocktakeApi'
import { allowsFraction, formatShownQty, parseShownQtyInput, shownUnit, toDisplayQty } from '@/features/inventory/lib/units'
import { formatQty, formatSignedQty } from './lib/qty'
import { checkStocktakeVariance, type StocktakeVarianceFlag, type StocktakeVarianceLine } from './lib/stocktakeVarianceGuard'

/** The three verdict tones shared by the live per-line preview and the post-submit summary. */
type Tone = 'loss' | 'gain' | 'balanced'

function toneOfVariance(varianceQty: number): Tone {
  return varianceQty === 0 ? 'balanced' : varianceQty > 0 ? 'gain' : 'loss'
}

const TONE_TEXT: Record<Tone, string> = {
  loss: 'text-loss',
  gain: 'text-profit-ink',
  balanced: 'text-ink-3',
}

const TONE_BANNER: Record<Tone, string> = {
  loss: 'bg-tint-loss text-loss',
  gain: 'bg-tint-profit text-profit-ink',
  balanced: 'bg-ink-50 text-ink-2',
}

export function StocktakeSheet({
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
  const navigate = useNavigate()
  const ingredientsQuery = useIngredients(session)
  const submit = useSubmitIngredientStocktake(session)

  // "Sold today" reference for the opname — units + omzet per MENU item over the local day, a
  // read-only aid to reconcile the physical count (the stocktake itself is ingredient-keyed, ADR
  // 0046). Day bounds are truncated to the calendar day, so from/to are stable across renders.
  const { from, to } = localDayBounds(new Date())
  const soldTodayQuery = useItemSales(session, from, to)
  // Per-ingredient "terpakai hari ini" (V42) — how much today's sales consumed by recipe, so the
  // operator can sanity-check the prefilled system figure. Absent = 0 (no sales of it today).
  const usageQuery = useIngredientUsage(session, usageDayKey(), true)
  const usedById = new Map((usageQuery.data ?? []).map((u) => [u.ingredientId, u.qtyUsed]))

  const ingredients: Ingredient[] = ingredientsQuery.data ?? []

  // Only the operator's EDITS are kept in state, keyed by ingredientId — not a full seeded map.
  // A row with no override effectively reads as its current system quantity (see `valueFor`
  // below), so every ingredient starts "counted" at the system qty and the operator only has to
  // touch what differs. Avoids syncing query data into state (react-hooks/set-state-in-effect).
  const [overrides, setOverrides] = useState<Record<string, string>>({})
  // Held after a successful submit so the summary stays visible.
  const [result, setResult] = useState<IngredientStocktakeResponse | null>(null)
  // ADR 0068 part 3 — set when Submit trips the variance guard: holds the flagged line(s) so the
  // confirm dialog can name them. Null = no confirm showing (either nothing tripped, or it's
  // already been dismissed/confirmed).
  const [pendingVarianceFlags, setPendingVarianceFlags] = useState<StocktakeVarianceFlag[] | null>(
    null,
  )
  // Inner confirm layer, inline conditional JSX within this always-mounted-while-open component.
  useBackDismiss(
    () => setPendingVarianceFlags(null),
    pendingVarianceFlags != null && pendingVarianceFlags.length > 0,
  )

  // Seeded (and re-parsed) in the ingredient's SHOWN unit — kg/liter items start counted at the
  // system quantity expressed as a decimal (e.g. "1.5"), not the raw base-unit integer.
  function valueFor(ing: Ingredient): string {
    return overrides[ing.id] ?? String(toDisplayQty(ing.stockQty, ing))
  }

  const parsedCounts = new Map<string, number | null>(
    ingredients.map((ing) => [ing.id, parseShownQtyInput(valueFor(ing), ing)]),
  )
  const allCounted = ingredients.length > 0 && [...parsedCounts.values()].every((v) => v !== null)
  // Lookup for the confirm dialog — flags carry only ingredientId + numbers (the guard is a plain
  // pure helper with no ingredient/display knowledge, see stocktakeVarianceGuard.ts).
  const ingredientById = new Map(ingredients.map((ing) => [ing.id, ing]))

  // ADR 0068 part 3 guard input — BASE-unit quantities (the same numbers the submit payload
  // carries), the ingredient's own unit cost/currency (defaulted to the company base currency when
  // the ingredient carries none, same fallback StocktakeIngredientRow already uses for its preview).
  const varianceLines: StocktakeVarianceLine[] = ingredients.map((ing) => ({
    ingredientId: ing.id,
    systemQty: ing.stockQty,
    countedQty: parsedCounts.get(ing.id) ?? 0,
    unitCostMinor: ing.unitCostMinor,
    currency: ing.costCurrency ?? currency,
  }))

  function buildSubmitLines() {
    return ingredients.map((ing) => ({
      ingredientId: ing.id,
      countedQty: parsedCounts.get(ing.id) ?? 0,
    }))
  }

  function doSubmit() {
    setPendingVarianceFlags(null)
    submit.mutate(buildSubmitLines(), {
      onSuccess: (res) => {
        if (res) setResult(res)
      },
    })
  }

  // Submit tapped: if any line looks implausible (a ×1000 g/kg slip, an extra zero…), hold the
  // submit behind a confirm dialog naming the suspicious line(s) instead of posting straight away —
  // a safety net, not a hard block (the owner can always "Save anyway", ADR 0068 §3).
  function handleSubmitClick() {
    const flags = checkStocktakeVariance(varianceLines)
    if (flags.length > 0) {
      setPendingVarianceFlags(flags)
      return
    }
    doSubmit()
  }

  const submitErrorMessage = (): string => {
    const err = submit.error
    if (err instanceof ApiError && (err.problem?.detail || err.problem?.title)) {
      return err.problem.detail || err.problem.title || t('stocktake.errorGeneric')
    }
    return t('stocktake.errorGeneric')
  }

  return (
    <>
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 p-0 backdrop-blur-sm sm:items-center sm:p-4"
      role="dialog"
      aria-modal="true"
      aria-label={t('stocktake.title')}
    >
      <div className="reveal flex max-h-full w-full max-w-lg flex-col overflow-hidden rounded-t-2xl border border-line bg-surface shadow-lg sm:rounded-2xl">
        {/* Header */}
        <div className="flex shrink-0 items-center justify-between border-b border-line px-5 py-4">
          <h2 className="flex items-center gap-2 font-display text-lg font-semibold text-ink">
            <ClipboardCheck className="size-5 text-emerald-2" aria-hidden="true" />
            {t('stocktake.title')}
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('common.close')}
            className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
          >
            <X className="size-4" />
          </button>
        </div>

        {result ? (
          <StocktakeSummary result={result} currency={currency} locale={locale} onDone={onClose} />
        ) : ingredientsQuery.isLoading ? (
          <>
            <div className="shrink-0 px-5 pt-3">
              <Skeleton className="h-3 w-4/5" />
            </div>
            <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 py-3">
              <ListSkeleton rows={5} className="rounded-none border-0" />
            </div>
            <div className="shrink-0 border-t border-line px-5 py-4">
              <Skeleton className="h-11 rounded-xl" />
            </div>
          </>
        ) : ingredientsQuery.isError ? (
          <div className="px-5 py-10 text-center">
            <TriangleAlert className="mx-auto mb-2 size-5 text-loss" aria-hidden="true" />
            <p className="text-sm text-loss">{t('stocktake.loadError')}</p>
          </div>
        ) : ingredients.length === 0 ? (
          <div className="px-5 py-10 text-center">
            <p className="text-sm text-ink-3">{t('stocktake.emptyHint')}</p>
            {/* The hint names the ingredient screen — this button actually goes there. Close
                FIRST: the standalone hosts (MobileTabBarGate) stay mounted across route
                changes, and a lingering fixed overlay would cover the destination page. */}
            <Button
              variant="secondary"
              className="mt-4"
              onClick={() => {
                onClose()
                navigate('/inventory')
              }}
            >
              {t('stocktake.emptyCta')}
            </Button>
          </div>
        ) : (
          <>
            <SoldTodayPanel
              items={soldTodayQuery.data ?? []}
              loading={soldTodayQuery.isLoading}
              currency={currency}
              locale={locale}
            />
            <p className="shrink-0 px-5 pt-3 text-xs leading-relaxed text-ink-3">{t('stocktake.entryHint')}</p>
            <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 py-3">
              <ul className="space-y-2">
                {ingredients.map((ing) => (
                  <StocktakeIngredientRow
                    key={ing.id}
                    ingredient={ing}
                    value={valueFor(ing)}
                    onChange={(raw) => setOverrides((p) => ({ ...p, [ing.id]: raw }))}
                    currency={currency}
                    locale={locale}
                    usedToday={usedById.get(ing.id) ?? 0}
                  />
                ))}
              </ul>
            </div>
            <div className="shrink-0 space-y-3 border-t border-line px-5 py-4">
              {submit.isError ? (
                <p className="text-xs text-loss" role="alert">
                  {submitErrorMessage()}
                </p>
              ) : null}
              <Button
                className="w-full"
                data-testid="stocktake-submit"
                disabled={!allCounted || submit.isPending}
                onClick={handleSubmitClick}
              >
                {submit.isPending ? <Spinner /> : t('stocktake.submitAction')}
              </Button>
            </div>
          </>
        )}
      </div>
    </div>

    {/* ADR 0068 part 3 — variance confirm: one or more counts tripped the plausibility guard, so
        make the operator reconfirm before the count posts (a fat-finger/×1000-slip safety net; the
        server still records + values whatever is submitted, this only gates whether it's sent). */}
    {pendingVarianceFlags && pendingVarianceFlags.length > 0 ? (
      <div
        className="fixed inset-0 z-[60] grid place-items-center bg-black/50 p-4 backdrop-blur-sm"
        role="dialog"
        aria-modal="true"
        aria-label={t('stocktake.varianceGuardTitle')}
      >
        <div
          className="reveal max-h-full w-full max-w-sm space-y-4 overflow-y-auto overscroll-contain rounded-card border border-line bg-surface p-5 shadow-lg"
          data-testid="stocktake-variance-confirm"
        >
          <h3 className="font-display text-lg font-semibold text-ink">
            {t('stocktake.varianceGuardTitle')}
          </h3>
          <p className="text-sm text-ink-3">{t('stocktake.varianceGuardBody')}</p>
          <ul className="space-y-2 rounded-xl bg-ink-50 px-4 py-3">
            {pendingVarianceFlags.map((flag) => {
              const ing = ingredientById.get(flag.ingredientId)
              if (!ing) return null
              return (
                <li key={flag.ingredientId} className="text-sm">
                  <div className="font-medium text-ink">{ing.name}</div>
                  <div className="tnum text-xs text-loss">
                    {t('stocktake.lineCounts', {
                      system: formatShownQty(flag.systemQty, ing, locale),
                      counted: formatShownQty(flag.countedQty, ing, locale),
                    })}{' '}
                    {shownUnit(ing)}
                  </div>
                </li>
              )
            })}
          </ul>
          <div className="flex gap-2">
            <Button
              variant="outline"
              className="flex-1"
              data-testid="stocktake-variance-recount"
              onClick={() => setPendingVarianceFlags(null)}
            >
              {t('stocktake.varianceGuardRecount')}
            </Button>
            <Button
              className="flex-1"
              data-testid="stocktake-variance-proceed"
              disabled={submit.isPending}
              onClick={doSubmit}
            >
              {submit.isPending ? <Spinner /> : t('stocktake.varianceGuardProceed')}
            </Button>
          </div>
        </div>
      </div>
    ) : null}
    </>
  )
}

/**
 * A collapsible, read-only "items sold today" reference in the stock-opname flow — units + gross
 * omzet per MENU item over the local day (from useItemSales). Collapsed by default so the ingredient
 * count stays the focus; expanded it caps its height and scrolls. Purely informational (helps the
 * operator sanity-check the physical count); it never feeds the ingredient submission.
 */
function SoldTodayPanel({
  items,
  loading,
  currency,
  locale,
}: {
  items: ItemSalesResponse[]
  loading: boolean
  currency: string
  locale: string
}) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)

  return (
    <div className="shrink-0 border-b border-line px-5 pt-3">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="flex w-full items-center gap-2 rounded-lg py-1.5 text-left text-[13px] font-semibold text-ink-2 hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
      >
        <ChevronDown
          className={cn('size-4 shrink-0 text-ink-3 transition-transform', open && 'rotate-180')}
          aria-hidden="true"
        />
        <span className="flex-1">{t('stocktake.soldTodayTitle')}</span>
      </button>
      {open ? (
        <div className="max-h-44 overflow-y-auto overscroll-contain pb-2">
          {loading ? (
            <p className="py-2 text-center text-xs text-ink-3">…</p>
          ) : items.length === 0 ? (
            <p className="py-2 text-center text-xs text-ink-3">{t('stocktake.soldTodayEmpty')}</p>
          ) : (
            <ul className="divide-y divide-line">
              {items.map((it) => (
                <li key={it.menuItemId} className="flex items-center gap-3 py-1.5 text-sm">
                  <span className="tnum w-9 shrink-0 font-mono font-bold text-ink">
                    {it.soldQty}×
                  </span>
                  <span className="min-w-0 flex-1 truncate text-ink-2">{it.name}</span>
                  <span className="tnum shrink-0 font-mono text-[12px] text-ink-3">
                    {formatMoney(it.revenueMinor, currency, locale)}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}
    </div>
  )
}

function StocktakeIngredientRow({
  ingredient,
  value,
  onChange,
  currency,
  locale,
  usedToday,
}: {
  ingredient: Ingredient
  value: string
  onChange: (raw: string) => void
  currency: string
  locale: string
  /** Quantity today's sales consumed by recipe ("terpakai hari ini", V42) — 0 when none. */
  usedToday: number
}) {
  const { t } = useTranslation()
  const systemQty = ingredient.stockQty
  const countedQty = parseShownQtyInput(value, ingredient)
  const varianceQty = countedQty != null ? countedQty - systemQty : null
  const tone = varianceQty != null ? toneOfVariance(varianceQty) : null
  // Client-side preview only (the server recomputes authoritatively on submit) — a simple
  // qty × unit-cost, never rounded beyond integer minor units. Stays in the BASE quantity
  // (grams) — unitCostMinor is per-base-unit, so a shown (kg) value would distort it ~1000×.
  const valuePreviewMinor =
    varianceQty != null && ingredient.unitCostMinor != null
      ? varianceQty * ingredient.unitCostMinor
      : null
  const previewCurrency = ingredient.costCurrency ?? currency
  const fractional = allowsFraction(ingredient)
  // Signed, locale-aware, in the SHOWN unit — up to 3 fraction digits for kg/liter.
  const varianceDisplay =
    varianceQty != null
      ? new Intl.NumberFormat(locale, {
          signDisplay: 'exceptZero',
          maximumFractionDigits: fractional ? 3 : 0,
        }).format(toDisplayQty(varianceQty, ingredient))
      : null

  return (
    <li className="flex items-center gap-3 rounded-xl border border-line bg-paper px-3 py-2.5">
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-medium text-ink">{ingredient.name}</div>
        <div className="tnum mt-0.5 text-xs text-ink-3">
          {t('stocktake.systemQty', {
            qty: formatShownQty(systemQty, ingredient, locale),
            unit: shownUnit(ingredient),
          })}
        </div>
        {usedToday > 0 ? (
          <div className="tnum mt-0.5 text-[11px] text-ink-3">
            {t('stocktake.usedToday', {
              qty: formatShownQty(usedToday, ingredient, locale),
              unit: shownUnit(ingredient),
            })}
          </div>
        ) : null}
      </div>

      <input
        aria-label={t('stocktake.countedForItem', { name: ingredient.name })}
        type="number"
        min="0"
        step={fractional ? 'any' : '1'}
        inputMode={fractional ? 'decimal' : 'numeric'}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="0"
        className="h-10 w-20 shrink-0 rounded-lg border border-line bg-surface px-2 text-right font-mono text-sm tnum text-ink placeholder:text-ink-3/50 focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/10"
      />

      <div className="w-24 shrink-0 text-right">
        {varianceQty != null ? (
          <>
            <div className={cn('tnum font-mono text-sm font-semibold', tone ? TONE_TEXT[tone] : undefined)}>
              {varianceDisplay}
            </div>
            {valuePreviewMinor != null ? (
              <div className={cn('tnum font-mono text-[11px]', tone ? TONE_TEXT[tone] : undefined)}>
                {formatMoney(Math.abs(valuePreviewMinor), previewCurrency, locale)}
              </div>
            ) : null}
          </>
        ) : (
          <span className="text-xs text-ink-3">{t('stocktake.notCounted')}</span>
        )}
      </div>
    </li>
  )
}

function StocktakeSummary({
  result,
  currency,
  locale,
  onDone,
}: {
  result: IngredientStocktakeResponse
  currency: string
  locale: string
  onDone: () => void
}) {
  const { t } = useTranslation()
  const tone: Tone = result.shrinkageMinor === 0 ? 'balanced' : result.shrinkageMinor > 0 ? 'loss' : 'gain'
  const variedLines = result.lines.filter((l) => l.varianceQty !== 0)

  return (
    <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain" data-testid="stocktake-summary">
      <div className="space-y-3 px-5 py-5">
        <div className="flex items-baseline justify-between text-sm">
          <span className="text-ink-3">{t('stocktake.countedAt')}</span>
          <span className="font-semibold text-ink">
            {new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(
              new Date(result.countedAt),
            )}
          </span>
        </div>

        <div className={cn('rounded-xl px-4 py-3 text-center', TONE_BANNER[tone])}>
          <div className="text-[12px] font-semibold uppercase tracking-[.06em]">
            {tone === 'balanced'
              ? t('stocktake.resultBalanced')
              : tone === 'loss'
                ? t('stocktake.resultLoss')
                : t('stocktake.resultGain')}
          </div>
          {/* currency null = no counted line carried a cost — nothing was posted, so showing a
              zero money figure would imply a valuation that never happened. */}
          {result.currency != null ? (
            <div className="tnum mt-1 font-mono text-2xl font-bold">
              {formatMoney(Math.abs(result.shrinkageMinor), result.currency, locale)}
            </div>
          ) : (
            <div className="mt-1 text-xs">{t('stocktake.noValuedLines')}</div>
          )}
        </div>

        {variedLines.length > 0 ? (
          <div className="rounded-xl border border-line bg-paper px-3 py-2">
            <div className="mb-1.5 text-[11px] font-semibold uppercase tracking-[.05em] text-ink-3">
              {t('stocktake.varianceLinesTitle')}
            </div>
            <ul className="divide-y divide-line">
              {variedLines.map((line) => (
                <StocktakeVarianceLine
                  key={line.ingredientId}
                  line={line}
                  currency={result.currency ?? currency}
                  locale={locale}
                />
              ))}
            </ul>
          </div>
        ) : null}
      </div>

      <div className="sticky bottom-0 border-t border-line bg-surface px-5 py-4">
        <Button className="w-full" onClick={onDone}>
          {t('stocktake.done')}
        </Button>
      </div>
    </div>
  )
}

function StocktakeVarianceLine({
  line,
  currency,
  locale,
}: {
  line: IngredientStocktakeLineResponse
  currency: string
  locale: string
}) {
  const { t } = useTranslation()
  const tone = toneOfVariance(line.varianceQty)
  return (
    <li className="flex items-center justify-between gap-3 py-1.5 text-sm">
      <div className="min-w-0 flex-1">
        <div className="truncate text-ink">{line.name}</div>
        <div className="tnum text-xs text-ink-3">
          {t('stocktake.lineCounts', {
            system: formatQty(line.systemQty, locale),
            counted: formatQty(line.countedQty, locale),
          })}{' '}
          {line.unit}
        </div>
      </div>
      <div className="shrink-0 text-right">
        <div className={cn('tnum font-mono text-sm font-semibold', TONE_TEXT[tone])}>
          {formatSignedQty(line.varianceQty, locale)}
        </div>
        {line.unitCostMinor != null ? (
          <div className={cn('tnum font-mono text-[11px]', TONE_TEXT[tone])}>
            {formatMoney(Math.abs(line.varianceValueMinor), currency, locale)}
          </div>
        ) : null}
      </div>
    </li>
  )
}
