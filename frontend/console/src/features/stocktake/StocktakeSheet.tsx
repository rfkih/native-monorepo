/**
 * StocktakeSheet — the stock opname (ADR 0038 phase 3 flow, re-aimed at INGREDIENTS by ADR 0046,
 * re-drawn after the "Native Opname Stok" design, 2026-09-10). Lists the outlet's active inventory
 * items pre-filled with the current system quantity; the operator checks each one and corrects what
 * differs from the physical count. Submitting POSTs every line to the server, which adjusts each
 * item's stock to the count and books valued net shrinkage for items that carry a cost — the
 * response is shown as a summary (red = net loss, green = net gain, neutral = balanced). A count
 * where NO line carries a cost posts nothing (`currency` null on the response) and the summary says
 * so instead of showing money.
 *
 * Reached from the till menu (features/pos-shell/layout/TillMenuSheet), chained from the
 * register-close verdict, and hosted full-screen on the phone by StandaloneStocktake.
 *
 * THE DESIGN'S THESIS — "filled in is not checked". Every field arrives pre-filled, so a row nobody
 * looked at and a row someone counted and found equal used to send the same number to the ledger
 * AND look the same on screen; on a sixteen-item count that turned the opname into a rubber stamp.
 * "Checked" is a status of its own now (./lib/stocktakeMarks): a row starts pending and leaves it
 * with one tap on its check mark (matches the system) or with a typed count that differs. The
 * header strip carries real progress, and the footer names how many rows are still unchecked
 * before the button that sends them — without blocking, exactly like the variance guard that
 * deliberately fails open. Purely client-side: the payload is unchanged, one `countedQty` per
 * ingredient, and an unchecked row still goes out at its system quantity.
 *
 * THE COUNT SHEET. Counts used to be typed into an inline field with the system keyboard, which on
 * a 412px phone covers ~40% of the screen INCLUDING the row being edited. The row still shows its
 * figure as a writing line, but tapping it opens a sheet of its own: the item's name, its system
 * reference, a live variance preview, and a keypad that knows whether the item admits fractions
 * (the reducer is `inventory/lib/countKeypad`, beside the unit rules it enforces; the pad itself
 * is `inventory/QtyKeypad`, shared with the inventory receive/set sheet). A physical keyboard
 * still types straight into the figure (`inputMode=none` keeps the on-screen one down), and the
 * first key REPLACES the seeded figure, as the old select-on-focus field did.
 *
 * Money rule (rule 8): unit cost / variance value render via formatMoney; quantities via the
 * Intl-backed helpers in ../inventory/lib/units (never a raw toString). Strings rule (rule 9):
 * i18n keys only.
 *
 * ADR 0068 part 3 — before a count is submitted, every line runs through stocktakeVarianceGuard
 * (./lib/stocktakeVarianceGuard); an implausible line pauses submit behind a confirm that WRITES
 * OUT its reasons ("774× the system quantity", "worth Rp 160 juta, above the Rp 5 juta threshold")
 * instead of the word "implausible". Still a safety net, not a block ("Send anyway").
 *
 * `chrome`: the till mounts this INSIDE its own overlay stack, so it draws its own scrim + card
 * there. On the phone screen host (StandaloneStocktake) the host already owns the chrome — drawing
 * a second one put a scrim over a live ScreenHeader whose Back arrow was then unclickable
 * (ADR 0075 N2, one page, one chrome). Both Back paths (hardware and the host's header arrow, via
 * `closeRequestRef`) go through `requestClose`, which confirms before discarding worked rows.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import {
  Check,
  ChevronDown,
  ClipboardCheck,
  History,
  Search,
  TriangleAlert,
  X,
} from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { useScrollLock } from '@/components/mobile/useScrollLock'
import { Button } from '@/components/ui/Button'
import { SuccessMark } from '@/components/ui/SuccessMark'
import { DialogOverlay } from '@/components/ui/Dialog'
import { Skeleton } from '@/components/ui/Skeleton'
import { Spinner } from '@/components/ui/Spinner'
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
import { StocktakeHistorySheet } from '@/features/inventory/StocktakeHistorySheet'
import {
  useSubmitIngredientStocktake,
  type IngredientStocktakeLineResponse,
  type IngredientStocktakeResponse,
} from '@/features/inventory/ingredientStocktakeApi'
import {
  allowsFraction,
  formatShownQty,
  formatSignedShownQty,
  parseShownQtyInput,
  sanitizeShownQtyInput,
  shownQtyInputValue,
  shownUnit,
  shownUnitCostMinor,
} from '@/features/inventory/lib/units'
import { formatQty, formatSignedQty } from './lib/qty'
import {
  applyCountKey,
  countDraftWithinCap,
  decimalSeparatorOf,
  type CountKey,
} from '@/features/inventory/lib/countKeypad'
import { QtyKeypad } from '@/features/inventory/QtyKeypad'
import { summarizeStocktakeDraft, type StocktakeDraftSummary } from './lib/stocktakeDraft'
import {
  EMPTY_MARKS,
  saveStocktakeCount,
  stocktakeRowState,
  summarizeStocktakeProgress,
  toggleStocktakeMark,
  workedRowCount,
  type StocktakeMarks,
  type StocktakeProgress,
  type StocktakeRowState,
} from './lib/stocktakeMarks'
import {
  checkStocktakeVariance,
  valueThresholdMinor,
  type StocktakeVarianceFlag,
  type StocktakeVarianceLine,
} from './lib/stocktakeVarianceGuard'
import { safeBottom } from '@/lib/safeArea'

/** The three verdict tones shared by the live per-line preview and the post-submit summary. */
type Tone = 'loss' | 'gain' | 'balanced'

/** Above this many items the list stops being scannable on a phone and gets a filter. */
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
  balanced: 'bg-hover text-ink-2',
}

/** A 44px icon target — the header buttons and the count sheet's close. */
const ICON_BUTTON =
  'grid size-11 shrink-0 place-items-center rounded-xl text-ink-2 transition-colors hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald'

/** Fixed bottom surfaces bypass the body's safe-area padding (index.css) — each pads itself. */
const SAFE_BOTTOM = safeBottom // lib/safeArea — the one definition of the nav-bar inset rule

