/**
 * StocktakeSheet — inventory stocktake (ADR 0038 phase 3, "daily close v2"). Lists the outlet's
 * TRACKED menu items (stockQuantity != null) pre-filled with the current system quantity; the
 * operator corrects any item that differs from the physical count. Submitting POSTs every tracked
 * line to the server, which adjusts stock to the count and books valued net shrinkage for items
 * that carry a `unitCostMinor` — the response is then shown as a summary (red = net loss, green =
 * net gain, neutral = balanced), exactly like RegisterSheet's over/short verdict.
 *
 * Reached from the till menu (features/pos-shell/layout/TillMenuSheet), independent of the register
 * close — a stocktake can be run any time the outlet is online, whether or not a drawer is open.
 *
 * Money rule (rule 8): unit cost / variance value render via formatMoney; quantities via the
 * Intl-backed helpers in ./lib/qty (never a raw toString). Strings rule (rule 9): i18n keys only.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { ClipboardCheck, TriangleAlert, X } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import { useMenu, type MenuItem } from '@/features/pos/api'
import { formatQty, formatSignedQty, parseQtyInput } from './lib/qty'
import { useSubmitStocktake, type StocktakeLineResponse, type StocktakeResponse } from './stocktakeApi'

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
  const navigate = useNavigate()
  const menuQuery = useMenu(session)
  const submit = useSubmitStocktake(session)

  const trackedItems: MenuItem[] = (menuQuery.data ?? []).filter((i) => i.stockQuantity != null)

  // Only the operator's EDITS are kept in state, keyed by menuItemId — not a full seeded map. An
  // item with no override effectively reads as its current system quantity (see `valueFor` below),
  // so every item starts "counted" at the system qty and the operator only has to touch what
  // differs. This avoids syncing query data into state via an effect (react-hooks/set-state-in-effect).
  const [overrides, setOverrides] = useState<Record<string, string>>({})
  // Held after a successful submit so the summary stays visible.
  const [result, setResult] = useState<StocktakeResponse | null>(null)

  function valueFor(item: MenuItem): string {
    return overrides[item.id] ?? String(item.stockQuantity ?? 0)
  }

  const parsedCounts = new Map<string, number | null>(
    trackedItems.map((item) => [item.id, parseQtyInput(valueFor(item))]),
  )
  const allCounted = trackedItems.length > 0 && [...parsedCounts.values()].every((v) => v !== null)

  function handleSubmit() {
    const lines = trackedItems.map((item) => ({
      menuItemId: item.id,
      countedQty: parsedCounts.get(item.id) ?? 0,
    }))
    submit.mutate(lines, {
      onSuccess: (res) => {
        if (res) setResult(res)
      },
    })
  }

  const submitErrorMessage = (): string => {
    const err = submit.error
    if (err instanceof ApiError && (err.problem?.detail || err.problem?.title)) {
      return err.problem.detail || err.problem.title || t('stocktake.errorGeneric')
    }
    return t('stocktake.errorGeneric')
  }

  return (
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
        ) : menuQuery.isLoading ? (
          <div className="grid place-items-center py-14">
            <Spinner />
          </div>
        ) : menuQuery.isError ? (
          <div className="px-5 py-10 text-center">
            <TriangleAlert className="mx-auto mb-2 size-5 text-loss" aria-hidden="true" />
            <p className="text-sm text-loss">{t('stocktake.loadError')}</p>
          </div>
        ) : trackedItems.length === 0 ? (
          <div className="px-5 py-10 text-center">
            <p className="text-sm text-ink-3">{t('stocktake.emptyHint')}</p>
            {/* Owner report: the hint named Menu management but offered no way there — a dead
                end. Close FIRST: the standalone hosts (MobileTabBarGate) stay mounted across
                route changes, and a lingering fixed overlay would cover the menu page. */}
            <Button
              variant="secondary"
              className="mt-4"
              onClick={() => {
                onClose()
                navigate('/menu')
              }}
            >
              {t('stocktake.emptyCta')}
            </Button>
          </div>
        ) : (
          <>
            <p className="shrink-0 px-5 pt-3 text-xs leading-relaxed text-ink-3">{t('stocktake.entryHint')}</p>
            <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 py-3">
              <ul className="space-y-2">
                {trackedItems.map((item) => (
                  <StocktakeItemRow
                    key={item.id}
                    item={item}
                    value={valueFor(item)}
                    onChange={(raw) => setOverrides((p) => ({ ...p, [item.id]: raw }))}
                    currency={currency}
                    locale={locale}
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
                onClick={handleSubmit}
              >
                {submit.isPending ? <Spinner /> : t('stocktake.submitAction')}
              </Button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

function StocktakeItemRow({
  item,
  value,
  onChange,
  currency,
  locale,
}: {
  item: MenuItem
  value: string
  onChange: (raw: string) => void
  currency: string
  locale: string
}) {
  const { t } = useTranslation()
  const systemQty = item.stockQuantity ?? 0
  const countedQty = parseQtyInput(value)
  const varianceQty = countedQty != null ? countedQty - systemQty : null
  const tone = varianceQty != null ? toneOfVariance(varianceQty) : null
  // Client-side preview only (the server recomputes authoritatively on submit) — a simple
  // qty × unit-cost, never rounded beyond integer minor units.
  const valuePreviewMinor =
    varianceQty != null && item.unitCostMinor != null ? varianceQty * item.unitCostMinor : null

  return (
    <li className="flex items-center gap-3 rounded-xl border border-line bg-paper px-3 py-2.5">
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-medium text-ink">{item.name}</div>
        <div className="tnum mt-0.5 text-xs text-ink-3">
          {t('stocktake.systemQty', { qty: formatQty(systemQty, locale) })}
        </div>
      </div>

      <input
        aria-label={t('stocktake.countedForItem', { name: item.name })}
        type="number"
        min="0"
        step="1"
        inputMode="numeric"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="0"
        className="h-10 w-20 shrink-0 rounded-lg border border-line bg-surface px-2 text-right font-mono text-sm tnum text-ink placeholder:text-ink-3/50 focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/10"
      />

      <div className="w-24 shrink-0 text-right">
        {varianceQty != null ? (
          <>
            <div className={cn('tnum font-mono text-sm font-semibold', tone ? TONE_TEXT[tone] : undefined)}>
              {formatSignedQty(varianceQty, locale)}
            </div>
            {valuePreviewMinor != null ? (
              <div className={cn('tnum font-mono text-[11px]', tone ? TONE_TEXT[tone] : undefined)}>
                {formatMoney(Math.abs(valuePreviewMinor), currency, locale)}
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
  result: StocktakeResponse
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
          <div className="tnum mt-1 font-mono text-2xl font-bold">
            {formatMoney(Math.abs(result.shrinkageMinor), currency, locale)}
          </div>
        </div>

        {variedLines.length > 0 ? (
          <div className="rounded-xl border border-line bg-paper px-3 py-2">
            <div className="mb-1.5 text-[11px] font-semibold uppercase tracking-[.05em] text-ink-3">
              {t('stocktake.varianceLinesTitle')}
            </div>
            <ul className="divide-y divide-line">
              {variedLines.map((line) => (
                <StocktakeVarianceLine key={line.menuItemId} line={line} currency={currency} locale={locale} />
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
  line: StocktakeLineResponse
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
          })}
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
