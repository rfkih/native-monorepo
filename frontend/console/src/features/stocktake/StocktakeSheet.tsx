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
 * Intl-backed helpers in ../inventory/lib/units (never a raw toString). Strings rule (rule 9):
 * i18n keys only.
 *
 * ADR 0068 part 3 — before a count is submitted, every line runs through stocktakeVarianceGuard
 * (./lib/stocktakeVarianceGuard); a line that looks implausible (a ×1000 g/kg slip, an extra
 * zero…) pauses submit behind a confirm dialog naming the suspicious line(s) — the SAME "are you
 * sure?" shape as RegisterSheet's close-cash mismatch confirm, one step later in the flow.
 *
 * PHONE FIRST. This is a surface an operator works standing up, one-handed, for as long as the
 * ingredient list takes, and every earlier decision here optimised for the tablet at the till:
 *  - The row was one flex line (name | field | variance). At 360px that left the NAME ~60px —
 *    seven characters — while two numeric columns took 210px. The row is stacked now: the name
 *    owns its own line, the field and its unit sit below it, and the variance only appears once
 *    there IS one (every row used to render a grey "0" and "Rp 0").
 *  - The field was a `type=number` seeded with a raw "1.5" while the line above it read "1,5 kg".
 *    An id-ID keypad offers "," — which a number field discards — so the row silently read as
 *    "not counted" and Submit greyed out with no way to see why. It is a text field with
 *    `inputMode=decimal` now, seeded in the operator's own locale, and both separators parse.
 *  - Submit still refuses an unusable line, but the footer now NAMES how many and scrolls to the
 *    first one, and states what is about to be posted (`summarizeStocktakeDraft`) BEFORE the
 *    button that posts it — the totals used to be visible only after the fact.
 *  - Leaving with counts typed asked nothing and threw them away. Both Back paths (hardware and
 *    the host header's arrow, via `closeRequestRef`) now confirm first.
 *
 * `chrome`: the till mounts this INSIDE its own overlay stack, so it draws its own scrim + card
 * there. On the phone screen host (StandaloneStocktake) the host already owns the chrome, and
 * drawing a second one put a scrim over a live ScreenHeader whose Back arrow was then unclickable
 * — ADR 0075 N2, one page, one chrome.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { ChevronDown, ClipboardCheck, Search, TriangleAlert, X } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { useScrollLock } from '@/components/mobile/useScrollLock'
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
import {
  formatShownQty,
  formatSignedShownQty,
  parseShownQtyInput,
  sanitizeShownQtyInput,
  shownQtyInputValue,
  shownUnit,
  type UnitBearing,
} from '@/features/inventory/lib/units'
import { formatQty, formatSignedQty } from './lib/qty'
import { summarizeStocktakeDraft } from './lib/stocktakeDraft'
import { checkStocktakeVariance, type StocktakeVarianceFlag, type StocktakeVarianceLine } from './lib/stocktakeVarianceGuard'

/** The three verdict tones shared by the live per-line preview and the post-submit summary. */
type Tone = 'loss' | 'gain' | 'balanced'

/** Above this many ingredients the list stops being scannable on a phone and gets a filter. */
const SEARCH_THRESHOLD = 12

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
  chrome = 'dialog',
  closeRequestRef,
}: {
  session: CompanySession
  currency: string
  locale: string
  onClose: () => void
  /** 'dialog' = draw the scrim + card (the till's overlay stack). 'screen' = the host screen
   *  already drew the chrome; fill it. */
  chrome?: 'dialog' | 'screen'
  /** A screen host wires its own header Back AND its back-dismiss through this, so both get the
   *  same unsaved-count confirm instead of discarding the work silently. The optional argument is
   *  called back when the close is DECLINED, so a host that spent a history entry can re-park it. */
  closeRequestRef?: { current: ((onStay?: () => void) => void) | null }
}) {
  const { t } = useTranslation()
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
  // Open = the operator asked to leave with counts typed but not submitted.
  const [discardAsked, setDiscardAsked] = useState(false)
  // Bumped whenever a Back press is INTERCEPTED (we stayed open) so useBackDismiss re-parks the
  // history entry that press consumed — otherwise the next Back would escape the sheet entirely.
  const [backArmKey, setBackArmKey] = useState(0)
  const [query, setQuery] = useState('')
  const listRef = useRef<HTMLDivElement>(null)

  // Seeded (and re-parsed) in the ingredient's SHOWN unit AND the operator's locale — a kg item
  // starts counted at "1,5" under an id-ID locale, matching the "Sistem: 1,5 kg" line above it.
  const seededValueFor = useCallback(
    (ing: Ingredient): string => shownQtyInputValue(ing.stockQty, ing, locale),
    [locale],
  )
  function valueFor(ing: Ingredient): string {
    return overrides[ing.id] ?? seededValueFor(ing)
  }

  const parsedCounts = new Map<string, number | null>(
    ingredients.map((ing) => [ing.id, parseShownQtyInput(valueFor(ing), ing)]),
  )
  // Lookup for the confirm dialog and the result summary — the guard's flags and the server's
  // response lines carry only ids + base-unit numbers, no display-unit knowledge.
  const ingredientById = new Map(ingredients.map((ing) => [ing.id, ing]))

  // What the operator is about to post, in the same base units the payload carries.
  const draft = summarizeStocktakeDraft(
    ingredients.map((ing) => ({
      systemQty: ing.stockQty,
      countedQty: parsedCounts.get(ing.id) ?? null,
      unitCostMinor: ing.unitCostMinor,
      currency: ing.costCurrency ?? currency,
    })),
    currency,
  )
  const canSubmit = ingredients.length > 0 && draft.invalid === 0

  // Unsaved work = a field the operator actually changed. An override typed back to the seeded
  // value is not a change, so re-typing the same number never triggers the confirm.
  const dirty = ingredients.some(
    (ing) => overrides[ing.id] != null && overrides[ing.id] !== seededValueFor(ing),
  )

  // The single close decision, shared by the X, the hardware Back and (via closeRequestRef) the
  // host screen's header arrow. `onStay` is called when we DECLINE to close: a Back press that we
  // answer has already spent a parked history entry, and only the hook instance that actually
  // handled the pop knows which entry to re-park — so the caller passes its own re-arm.
  const requestClose = useCallback(
    (onStay?: () => void) => {
      if (result != null || !dirty) {
        onClose()
        return
      }
      setDiscardAsked(true)
      onStay?.()
      // Setters are listed because the React Compiler infers them as dependencies and refuses to
      // optimise a component whose declared deps do not match what it inferred.
    },
    [result, dirty, onClose, setDiscardAsked],
  )

  const rearmBack = useCallback(() => setBackArmKey((k) => k + 1), [setBackArmKey])

  // Only in `dialog` chrome. A screen host registers its OWN back-dismiss, and because React runs
  // child effects before parent ones, the HOST is what the overlay registry sees as topmost — it
  // owns the pop there and routes it through `closeRequestRef` into this same decision. Registering
  // here too would park a second entry that nothing would ever pop.
  useBackDismiss(() => requestClose(rearmBack), chrome !== 'screen', backArmKey)
  useScrollLock()

  // Inner confirm layers, inline conditional JSX within this always-mounted-while-open component.
  const varianceConfirmOpen = pendingVarianceFlags != null && pendingVarianceFlags.length > 0
  useBackDismiss(() => setPendingVarianceFlags(null), varianceConfirmOpen)
  useScrollLock(varianceConfirmOpen)
  useBackDismiss(() => setDiscardAsked(false), discardAsked)
  useScrollLock(discardAsked)

  // Hand the host screen our guarded close so its header Back asks the same question.
  useEffect(() => {
    if (!closeRequestRef) return
    closeRequestRef.current = requestClose
    return () => {
      closeRequestRef.current = null
    }
  }, [closeRequestRef, requestClose])

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
        // The count is committed — nothing left to warn about on the way out.
        setOverrides({})
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

  /** Footer → the first line that blocks submit. Clears the filter first: the offending row may be
   *  the one the search is hiding, and a button that scrolls to nothing is worse than no button. */
  function jumpToFirstInvalid() {
    setQuery('')
    requestAnimationFrame(() => {
      const field = listRef.current?.querySelector<HTMLInputElement>('[data-invalid] input')
      field?.scrollIntoView({ block: 'center', behavior: 'smooth' })
      field?.focus()
    })
  }

  const submitErrorMessage = (): string => {
    const err = submit.error
    if (err instanceof ApiError && (err.problem?.detail || err.problem?.title)) {
      return err.problem.detail || err.problem.title || t('stocktake.errorGeneric')
    }
    return t('stocktake.errorGeneric')
  }

  const needle = query.trim().toLowerCase()
  const visibleIngredients = needle
    ? ingredients.filter((ing) => ing.name.toLowerCase().includes(needle))
    : ingredients

  const body = result ? (
    <StocktakeSummary
      result={result}
      currency={currency}
      locale={locale}
      unitsById={ingredientById}
      onDone={onClose}
    />
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
      {ingredients.length > SEARCH_THRESHOLD ? (
        <div className="relative shrink-0 px-5 pt-2">
          <Search
            className="pointer-events-none absolute left-7 top-1/2 size-4 -translate-y-1/2 text-ink-3"
            aria-hidden="true"
          />
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            aria-label={t('stocktake.searchLabel')}
            placeholder={t('stocktake.searchPlaceholder')}
            className="h-11 w-full rounded-lg border border-line bg-surface pl-9 pr-3 text-sm text-ink placeholder:text-ink-3/70 focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/10"
          />
        </div>
      ) : null}
      <div
        ref={listRef}
        className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 py-3"
      >
        {/* Inside the scroller, not pinned above it: as fixed chrome this paragraph cost the list
            ~54px of a 740px phone forever, and the list is what the operator is here for. It
            scrolls away after it has been read. */}
        <p className="mb-3 text-xs leading-relaxed text-ink-3">{t('stocktake.entryHint')}</p>
        {visibleIngredients.length === 0 ? (
          <p className="py-8 text-center text-sm text-ink-3">{t('stocktake.searchEmpty')}</p>
        ) : (
          <ul className="space-y-2">
            {visibleIngredients.map((ing) => (
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
        )}
      </div>
      <div className="shrink-0 space-y-2 border-t border-line px-5 py-4">
        {submit.isError ? (
          <p className="text-xs text-loss" role="alert">
            {submitErrorMessage()}
          </p>
        ) : null}
        <DraftFooterSummary
          draft={draft}
          currency={currency}
          locale={locale}
          onShowInvalid={jumpToFirstInvalid}
        />
        <Button
          className="w-full"
          data-testid="stocktake-submit"
          disabled={!canSubmit || submit.isPending}
          onClick={handleSubmitClick}
        >
          {submit.isPending ? <Spinner /> : t('stocktake.submitAction')}
        </Button>
      </div>
    </>
  )

  const confirmLayers = (
    <>
      {/* ADR 0068 part 3 — variance confirm: one or more counts tripped the plausibility guard, so
          make the operator reconfirm before the count posts (a fat-finger/×1000-slip safety net; the
          server still records + values whatever is submitted, this only gates whether it's sent). */}
      {varianceConfirmOpen ? (
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
              {pendingVarianceFlags?.map((flag) => {
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

      {/* Leaving with counts typed. A count is minutes of standing-up work that lives only in this
          component's state — closing used to throw it away without a word. */}
      {discardAsked ? (
        <div
          className="fixed inset-0 z-[60] grid place-items-center bg-black/50 p-4 backdrop-blur-sm"
          role="dialog"
          aria-modal="true"
          aria-label={t('stocktake.discardTitle')}
        >
          <div
            className="reveal w-full max-w-sm space-y-4 rounded-card border border-line bg-surface p-5 shadow-lg"
            data-testid="stocktake-discard-confirm"
          >
            <h3 className="font-display text-lg font-semibold text-ink">
              {t('stocktake.discardTitle')}
            </h3>
            <p className="text-sm text-ink-3">{t('stocktake.discardBody')}</p>
            <div className="flex gap-2">
              <Button
                variant="outline"
                className="flex-1"
                data-testid="stocktake-discard-keep"
                // No re-arm here: this dialog's own useBackDismiss parked an entry when it opened
                // and unwinds it on close, and the entry the intercepted Back spent was already
                // re-parked at intercept time.
                onClick={() => setDiscardAsked(false)}
              >
                {t('stocktake.discardKeep')}
              </Button>
              <Button className="flex-1" data-testid="stocktake-discard-leave" onClick={onClose}>
                {t('stocktake.discardLeave')}
              </Button>
            </div>
          </div>
        </div>
      ) : null}
    </>
  )

  // The phone screen host owns the header and the surface; fill it and draw nothing else.
  if (chrome === 'screen') {
    return (
      <>
        <div className="flex min-h-0 flex-1 flex-col bg-paper">{body}</div>
        {confirmLayers}
      </>
    )
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
              // Wrapped: a bare handler would hand the click event in as `onStay`.
              onClick={() => requestClose()}
              aria-label={t('common.close')}
              className="grid size-11 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
            >
              <X className="size-5" />
            </button>
          </div>
          {body}
        </div>
      </div>
      {confirmLayers}
    </>
  )
}

/**
 * The footer line above the submit button: what this count is about to do. Either the lines that
 * block it (with a way to reach the first one) or the change it will post — the figure that used
 * to appear only on the result screen, after the posting had already happened.
 */
function DraftFooterSummary({
  draft,
  currency,
  locale,
  onShowInvalid,
}: {
  draft: ReturnType<typeof summarizeStocktakeDraft>
  currency: string
  locale: string
  onShowInvalid: () => void
}) {
  const { t } = useTranslation()

  if (draft.invalid > 0) {
    return (
      <button
        type="button"
        onClick={onShowInvalid}
        data-testid="stocktake-show-invalid"
        className="flex min-h-11 w-full items-center justify-between gap-3 rounded-lg bg-tint-loss px-3 py-2 text-left text-xs text-loss focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-loss"
      >
        <span>
          {t('stocktake.invalidLines', {
            formatted: new Intl.NumberFormat(locale).format(draft.invalid),
          })}
        </span>
        <span className="shrink-0 font-semibold underline">{t('stocktake.showInvalid')}</span>
      </button>
    )
  }

  const tone: Tone =
    draft.netValueMinor === 0 ? 'balanced' : draft.netValueMinor < 0 ? 'loss' : 'gain'

  return (
    <div
      className="flex min-h-6 items-baseline justify-between gap-3 text-xs"
      data-testid="stocktake-draft-summary"
    >
      <span className="text-ink-3">
        {t('stocktake.changedLines', {
          formatted: new Intl.NumberFormat(locale).format(draft.changed),
        })}
      </span>
      {draft.netValueMinor !== 0 ? (
        <span className={cn('tnum shrink-0 font-mono font-semibold', TONE_TEXT[tone])}>
          {/* Signed, not colour-only: the direction has to survive a colourblind reading. */}
          {formatMoney(draft.netValueMinor, currency, locale)}
          {draft.partialValue ? ` ${t('stocktake.partialValueMark')}` : ''}
        </span>
      ) : null}
    </div>
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
    <div className="shrink-0 border-b border-line px-5">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="flex min-h-11 w-full items-center gap-2 rounded-lg py-2 text-left text-[13px] font-semibold text-ink-2 hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
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
  const unit = shownUnit(ingredient)

  return (
    <li
      // The footer's "show me" jump reads this; it is also what tints the row.
      data-invalid={countedQty == null ? true : undefined}
      className={cn(
        'rounded-xl border bg-paper px-3 py-2.5',
        countedQty == null ? 'border-loss/60' : 'border-line',
      )}
    >
      {/* The name gets a line of its own. Shared with the field it was ~60px on a 360px phone —
          seven characters of an ingredient name, on the surface where picking the wrong row
          MANUFACTURES the variance the leak report later reads as theft. */}
      <div className="truncate text-sm font-medium text-ink">{ingredient.name}</div>
      <div className="tnum mt-0.5 text-xs text-ink-3">
        {t('stocktake.systemQty', {
          qty: formatShownQty(systemQty, ingredient, locale),
          unit,
        })}
        {usedToday > 0
          ? ` · ${t('stocktake.usedToday', {
              qty: formatShownQty(usedToday, ingredient, locale),
              unit,
            })}`
          : ''}
      </div>

      <div className="mt-2 flex items-center gap-2">
        {/* The unit sits beside the field, not only above it. This is the counting surface, and a
            count entered in the wrong unit does not merely display wrong — it MANUFACTURES the
            variance that the leak report then reads as theft. Typing 1500 against an item shown as
            "1,5 kg" would record 1500 kg counted and fabricate a 1498,5 kg overage. */}
        <input
          aria-label={t('stocktake.countedForItemWithUnit', { name: ingredient.name, unit })}
          // Text, not number: a `type=number` silently discards the "," an id-ID keypad offers,
          // which read as "not counted" with a value still on screen. `inputMode` still brings up
          // the numeric keypad; sanitizeShownQtyInput does the filtering the number type used to.
          type="text"
          inputMode="decimal"
          autoComplete="off"
          value={value}
          onChange={(e) => onChange(sanitizeShownQtyInput(e.target.value))}
          // Every field arrives pre-filled, so the first tap should mean "replace this", not
          // "put the caret somewhere and backspace three times".
          onFocus={(e) => e.currentTarget.select()}
          placeholder="0"
          className={cn(
            'h-11 w-24 shrink-0 rounded-lg border bg-surface px-2 text-right font-mono text-base tnum text-ink placeholder:text-ink-3/50 focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/10',
            countedQty == null ? 'border-loss' : 'border-line',
          )}
        />
        {/* aria-hidden: the label above already carries the unit; announcing it twice is worse. */}
        <span
          aria-hidden="true"
          title={unit}
          className="min-w-8 max-w-20 shrink-0 truncate font-mono text-xs font-semibold text-ink-3"
        >
          {unit}
        </span>

        <div className="ml-auto min-w-0 text-right">
          {countedQty == null ? (
            <span className="text-xs font-medium text-loss">{t('stocktake.notCounted')}</span>
          ) : varianceQty !== 0 ? (
            <>
              <div
                className={cn(
                  'tnum font-mono text-sm font-semibold',
                  tone ? TONE_TEXT[tone] : undefined,
                )}
              >
                {formatSignedShownQty(varianceQty ?? 0, ingredient, locale)} {unit}
              </div>
              {valuePreviewMinor != null ? (
                <div
                  className={cn('tnum font-mono text-[11px]', tone ? TONE_TEXT[tone] : undefined)}
                >
                  {formatMoney(Math.abs(valuePreviewMinor), previewCurrency, locale)}
                </div>
              ) : null}
            </>
          ) : null}
        </div>
      </div>
    </li>
  )
}

function StocktakeSummary({
  result,
  currency,
  locale,
  unitsById,
  onDone,
}: {
  result: IngredientStocktakeResponse
  currency: string
  locale: string
  /** The counted ingredients, for their DISPLAY units — the response carries only base units. */
  unitsById: Map<string, UnitBearing>
  onDone: () => void
}) {
  const { t } = useTranslation()
  const tone: Tone = result.shrinkageMinor === 0 ? 'balanced' : result.shrinkageMinor > 0 ? 'loss' : 'gain'
  const variedLines = result.lines.filter((l) => l.varianceQty !== 0)

  return (
    <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain" data-testid="stocktake-summary">
      {/* min-h-full + flex: on the phone screen host a short summary would otherwise leave the
          Done button stranded halfway up the screen with empty space under it. */}
      <div className="flex min-h-full flex-col">
      <div className="flex-1 space-y-3 px-5 py-5">
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
                  ingredient={unitsById.get(line.ingredientId)}
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
    </div>
  )
}

function StocktakeVarianceLine({
  line,
  ingredient,
  currency,
  locale,
}: {
  line: IngredientStocktakeLineResponse
  /** Absent only if the catalog moved under us; then the server's own base unit is the fallback. */
  ingredient: UnitBearing | undefined
  currency: string
  locale: string
}) {
  const { t } = useTranslation()
  const tone = toneOfVariance(line.varianceQty)
  // The entry screen counted in kg; the response speaks grams. Showing the base figure here made
  // "1,5 kg counted" come back as "1.500 g" — the same number twice, in two units, one screen apart.
  const unit = ingredient ? shownUnit(ingredient) : line.unit
  const system = ingredient
    ? formatShownQty(line.systemQty, ingredient, locale)
    : formatQty(line.systemQty, locale)
  const counted = ingredient
    ? formatShownQty(line.countedQty, ingredient, locale)
    : formatQty(line.countedQty, locale)
  const variance = ingredient
    ? formatSignedShownQty(line.varianceQty, ingredient, locale)
    : formatSignedQty(line.varianceQty, locale)

  return (
    <li className="flex items-center justify-between gap-3 py-1.5 text-sm">
      <div className="min-w-0 flex-1">
        <div className="truncate text-ink">{line.name}</div>
        <div className="tnum text-xs text-ink-3">
          {t('stocktake.lineCounts', { system, counted })} {unit}
        </div>
      </div>
      <div className="shrink-0 text-right">
        <div className={cn('tnum font-mono text-sm font-semibold', TONE_TEXT[tone])}>
          {variance} {unit}
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