export function StocktakeSheet({
  session,
  currency,
  locale,
  onClose,
  chrome = 'dialog',
  closeRequestRef,
  onDiscardAskedChange,
}: {
  session: CompanySession
  currency: string
  locale: string
  onClose: () => void
  /** 'dialog' = draw the scrim + card (the till's overlay stack). 'screen' = the host screen
   *  already drew the chrome; fill it. */
  chrome?: 'dialog' | 'screen'
  /** A screen host wires its own header Back AND its back-dismiss through this, so both get the
   *  same unsaved-count confirm instead of discarding the work silently. */
  closeRequestRef?: { current: (() => void) | null }
  /** Fires when the discard confirm opens/closes. A screen host that owns the hardware Back MUST
   *  disable its own back-dismiss while the confirm is up — see the note above `useBackDismiss`
   *  below for why the confirm has to be the only thing parked. */
  onDiscardAskedChange?: (open: boolean) => void
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

  // Only the operator's WORK is kept in state — typed counts that differ from the system figure,
  // and the rows confirmed equal to it — never a full seeded map. A row with no override reads as
  // its system quantity (`valueFor`), so nothing has to sync query data into state.
  const [marks, setMarks] = useState<StocktakeMarks>(EMPTY_MARKS)
  // The count sheet: which item, and the figure as typed so far. Null = no sheet open.
  const [countDraft, setCountDraft] = useState<{
    ingredientId: string
    raw: string
  } | null>(null)
  // Held after a successful submit so the summary stays visible.
  const [result, setResult] = useState<IngredientStocktakeResponse | null>(null)
  // ADR 0068 part 3 — set when Submit trips the variance guard: holds the flagged line(s) so the
  // confirm can name them. Null = no confirm showing.
  const [pendingVarianceFlags, setPendingVarianceFlags] = useState<StocktakeVarianceFlag[] | null>(
    null,
  )
  // Open = the operator asked to leave with rows worked on but not submitted.
  const [discardAsked, setDiscardAsked] = useState(false)
  const [query, setQuery] = useState('')
  // Dialog chrome only — the screen host owns its own header button and mounts the sheet itself.
  const [historyOpen, setHistoryOpen] = useState(false)
  const listRef = useRef<HTMLDivElement>(null)

  // Seeded (and re-parsed) in the item's SHOWN unit AND the operator's locale — a kg item starts
  // counted at "1,5" under an id-ID locale, matching the "sistem 1,5 kg" line beside it.
  const seededValueFor = useCallback(
    (ing: Ingredient): string => shownQtyInputValue(ing.stockQty, ing, locale),
    [locale],
  )
  function valueFor(ing: Ingredient): string {
    return marks.overrides[ing.id] ?? seededValueFor(ing)
  }

  const parsedCounts = new Map<string, number | null>(
    ingredients.map((ing) => [ing.id, parseShownQtyInput(valueFor(ing), ing)]),
  )
  const rowStates = new Map<string, StocktakeRowState>(
    ingredients.map((ing) => [
      ing.id,
      stocktakeRowState(
        parsedCounts.get(ing.id) ?? null,
        ing.stockQty,
        marks.verified[ing.id] === true,
      ),
    ]),
  )
  const progress = summarizeStocktakeProgress([...rowStates.values()])
  // Lookup for the confirm, the count sheet and the result summary — the guard's flags and the
  // server's response lines carry only ids + base-unit numbers, no display-unit knowledge.
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

  // Unsaved work = a row the operator touched: a differing count OR a check mark. Both are minutes
  // of standing-up work that live only in this component's state.
  const workedRows = workedRowCount(marks)
  const dirty = workedRows > 0

  // The single close decision, shared by the X, the hardware Back and (via closeRequestRef) the
  // host screen's header arrow. Declining opens the discard confirm — and tells the host, which
  // has to stand down from the hardware Back while the confirm is up (see below).
  const requestClose = useCallback(() => {
    if (result != null || !dirty) {
      onClose()
      return
    }
    setDiscardAsked(true)
    onDiscardAskedChange?.(true)
    // Setters are listed because the React Compiler infers them as dependencies and refuses to
    // optimise a component whose declared deps do not match what it inferred.
  }, [result, dirty, onClose, onDiscardAskedChange, setDiscardAsked])

  // Only in `dialog` chrome. A screen host registers its OWN back-dismiss, and because React runs
  // child effects before parent ones, the HOST is what the overlay registry sees as topmost — it
  // owns the pop there and routes it through `closeRequestRef` into this same decision. Registering
  // here too would park a second entry that nothing would ever pop.
  //
  // DISABLED WHILE THE DISCARD CONFIRM IS UP. The confirm is a child DialogOverlay that parks its
  // own entry, and child effects run before parent ones — so if this hook re-parked in the same
  // commit (the old `rearmKey` re-arm did exactly that), the SHEET would land on top of the
  // registry and the confirm could never be dismissed by Back. Gating on `discardAsked` instead
  // means: a Back that opened the confirm has spent this hook's entry, and nothing re-parks until
  // the confirm closes — at which point the effect re-runs and either adopts the confirm's
  // still-parked entry (closed by tap) or parks a fresh one (closed by Back). Same contract for a
  // screen host, via `onDiscardAskedChange`.
  useBackDismiss(requestClose, chrome !== 'screen' && !discardAsked)
  useScrollLock()

  // Hand the host screen our guarded close so its header Back asks the same question.
  useEffect(() => {
    if (!closeRequestRef) return
    closeRequestRef.current = requestClose
    return () => {
      closeRequestRef.current = null
    }
  }, [closeRequestRef, requestClose])

  // If this sheet is unmounted UNDER an open confirm — the outlet gate swapping it for a panel on
  // a failed refetch — the host must get its Back back, or its gate stays shut with nothing parked
  // and the next Back leaves the screen silently.
  useEffect(() => {
    if (!onDiscardAskedChange) return
    return () => onDiscardAskedChange(false)
  }, [onDiscardAskedChange])

  // The inner overlays are DialogOverlays mounted while open; their onClose has to be stable
  // because the overlay's exit timer lists it as an effect dependency.
  const closeCountSheet = useCallback(() => setCountDraft(null), [setCountDraft])
  const closeVarianceGuard = useCallback(
    () => setPendingVarianceFlags(null),
    [setPendingVarianceFlags],
  )
  const keepCounting = useCallback(() => {
    setDiscardAsked(false)
    onDiscardAskedChange?.(false)
  }, [onDiscardAskedChange, setDiscardAsked])
  const closeHistory = useCallback(() => setHistoryOpen(false), [setHistoryOpen])

  // ADR 0068 part 3 guard input — BASE-unit quantities (the same numbers the submit payload
  // carries), the item's own unit cost/currency (defaulted to the company base currency when the
  // item carries none, the same fallback the row preview uses).
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

  // The guard (when it is showing) stays up while the request is in flight, so its "Send anyway"
  // can show the spinner; it comes down on settle either way — on success the summary replaces
  // it, on failure the footer's error line has to be visible, not hidden behind a dialog.
  function doSubmit() {
    submit.mutate(buildSubmitLines(), {
      onSuccess: (res) => {
        if (res) setResult(res)
        // The count is committed — nothing left to warn about on the way out.
        setMarks(EMPTY_MARKS)
      },
      onSettled: () => setPendingVarianceFlags(null),
    })
  }

  // Submit tapped: if any line looks implausible (a ×1000 g/kg slip, an extra zero…), hold the
  // submit behind a confirm naming the suspicious line(s) and WHY, instead of posting straight
  // away — a safety net, not a hard block (the owner can always "Send anyway", ADR 0068 §3).
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
      const row = listRef.current?.querySelector<HTMLElement>('[data-invalid]')
      row?.scrollIntoView({ block: 'center', behavior: 'smooth' })
      row?.querySelector<HTMLButtonElement>('[data-count-field]')?.focus()
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

  const countIngredient = countDraft ? ingredientById.get(countDraft.ingredientId) : undefined

  const body = result ? (
    <StocktakeSummary
      result={result}
      currency={currency}
      locale={locale}
      ingredientById={ingredientById}
      onDone={onClose}
    />
  ) : ingredientsQuery.isLoading ? (
    <CountSkeleton />
  ) : ingredientsQuery.isError ? (
    <div className="grid min-h-0 flex-1 place-items-center px-8 py-10">
      <div className="text-center">
        <TriangleAlert className="mx-auto mb-2.5 size-[26px] text-loss" aria-hidden="true" />
        <p className="text-sm font-semibold leading-relaxed text-loss">
          {t('stocktake.loadError')}
        </p>
        <p className="mt-1.5 text-xs leading-relaxed text-ink-3">{t('stocktake.loadErrorBody')}</p>
        <Button size="lg" className="mt-5" onClick={() => void ingredientsQuery.refetch()}>
          {t('stocktake.retry')}
        </Button>
      </div>
    </div>
  ) : ingredients.length === 0 ? (
    <div className="grid min-h-0 flex-1 place-items-center px-8 py-10">
      <div className="text-center">
        <p className="text-sm font-semibold leading-relaxed text-ink">
          {t('stocktake.emptyTitle')}
        </p>
        <p className="mt-1.5 text-xs leading-relaxed text-ink-3">{t('stocktake.emptyHint')}</p>
        {/* The hint names the inventory screen — this button actually goes there. Close FIRST, and
            explicitly: the host is MorePage now (ADR 0078 moved it off MobileTabBarGate), which
            unmounts on navigation, so an unclosed overlay would be torn down mid-flow rather than
            left covering the destination. Either way the close has to precede the navigate — do not
            "simplify" this to a bare navigate on the assumption the unmount handles it. */}
        <Button
          size="lg"
          className="mt-5"
          onClick={() => {
            onClose()
            navigate('/inventory')
          }}
        >
          {t('stocktake.emptyCta')}
        </Button>
      </div>
    </div>
  ) : (
    <>
      <ProgressStrip progress={progress} locale={locale} />
      {/* The only fixed chrome besides the footer. Everything explanatory scrolls. */}
      {ingredients.length > SEARCH_THRESHOLD ? (
        <div className="shrink-0 px-4 pb-3">
          <div className="flex h-11 items-center gap-2 rounded-xl bg-hover px-3 transition-colors focus-within:bg-surface focus-within:ring-2 focus-within:ring-line-strong">
            <Search className="size-[17px] shrink-0 text-ink-3" aria-hidden="true" />
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              aria-label={t('stocktake.searchLabel')}
              placeholder={t('stocktake.searchPlaceholder')}
              className="min-w-0 flex-1 bg-transparent text-sm text-ink placeholder:text-ink-3 focus:outline-none"
            />
            {query ? (
              <button
                type="button"
                onClick={() => setQuery('')}
                aria-label={t('stocktake.searchClear')}
                className="-mr-3 grid size-11 shrink-0 place-items-center rounded-xl text-ink-3 focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald"
              >
                <X className="size-4" aria-hidden="true" />
              </button>
            ) : null}
          </div>
        </div>
      ) : null}
      <div ref={listRef} className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
        <div className="px-4 pb-1 pt-3.5">
          <p className="text-xs leading-relaxed text-ink-3">{t('stocktake.entryHint')}</p>
          <SoldTodayPanel
            items={soldTodayQuery.data ?? []}
            loading={soldTodayQuery.isLoading}
            currency={currency}
            locale={locale}
          />
        </div>
        {visibleIngredients.length === 0 ? (
          <p className="px-8 py-11 text-center text-xs leading-relaxed text-ink-3">
            {t('stocktake.searchEmpty')}
          </p>
        ) : null}
        {/* One sheet, hairline-ruled — not fifteen cards. A card per row put a bordered box inside
            a bordered box thirty times over and gave the number, which is the whole point of the
            screen, the least visual weight on it. */}
        <ul className="border-t border-line">
          {visibleIngredients.map((ing) => {
            const state = rowStates.get(ing.id) ?? 'pending'
            return (
              <StocktakeIngredientRow
                key={ing.id}
                ingredient={ing}
                value={valueFor(ing)}
                state={state}
                currency={currency}
                locale={locale}
                usedToday={usedById.get(ing.id) ?? 0}
                onMark={() => setMarks((m) => toggleStocktakeMark(m, ing.id, state))}
                onOpen={() => setCountDraft({ ingredientId: ing.id, raw: valueFor(ing) })}
              />
            )
          })}
        </ul>
        <p className="px-4 pb-6 pt-4 text-xs leading-relaxed text-ink-3">
          {t('stocktake.listFootnote')}
        </p>
      </div>
      <div className="shrink-0 border-t border-line bg-surface px-4 pt-3" style={SAFE_BOTTOM(20)}>
        {submit.isError ? (
          <p className="mb-2 text-xs font-semibold leading-snug text-loss" role="alert">
            {submitErrorMessage()}
          </p>
        ) : null}
        <DraftFooterSummary
          draft={draft}
          pending={progress.pending}
          currency={currency}
          locale={locale}
          onShowInvalid={jumpToFirstInvalid}
        />
        <Button
          size="2xl"
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

  const overlays = (
    <>
      {/* Keyed by item so the sheet's "first key replaces" state resets per item. */}
      {countDraft && countIngredient ? (
        <CountSheet
          key={countIngredient.id}
          ingredient={countIngredient}
          raw={countDraft.raw}
          usedToday={usedById.get(countIngredient.id) ?? 0}
          currency={currency}
          locale={locale}
          onChange={(raw) => setCountDraft((d) => (d ? { ...d, raw } : d))}
          onReset={() =>
            setCountDraft({
              ingredientId: countIngredient.id,
              raw: seededValueFor(countIngredient),
            })
          }
          onSave={() =>
            setMarks((m) =>
              saveStocktakeCount(
                m,
                countIngredient.id,
                countDraft.raw,
                parseShownQtyInput(countDraft.raw, countIngredient),
                countIngredient.stockQty,
              ),
            )
          }
          onClose={closeCountSheet}
        />
      ) : null}

      {/* ADR 0068 part 3 — variance confirm: one or more counts tripped the plausibility guard, so
          make the operator reconfirm before the count posts (the server still records + values
          whatever is submitted, this only gates whether it's sent). */}
      {pendingVarianceFlags && pendingVarianceFlags.length > 0 ? (
        <VarianceGuardDialog
          flags={pendingVarianceFlags}
          ingredientById={ingredientById}
          currency={currency}
          locale={locale}
          busy={submit.isPending}
          onRecount={closeVarianceGuard}
          onProceed={doSubmit}
        />
      ) : null}

      {/* Leaving with rows worked on. A count is minutes of standing-up work that lives only in
          this component's state — closing used to throw it away without a word. */}
      {discardAsked ? (
        <DiscardDialog
          workedRows={workedRows}
          locale={locale}
          onKeep={keepCounting}
          onLeave={onClose}
        />
      ) : null}

      {historyOpen ? (
        <StocktakeHistorySheet
          session={session}
          currency={currency}
          locale={locale}
          onClose={closeHistory}
        />
      ) : null}
    </>
  )

  // The phone screen host owns the header and the surface; fill it and draw nothing else.
  if (chrome === 'screen') {
    return (
      <>
        <div className="flex min-h-0 flex-1 flex-col bg-surface">{body}</div>
        {overlays}
      </>
    )
  }

  return (
    <>
      <div
        className="fixed inset-0 z-50 flex items-end justify-center bg-scrim p-0 backdrop-blur-sm sm:items-center sm:p-4"
        role="dialog"
        aria-modal="true"
        aria-label={t('stocktake.title')}
      >
        <div className="reveal flex max-h-full w-full max-w-lg flex-col overflow-hidden rounded-t-2xl border border-line bg-surface shadow-lg sm:rounded-2xl">
          <div className="flex shrink-0 items-center gap-1 border-b border-line py-2 pl-5 pr-2">
            <h2 className="flex min-w-0 flex-1 items-center gap-2 font-display text-lg font-semibold text-ink">
              <ClipboardCheck className="size-5 shrink-0 text-emerald-2" aria-hidden="true" />
              <span className="truncate">{t('stocktake.title')}</span>
            </h2>
            {result == null ? (
              <button
                type="button"
                onClick={() => setHistoryOpen(true)}
                aria-label={t('stocktake.historyTitle')}
                className={ICON_BUTTON}
              >
                <History className="size-[19px]" strokeWidth={1.8} aria-hidden="true" />
              </button>
            ) : null}
            <button
              type="button"
              onClick={requestClose}
              aria-label={t('common.close')}
              className={ICON_BUTTON}
            >
              <X className="size-5" aria-hidden="true" />
            </button>
          </div>
          {body}
        </div>
      </div>
      {overlays}
    </>
  )
}

/** The header strip: "12 of 16 checked", how many are left, and a 2px bar — real progress, not
 *  "fields with a value in them" (every field has one). */
function ProgressStrip({ progress, locale }: { progress: StocktakeProgress; locale: string }) {
  const { t } = useTranslation()
  const nf = new Intl.NumberFormat(locale)
  const pct = progress.total > 0 ? Math.round((progress.resolved / progress.total) * 100) : 0
  return (
    <div className="shrink-0 px-4 pb-3 pt-1" data-testid="stocktake-progress">
      <div className="flex items-baseline justify-between gap-2.5">
        <span className="text-sm font-bold text-ink">
          {t('stocktake.progress', {
            resolved: nf.format(progress.resolved),
            total: nf.format(progress.total),
          })}
        </span>
        {progress.pending > 0 ? (
          <span className="text-xs font-medium text-amber">
            {t('stocktake.progressRemaining', {
              formatted: nf.format(progress.pending),
            })}
          </span>
        ) : progress.invalid === 0 ? (
          <span className="text-xs font-medium text-profit-ink">
            {t('stocktake.progressComplete')}
          </span>
        ) : null}
      </div>
      <div
        className="mt-2 h-0.5 overflow-hidden rounded-full bg-ink-100"
        role="progressbar"
        aria-label={t('stocktake.progressLabel')}
        aria-valuemin={0}
        aria-valuemax={progress.total}
        aria-valuenow={progress.resolved}
      >
        <div
          className="h-full rounded-full bg-ink transition-[width] duration-500 ease-out motion-reduce:transition-none"
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  )
}

/** The list ghost — a check circle, two text lines and a figure block per row, footer button. */
function CountSkeleton() {
  const widths = ['w-3/4', 'w-7/12', 'w-4/5', 'w-5/12', 'w-2/3', 'w-1/2']
  return (
    <div aria-busy="true" className="flex min-h-0 flex-1 flex-col">
      <div className="min-h-0 flex-1 space-y-4 overflow-hidden px-4 pt-4">
        <Skeleton className="h-2.5 w-4/5" />
        {widths.map((w, i) => (
          <div key={i} className="flex items-start gap-3 pt-1">
            <Skeleton className="size-[26px] shrink-0 rounded-full" />
            <div className="min-w-0 flex-1 space-y-2">
              <Skeleton className={cn('h-3', w)} />
              <Skeleton className="h-2 w-1/2" />
            </div>
            <Skeleton className="h-[22px] w-[76px] shrink-0 rounded-md" />
          </div>
        ))}
      </div>
      <div className="shrink-0 border-t border-line px-4 pb-6 pt-3.5">
        <Skeleton className="h-14 rounded-2xl" />
      </div>
    </div>
  )
}

/**
 * The lines above the submit button: what this count is about to do. Either the lines that block
 * it (with a way to reach the first one) or the change it will post — the figure that used to
 * appear only on the result screen, after the posting had already happened — plus how many rows
 * are still unchecked and will go out at their system quantity.
 */
function DraftFooterSummary({
  draft,
  pending,
  currency,
  locale,
  onShowInvalid,
}: {
  draft: StocktakeDraftSummary
  pending: number
  currency: string
  locale: string
  onShowInvalid: () => void
}) {
  const { t } = useTranslation()
  const nf = new Intl.NumberFormat(locale)

  if (draft.invalid > 0) {
    return (
      <button
        type="button"
        onClick={onShowInvalid}
        data-testid="stocktake-show-invalid"
        className="mb-2.5 flex min-h-11 w-full items-center justify-between gap-3 rounded-xl bg-tint-loss px-3 py-2 text-left text-xs font-semibold leading-snug text-loss focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-loss"
      >
        <span>
          {t('stocktake.invalidLines', {
            count: draft.invalid,
            formatted: nf.format(draft.invalid),
          })}
        </span>
        <span className="shrink-0 font-bold underline">{t('stocktake.showInvalid')}</span>
      </button>
    )
  }

  const tone: Tone =
    draft.netValueMinor === 0 ? 'balanced' : draft.netValueMinor < 0 ? 'loss' : 'gain'

  return (
    <div className="mb-2.5 space-y-1.5" data-testid="stocktake-draft-summary">
      <div className="flex items-baseline justify-between gap-2.5">
        <span className="text-xs font-medium text-ink-3">
          {draft.changed === 0
            ? t('stocktake.changedNone')
            : t('stocktake.changedLines', {
                count: draft.changed,
                formatted: nf.format(draft.changed),
              })}
        </span>
        {draft.netValueMinor !== 0 ? (
          <span className={cn('tnum shrink-0 font-mono text-base font-bold', TONE_TEXT[tone])}>
            {/* Signed, not colour-only: the direction has to survive a colourblind reading. */}
            {formatMoney(draft.netValueMinor, currency, locale)}
          </span>
        ) : null}
      </div>
      {draft.partialValue ? (
        <p className="text-xs font-medium leading-snug text-amber">
          {t('stocktake.partialValueNote')}
        </p>
      ) : null}
      {pending > 0 ? (
        <p className="text-xs leading-snug text-ink-3">
          {t('stocktake.unverifiedNote', { count: pending, formatted: nf.format(pending) })}
        </p>
      ) : null}
    </div>
  )
}

/**
 * A collapsible, read-only "sold today" reference in the stock-opname flow — units + gross omzet
 * per MENU item over the local day (from useItemSales). Collapsed by default so the count stays
 * the focus; expanded it caps its height and scrolls. Purely informational (helps the operator
 * sanity-check the physical count); it never feeds the ingredient submission.
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
    <div className="mt-1.5">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        // A reference aid, not chrome: a quiet line that scrolls away with the hint.
        className="flex min-h-11 w-full items-center gap-[7px] text-left text-xs font-semibold text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald"
      >
        <ChevronDown
          className={cn('size-[15px] shrink-0 transition-transform', open && 'rotate-180')}
          strokeWidth={2.2}
          aria-hidden="true"
        />
        <span>{t('stocktake.soldTodayTitle')}</span>
      </button>
      {open ? (
        <div className="mb-1.5">
          <div className="max-h-44 overflow-y-auto overscroll-contain">
            {loading ? (
              <p className="py-2 text-center text-xs text-ink-3">…</p>
            ) : items.length === 0 ? (
              <p className="py-2 text-center text-xs text-ink-3">{t('stocktake.soldTodayEmpty')}</p>
            ) : (
              <ul>
                {items.map((it) => (
                  <li
                    key={it.menuItemId}
                    className="flex items-center gap-[11px] border-b border-line py-[7px]"
                  >
                    <span className="tnum w-[34px] shrink-0 font-mono text-xs font-bold text-ink">
                      {t('stocktake.soldTimes', {
                        formatted: new Intl.NumberFormat(locale).format(it.soldQty),
                      })}
                    </span>
                    <span className="min-w-0 flex-1 truncate text-xs text-ink-2">{it.name}</span>
                    <span className="tnum shrink-0 font-mono text-xs text-ink-3">
                      {formatMoney(it.revenueMinor, currency, locale)}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
          <p className="pb-2.5 pt-2 text-xs leading-relaxed text-ink-3">
            {t('stocktake.soldTodayNote')}
          </p>
        </div>
      ) : null}
    </div>
  )
}

function StocktakeIngredientRow({
  ingredient,
  value,
  state,
  currency,
  locale,
  usedToday,
  onMark,
  onOpen,
}: {
  ingredient: Ingredient
  value: string
  state: StocktakeRowState
  currency: string
  locale: string
  /** Quantity today's sales consumed by recipe ("terpakai hari ini", V42) — 0 when none. */
  usedToday: number
  onMark: () => void
  onOpen: () => void
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
  const invalid = state === 'invalid'
  const done = state === 'verified' || state === 'changed'

  // One line, never wrapping: the figure column is a fixed width, so this string's budget no
  // longer changes when a row grows a variance note. The unit rides the system figure only —
  // repeating it on "terpakai" was what pushed liter rows to two lines.
  const meta = [
    t('stocktake.systemQty', {
      qty: formatShownQty(systemQty, ingredient, locale),
      unit,
    }),
    usedToday > 0
      ? t('stocktake.usedTodayShort', {
          qty: formatShownQty(usedToday, ingredient, locale),
        })
      : null,
  ]
    .filter(Boolean)
    .join(' · ')

  const markLabel =
    state === 'pending'
      ? t('stocktake.markPending', { name: ingredient.name })
      : state === 'verified'
        ? t('stocktake.markUnverify', { name: ingredient.name })
        : t('stocktake.markReset', { name: ingredient.name })

  // Always rendered at a fixed height (empty when there is nothing to say) so a noted row and a
  // plain row are exactly the same height. The unit is dropped — it sits directly above.
  const note = invalid
    ? t('stocktake.notCounted')
    : varianceQty != null && varianceQty !== 0
      ? `${formatSignedShownQty(varianceQty, ingredient, locale)}${
          valuePreviewMinor != null
            ? ` · ${formatMoney(Math.abs(valuePreviewMinor), previewCurrency, locale)}`
            : ''
        }`
      : ''

  return (
    <li
      // The footer's "show me" jump reads this.
      data-invalid={invalid ? true : undefined}
      className={cn(
        'flex items-start gap-2 border-b border-line px-4 py-3 transition-colors',
        invalid && 'bg-tint-loss/50',
      )}
    >
      {/* The check mark: pending = empty ring, checked/changed = filled ink with a tick, invalid =
          a red "!". Tapping it is the one-tap door out of "unchecked" (see stocktakeMarks). */}
      <button
        type="button"
        onClick={onMark}
        aria-label={markLabel}
        className="-ml-2.5 -mt-0.5 grid size-11 shrink-0 place-items-center rounded-xl transition-colors active:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald"
      >
        <span
          aria-hidden="true"
          className={cn(
            'grid size-6 place-items-center rounded-full border-[1.5px] transition-colors',
            invalid
              ? 'border-loss bg-surface text-loss'
              : done
                ? 'border-emerald bg-emerald text-on-emerald'
                : 'border-ink-300 bg-surface',
          )}
        >
          {invalid ? (
            <span className="text-base font-bold leading-none">!</span>
          ) : done ? (
            <Check className="size-3.5" strokeWidth={3.4} />
          ) : null}
        </span>
      </button>

      <div className="min-w-0 flex-1 pt-0.5">
        {/* The name owns its line and wraps to a second rather than truncating: on a 360px phone a
            long name would lose its tail, and choosing the wrong row is the one mistake this screen
            must not invite — picking the wrong row MANUFACTURES the variance the leak report later
            reads as theft. Two lines is the ceiling. */}
        <div className="line-clamp-2 text-sm font-semibold leading-snug text-ink">
          {ingredient.name}
        </div>
        <div className="tnum mt-1 h-[15px] truncate font-mono text-2xs leading-[15px] text-ink-3">
          {meta}
        </div>
      </div>

      {/* A fixed-width figure column so every number on the sheet ends on the same axis; the unit
          has a column of its own. The figure is the largest thing on the row because it is the
          only thing on the row the operator is here to produce. 120px, not the design's 128: the
          design is drawn at 412px and the harness shoots 390, where the wider column clipped the
          "used" figure off the meta line of the very first row. */}
      <div className="flex w-[120px] shrink-0 flex-col items-end gap-[3px]">
        <button
          type="button"
          onClick={onOpen}
          data-count-field
          // The figure is IN the label: an aria-label replaces the button's content as its name,
          // so a reader that only heard "counted for X, in kg" would never hear the count itself.
          aria-label={t('stocktake.rowFigureLabel', {
            name: ingredient.name,
            value,
            unit,
          })}
          className={cn(
            'flex min-h-11 max-w-full items-baseline gap-1 rounded-t border-b-2 pb-[5px] pl-1.5 pr-0.5 transition-colors active:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-emerald',
            invalid ? 'border-loss' : state === 'pending' ? 'border-line-strong' : 'border-ink',
          )}
        >
          <span
            className={cn(
              'tnum min-w-14 text-right font-mono font-semibold leading-none',
              // A nine-digit slip still has to be READ, not clipped — it drops a size instead.
              value.length > 6 ? 'text-base' : 'text-xl',
              invalid ? 'text-loss' : 'text-ink',
            )}
          >
            {value}
          </span>
          <span
            aria-hidden="true"
            title={unit}
            className="w-7 shrink-0 truncate text-left text-xs font-medium text-ink-3"
          >
            {unit}
          </span>
        </button>
        <div
          className={cn(
            'tnum h-[15px] w-full truncate text-right font-mono text-2xs font-bold leading-[15px]',
            invalid ? 'text-loss' : tone ? TONE_TEXT[tone] : 'text-ink-3',
          )}
        >
          {note}
        </div>
      </div>
    </li>
  )
}

/**
 * The count sheet — one item's figure, edited on a surface of its own. Header (name + system
 * reference), the figure over a rule with the unit beside it, a hint that says which separators
 * this item accepts (or why the figure is unusable), the live variance preview, a 3×4 pad, and
 * "same as system" / "save". Everything here is the SHOWN unit; the base conversion happens in
 * parseShownQtyInput on save, like the old inline field.
 */
function CountSheet({
  ingredient,
  raw,
  usedToday,
  currency,
  locale,
  onChange,
  onReset,
  onSave,
  onClose,
}: {
  ingredient: Ingredient
  raw: string
  usedToday: number
  currency: string
  locale: string
  onChange: (raw: string) => void
  onReset: () => void
  /** Commits the draft into the marks; the sheet closes itself (with its exit) afterwards. */
  onSave: () => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const inputRef = useRef<HTMLInputElement>(null)
  // The first key after opening REPLACES the seeded figure. The old field selected all on focus so
  // the first keystroke meant "replace this, not backspace three times"; the pad keeps that meaning.
  const [pristine, setPristine] = useState(true)

  const fraction = allowsFraction(ingredient)
  const unit = shownUnit(ingredient)
  const parsed = parseShownQtyInput(raw, ingredient)
  const canSave = parsed != null
  const varianceQty = parsed != null ? parsed - ingredient.stockQty : null
  const valueMinor =
    varianceQty != null && ingredient.unitCostMinor != null
      ? varianceQty * ingredient.unitCostMinor
      : null
  const previewCurrency = ingredient.costCurrency ?? currency
  const tone: Tone = varianceQty != null ? toneOfVariance(varianceQty) : 'balanced'
  const separator = decimalSeparatorOf(locale)
  const costPerShown = shownUnitCostMinor(ingredient)

  // DialogOverlay focuses its panel in a parent effect, which runs AFTER this child's — so take the
  // focus one frame later. A physical keyboard then types straight into the figure; inputMode=none
  // keeps the on-screen keyboard down, which is the whole reason the sheet exists.
  useEffect(() => {
    const id = requestAnimationFrame(() => inputRef.current?.focus())
    return () => cancelAnimationFrame(id)
  }, [])

  const press = (key: CountKey) => {
    onChange(applyCountKey(pristine ? '' : raw, key, fraction))
    setPristine(false)
  }

  // A physical keyboard goes through the SAME reducer as the pad, so it gets the same rules —
  // first key replaces, no second separator, no fraction on a whole-unit item — instead of a
  // second, looser path. The caret is invisible and always at the end, so the keys that would
  // move it (arrows, Home/End) or edit at it (Delete) are folded into that model rather than left
  // to act on a position nobody can see. `save` is the overlay's own close (Enter = Save).
  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>, save: () => void) => {
    if (e.key === 'Enter') {
      if (canSave) {
        e.preventDefault()
        save()
      }
      return
    }
    if (e.ctrlKey || e.metaKey || e.altKey) return
    if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End'].includes(e.key)) {
      e.preventDefault()
      return
    }
    const key: CountKey | null =
      e.key === 'Backspace' || e.key === 'Delete'
        ? 'backspace'
        : /^[0-9.,]$/.test(e.key)
          ? (e.key as CountKey)
          : null
    if (key == null) return
    e.preventDefault()
    press(key)
  }

  // Paste replaces the seeded figure like a first key does, is sanitised like typing, and is
  // refused outright past the base cap — a clipboard slip is exactly the kind of figure the cap
  // exists for.
  const onPaste = (e: React.ClipboardEvent<HTMLInputElement>) => {
    e.preventDefault()
    const pasted = e.clipboardData.getData('text')
    const next = sanitizeShownQtyInput(pristine ? pasted : `${raw}${pasted}`)
    if (!countDraftWithinCap(next, fraction)) return
    onChange(next)
    setPristine(false)
  }

  const meta = [
    t('stocktake.systemQty', {
      qty: formatShownQty(ingredient.stockQty, ingredient, locale),
      unit,
    }),
    usedToday > 0
      ? t('stocktake.usedTodayLong', {
          qty: formatShownQty(usedToday, ingredient, locale),
          unit,
        })
      : null,
    costPerShown != null
      ? t('stocktake.unitCost', {
          money: formatMoney(costPerShown, previewCurrency, locale),
          unit,
        })
      : t('stocktake.noCost'),
  ]
    .filter(Boolean)
    .join(' · ')

  const hint = canSave
    ? fraction
      ? t('stocktake.sheetHintFraction')
      : t('stocktake.sheetHintWhole')
    : fraction
      ? t('stocktake.sheetInvalidFraction')
      : t('stocktake.sheetInvalidWhole', { unit })

  return (
    <DialogOverlay onClose={onClose} ariaLabel={ingredient.name} className="p-0">
      {(requestClose) => {
        const save = () => {
          onSave()
          requestClose()
        }
        return (
          <div className="flex flex-col" data-testid="stocktake-count-sheet">
            <div className="flex shrink-0 justify-center pb-1 pt-2.5 sm:hidden" aria-hidden="true">
              <div className="h-1 w-10 rounded-full bg-ink-300" />
            </div>
            <div className="flex shrink-0 items-start gap-2 border-b border-line px-4 pb-3 pt-1 sm:pt-4">
              <div className="min-w-0 flex-1 pt-1">
                <div className="text-base font-bold leading-tight text-ink">{ingredient.name}</div>
                <div className="tnum mt-1 font-mono text-xs leading-snug text-ink-3">{meta}</div>
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

            <div className="shrink-0 px-4 pb-3 pt-4">
              <div
                className={cn(
                  'flex items-baseline gap-2 border-b-2 pb-2 transition-colors',
                  canSave ? 'border-ink' : 'border-loss',
                )}
              >
                <input
                  ref={inputRef}
                  type="text"
                  inputMode="none"
                  autoComplete="off"
                  spellCheck={false}
                  value={raw}
                  placeholder="0"
                  aria-label={t('stocktake.countedForItemWithUnit', {
                    name: ingredient.name,
                    unit,
                  })}
                  // Every ordinary key is intercepted above; this only sees IME / autofill input,
                  // which gets the same sanitising and the same cap.
                  onChange={(e) => {
                    const next = sanitizeShownQtyInput(e.target.value)
                    if (!countDraftWithinCap(next, fraction)) return
                    onChange(next)
                    setPristine(false)
                  }}
                  onKeyDown={(e) => onKeyDown(e, save)}
                  onPaste={onPaste}
                  className={cn(
                    'tnum w-full min-w-0 flex-1 bg-transparent text-right font-mono text-2xl font-bold leading-none tracking-display caret-transparent placeholder:text-ink-3/40 focus:outline-none',
                    canSave ? 'text-ink' : 'text-loss',
                  )}
                />
                <span className="shrink-0 text-sm font-semibold text-ink-3">{unit}</span>
              </div>
              <div className="mt-2 flex min-h-[34px] items-start justify-between gap-2.5">
                <span
                  className={cn(
                    'text-xs font-medium leading-snug',
                    canSave ? 'text-ink-3' : 'text-loss',
                  )}
                >
                  {hint}
                </span>
                {varianceQty != null && varianceQty !== 0 ? (
                  <span className="shrink-0 text-right">
                    <span
                      className={cn(
                        'tnum block font-mono text-sm font-bold leading-tight',
                        TONE_TEXT[tone],
                      )}
                    >
                      {formatSignedShownQty(varianceQty, ingredient, locale)} {unit}
                    </span>
                    {valueMinor != null && valueMinor !== 0 ? (
                      <span
                        className={cn(
                          'tnum mt-0.5 block font-mono text-xs font-semibold leading-tight',
                          TONE_TEXT[tone],
                        )}
                      >
                        {formatMoney(Math.abs(valueMinor), previewCurrency, locale)}
                      </span>
                    ) : null}
                  </span>
                ) : null}
              </div>
            </div>

            <QtyKeypad fraction={fraction} separator={separator} onKey={press} />

            <div className="flex shrink-0 gap-2 px-4 pt-3" style={SAFE_BOTTOM(22)}>
              <Button
                variant="ghost"
                size="2xl"
                className="shrink-0 px-3.5 text-xs"
                onClick={() => {
                  onReset()
                  setPristine(true)
                }}
              >
                {t('stocktake.sheetReset')}
              </Button>
              <Button
                size="2xl"
                className="flex-1"
                data-testid="stocktake-count-save"
                disabled={!canSave}
                onClick={save}
              >
                {t('stocktake.sheetSave')}
              </Button>
            </div>
          </div>
        )
      }}
    </DialogOverlay>
  )
}

/**
 * ADR 0068 part 3 — the confirm behind an implausible count. Each flagged line gets its own card:
 * system vs counted, then the reasons WRITTEN OUT from the guard's numbers — the ratio that makes
 * someone recount, the value against the threshold it cleared.
 */
function VarianceGuardDialog({
  flags,
  ingredientById,
  currency,
  locale,
  busy,
  onRecount,
  onProceed,
}: {
  flags: StocktakeVarianceFlag[]
  ingredientById: Map<string, Ingredient>
  currency: string
  locale: string
  busy: boolean
  onRecount: () => void
  onProceed: () => void
}) {
  const { t } = useTranslation()
  const ratioFmt = new Intl.NumberFormat(locale, { maximumFractionDigits: 0 })

  function reasonsFor(flag: StocktakeVarianceFlag, ing: Ingredient): string[] {
    const out: string[] = []
    if (flag.reasons.includes('ratio') && flag.ratio != null) {
      const ratio = ratioFmt.format(flag.ratio)
      const above = flag.countedQty > flag.systemQty
      const head = above
        ? t('stocktake.guardReasonRatioAbove', { ratio })
        : t('stocktake.guardReasonRatioBelow', { ratio })
      // Naming the slip only makes sense when there IS a display unit to confuse with the base one,
      // and the direction matters: a count 1000× too BIG is a gram figure typed into the kg field;
      // 1000× too SMALL is the other way round.
      const tail = ing.displayUnit
        ? above
          ? t('stocktake.guardReasonSlipAbove', { from: ing.unit, to: shownUnit(ing) })
          : t('stocktake.guardReasonSlipBelow', { from: shownUnit(ing), to: ing.unit })
        : above
          ? t('stocktake.guardReasonZerosAbove')
          : t('stocktake.guardReasonZerosBelow')
      out.push(`${head} ${tail}`)
    }
    if (flag.reasons.includes('value') && flag.varianceValueMinor != null) {
      const cur = ing.costCurrency ?? currency
      out.push(
        t('stocktake.guardReasonValue', {
          value: formatMoney(flag.varianceValueMinor, cur, locale),
          threshold: formatMoney(valueThresholdMinor(cur), cur, locale),
        }),
      )
    }
    return out
  }

  return (
    <DialogOverlay
      onClose={onRecount}
      ariaLabel={t('stocktake.varianceGuardTitle')}
      className="p-0"
    >
      {(requestClose) => (
        <div className="flex flex-col" data-testid="stocktake-variance-confirm">
          <div className="flex shrink-0 justify-center pb-1 pt-2.5 sm:hidden" aria-hidden="true">
            <div className="h-1 w-10 rounded-full bg-ink-300" />
          </div>
          <div className="flex shrink-0 gap-[11px] border-b border-line px-[18px] pb-3.5 pt-1.5 sm:pt-4">
            <TriangleAlert className="mt-0.5 size-[21px] shrink-0 text-amber" aria-hidden="true" />
            <div className="min-w-0 flex-1">
              <h3 className="text-base font-bold leading-tight text-ink">
                {t('stocktake.varianceGuardTitle')}
              </h3>
              <p className="mt-1 text-xs leading-relaxed text-ink-2">
                {flags.length === 1
                  ? t('stocktake.varianceGuardIntroOne')
                  : t('stocktake.varianceGuardIntroMany')}
              </p>
            </div>
          </div>
          <div className="space-y-2.5 px-[18px] pb-2 pt-3.5">
            {flags.map((flag) => {
              const ing = ingredientById.get(flag.ingredientId)
              if (!ing) return null
              const unit = shownUnit(ing)
              return (
                <div key={flag.ingredientId} className="rounded-2xl bg-amber-tint p-3.5">
                  <div className="text-sm font-bold leading-snug text-ink">{ing.name}</div>
                  <dl className="mt-2 space-y-1.5">
                    <div className="flex justify-between gap-2.5">
                      <dt className="text-xs font-medium text-ink-3">
                        {t('stocktake.guardSystem')}
                      </dt>
                      <dd className="tnum font-mono text-xs font-semibold text-ink-2">
                        {formatShownQty(flag.systemQty, ing, locale)} {unit}
                      </dd>
                    </div>
                    <div className="flex justify-between gap-2.5">
                      <dt className="text-xs font-medium text-ink-3">
                        {t('stocktake.guardCounted')}
                      </dt>
                      <dd className="tnum font-mono text-xs font-bold text-amber">
                        {formatShownQty(flag.countedQty, ing, locale)} {unit}
                      </dd>
                    </div>
                  </dl>
                  <ul className="mt-2.5 space-y-1.5 border-t border-warning-line pt-2.5">
                    {reasonsFor(flag, ing).map((text, i) => (
                      <li key={i} className="flex items-start gap-2">
                        <span
                          className="mt-[5px] size-1 shrink-0 rounded-full bg-amber"
                          aria-hidden="true"
                        />
                        <span className="text-xs font-medium leading-relaxed text-amber-2">
                          {text}
                        </span>
                      </li>
                    ))}
                  </ul>
                </div>
              )
            })}
            <p className="text-xs leading-relaxed text-ink-3">{t('stocktake.varianceGuardNote')}</p>
          </div>
          <div
            className="flex shrink-0 gap-2 border-t border-line px-[18px] pt-3"
            style={SAFE_BOTTOM(22)}
          >
            <Button
              variant="ghost"
              size="2xl"
              className="flex-1"
              data-testid="stocktake-variance-recount"
              onClick={requestClose}
            >
              {t('stocktake.varianceGuardRecount')}
            </Button>
            <Button
              size="2xl"
              className="flex-1"
              data-testid="stocktake-variance-proceed"
              disabled={busy}
              onClick={onProceed}
            >
              {busy ? <Spinner /> : t('stocktake.varianceGuardProceed')}
            </Button>
          </div>
        </div>
      )}
    </DialogOverlay>
  )
}

/** "Leave this count?" — names how many rows would be lost. */
function DiscardDialog({
  workedRows,
  locale,
  onKeep,
  onLeave,
}: {
  workedRows: number
  locale: string
  onKeep: () => void
  onLeave: () => void
}) {
  const { t } = useTranslation()
  return (
    <DialogOverlay onClose={onKeep} ariaLabel={t('stocktake.discardTitle')} className="p-0">
      {(requestClose) => (
        <div data-testid="stocktake-discard-confirm">
          <div className="border-b border-line px-[18px] py-[18px]">
            <h3 className="text-base font-bold leading-tight text-ink">
              {t('stocktake.discardTitle')}
            </h3>
            <p className="mt-1.5 text-xs leading-relaxed text-ink-2">
              {t('stocktake.discardBody', {
                count: workedRows,
                formatted: new Intl.NumberFormat(locale).format(workedRows),
              })}
            </p>
          </div>
          <div className="flex gap-2 px-[18px] pt-3.5" style={SAFE_BOTTOM(18)}>
            <Button
              variant="ghost"
              size="xl"
              className="flex-1"
              data-testid="stocktake-discard-keep"
              // Through the overlay's own close so the exit plays; its teardown schedules an
              // unwind of the parked entry, which the sheet's (or host's) back-dismiss ADOPTS the
              // moment `discardAsked` flips back and re-enables it — no fresh push, no double park.
              onClick={requestClose}
            >
              {t('stocktake.discardKeep')}
            </Button>
            <Button
              size="xl"
              className="flex-1 bg-loss text-white shadow-none hover:bg-loss/90"
              data-testid="stocktake-discard-leave"
              onClick={onLeave}
            >
              {t('stocktake.discardLeave')}
            </Button>
          </div>
        </div>
      )}
    </DialogOverlay>
  )
}

function StocktakeSummary({
  result,
  currency,
  locale,
  ingredientById,
  onDone,
}: {
  result: IngredientStocktakeResponse
  currency: string
  locale: string
  /** The counted items, for their DISPLAY units — the response carries only base units. */
  ingredientById: Map<string, Ingredient>
  onDone: () => void
}) {
  const { t } = useTranslation()
  const tone: Tone =
    result.shrinkageMinor === 0 ? 'balanced' : result.shrinkageMinor > 0 ? 'loss' : 'gain'
  const variedLines = result.lines.filter((l) => l.varianceQty !== 0)
  // No "partial value" note here, unlike the pre-submit footer: the server refuses a count whose
  // costed lines span two currencies (IngredientStocktakeWriter → 422), so a result that exists
  // is single-currency by construction.

  return (
    <div
      className="min-h-0 flex-1 overflow-y-auto overscroll-contain"
      data-testid="stocktake-summary"
    >
      {/* min-h-full + flex: on the phone screen host a short summary would otherwise leave the
          Done button stranded halfway up the screen with empty space under it. */}
      <div className="flex min-h-full flex-col">
        <div className="shrink-0 px-[18px] pt-4">
          {/* The one "done" moment: the count is in. */}
          <div className="mb-3 flex items-center gap-3">
            <SuccessMark size="sm" />
            <span className="text-lg font-bold text-ink">{t('stocktake.submittedTitle')}</span>
          </div>
          <div className="flex items-baseline justify-between gap-2.5 text-xs">
            <span className="font-medium text-ink-3">{t('stocktake.countedAt')}</span>
            <span className="font-semibold text-ink">
              {new Intl.DateTimeFormat(locale, {
                dateStyle: 'medium',
                timeStyle: 'short',
              }).format(new Date(result.countedAt))}
            </span>
          </div>

          <div
            className={cn('mt-3.5 rounded-2xl px-4 py-[18px] text-center', TONE_BANNER[tone])}
          >
            <div className="text-xs font-bold uppercase tracking-eyebrow">
              {tone === 'balanced'
                ? t('stocktake.resultBalanced')
                : tone === 'loss'
                  ? t('stocktake.resultLoss')
                  : t('stocktake.resultGain')}
            </div>
            {/* currency null = no counted line carried a cost — nothing was posted, so showing a
                zero money figure would imply a valuation that never happened. */}
            {result.currency != null ? (
              <div className="tnum mt-2 font-mono text-2xl font-bold leading-none tracking-display">
                {formatMoney(Math.abs(result.shrinkageMinor), result.currency, locale)}
              </div>
            ) : (
              <p className="mt-2 text-xs font-medium leading-relaxed">
                {t('stocktake.noValuedLines')}
              </p>
            )}
          </div>
        </div>

        <div className="flex-1 px-[18px] pb-2 pt-[18px]">
          {variedLines.length > 0 ? (
            <>
              <div className="text-xs font-semibold text-ink-3">
                {t('stocktake.varianceLinesTitle')}
              </div>
              <ul className="mt-1">
                {variedLines.map((line) => (
                  <StocktakeVarianceLine
                    key={line.ingredientId}
                    line={line}
                    ingredient={ingredientById.get(line.ingredientId)}
                    currency={result.currency ?? currency}
                    locale={locale}
                  />
                ))}
              </ul>
            </>
          ) : (
            <p className="px-3 py-7 text-center text-xs leading-relaxed text-ink-3">
              {t('stocktake.noVariedLines')}
            </p>
          )}
        </div>

        <div
          className="sticky bottom-0 border-t border-line bg-surface px-[18px] pt-3"
          style={SAFE_BOTTOM(20)}
        >
          <Button size="2xl" className="w-full" onClick={onDone}>
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
  ingredient: Ingredient | undefined
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
    <li className="flex items-center gap-3 border-b border-line py-[11px]">
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-medium leading-snug text-ink">{line.name}</div>
        <div className="tnum mt-0.5 font-mono text-2xs leading-snug text-ink-3">
          {t('stocktake.lineArrow', { system, counted, unit })}
        </div>
      </div>
      <div className="shrink-0 text-right">
        <div className={cn('tnum font-mono text-sm font-bold leading-tight', TONE_TEXT[tone])}>
          {variance} {unit}
        </div>
        {line.unitCostMinor != null ? (
          <div
            className={cn(
              'tnum mt-0.5 font-mono text-xs font-semibold leading-tight',
              TONE_TEXT[tone],
            )}
          >
            {formatMoney(Math.abs(line.varianceValueMinor), currency, locale)}
          </div>
        ) : null}
      </div>
    </li>
  )
}
