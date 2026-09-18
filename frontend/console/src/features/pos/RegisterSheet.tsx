/**
 * RegisterSheet — closing kasir (ADR 0036). Open the drawer with a counted float; close it with a
 * physical recount. Expected cash and the SIGNED over/short (selisih kas) are SERVER-computed —
 * this sheet only collects the two counts and presents the verdict (green = over, red = short).
 *
 * Reached from the till menu, which disables it offline AND while the sync queue is non-empty
 * (closing over unsynced cash sales would understate expected cash — ADR 0028).
 *
 * Money rule (rule 8): inputs are major units parsed exponent-aware via parseDiscountInput (IDR
 * exp 0); everything renders through formatMoney. Strings rule (rule 9): i18n keys only.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Banknote, X } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { useScrollLock } from '@/components/mobile/useScrollLock'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { FormSkeleton } from '@/components/ui/Skeleton'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import { useBills } from './billsApi'
import { closeBlockedByOpenBills, needsCountConfirmation } from './lib/closeGuard'
import { parseDiscountInput } from './lib/discountInput'
import { minorToMajorInput } from './lib/registerFloat'
import { registerErrorKey } from './lib/registerErrors'
import {
  useCloseRegisterSession,
  useCurrentRegisterSession,
  useLastClosedSession,
  useOpenRegisterSession,
  useRegisterExpected,
  type RegisterSessionResponse,
} from './registerApi'

// How many blocking open bills the close form lists before "+N more" (the switcher has them all).
const OPEN_BILLS_SHOWN = 5
// ISO-4217 "no currency" — what BillWriter.open stamps on a bill until its first line.
const BILL_CURRENCY_PLACEHOLDER = 'XXX'

// Per-tender label keys (ADR 0038 daily close v2) — i18n only (rule 9).
const TENDER_LABEL_KEY: Record<string, string> = {
  CASH: 'register.tenderCash',
  CARD: 'register.tenderCard',
  QRIS: 'register.tenderQris',
  ONLINE: 'register.tenderOnline',
}

export function RegisterSheet({
  session,
  currency,
  locale,
  reasonMessage,
  onClose,
  onContinueToStocktake,
  onPrintSummary,
  onOpenBills,
}: {
  session: CompanySession
  currency: string
  locale: string
  /** An optional short explanatory line shown above the open form — e.g. why the sheet appeared
   * unprompted (the "open the register first" payment gate, owner request). Only rendered while
   * there's no open session to close. */
  reasonMessage?: string
  onClose: () => void
  /** Owner request — "after closing, continue straight to stock opname": when provided, the
   * close verdict offers a PRIMARY continue-to-stocktake action (Done demotes to secondary).
   * Callers omit it when the stocktake can't run right now (offline — ADR 0038 phase 3 has no
   * offline path). */
  onContinueToStocktake?: () => void
  /** Owner request — "print today's transaction summary at closing": when provided, both the close
   * FORM (a live X-report over the open session) and the after-close VERDICT (the final Z-report)
   * offer a "Cetak ringkasan" action, handed the session id to summarize. The parent owns the print
   * overlay (the DailySummary/ThermalReceipt surface) so this sheet stays print-agnostic. */
  onPrintSummary?: (sessionId: string) => void
  /** ADR 0086 — the door to the order switcher when open bills block the close. Omitted (no till
   * mounted, no route to offer) → the block still renders, without its button. */
  onOpenBills?: () => void
}) {
  const { t } = useTranslation()
  useBackDismiss(onClose)
  useScrollLock()
  const currentQuery = useCurrentRegisterSession(session)
  const openSession = useOpenRegisterSession(session)
  const closeSession = useCloseRegisterSession(session)
  const currentId = currentQuery.data?.id ?? null
  // Live per-tender expected for the OPEN session (ADR 0038) — shown on the close form.
  const expectedQuery = useRegisterExpected(session, currentId, !!currentId)
  // ADR 0086 — no open bill survives a close. The preview carries the count; while it is > 0 the
  // close button is withheld and the bills are listed (same cache key as the till's switcher and
  // the phone home, so nothing extra is fetched when the till is mounted). Unknown never blocks —
  // the server re-counts under its lock and its 409 is the backstop.
  const openBillCount = expectedQuery.data?.openBillCount ?? null
  const closeBlocked = closeBlockedByOpenBills(openBillCount)
  const openBillsQuery = useBills(session, !!currentId && closeBlocked)
  const openBills = openBillsQuery.data ?? []
  // An EMPTY bill — the accidental one this list exists for — carries the server's "XXX"
  // placeholder until its first line sets the real currency; it is the drawer's currency here.
  const billCurrency = (code: string) => (code === BILL_CURRENCY_PLACEHOLDER ? currency : code)

  const [floatInput, setFloatInput] = useState('')
  // The cashier typed in the float field — never overwrite their entry with the default.
  const [floatTouched, setFloatTouched] = useState(false)
  const [countedInput, setCountedInput] = useState('')
  // Per non-cash tender counted/settled amounts (ADR 0038 phase 2), keyed by tender type.
  const [tenderCounts, setTenderCounts] = useState<Record<string, string>>({})
  // Held after a successful close so the verdict stays visible (the query flips to 204/null).
  const [closed, setClosed] = useState<RegisterSessionResponse | null>(null)
  // Owner request — the cashier tapped "Close" while the counted drawer didn't match expected cash:
  // hold the close behind a confirm step so a fat-fingered count can be caught before it commits.
  const [confirmMismatch, setConfirmMismatch] = useState(false)
  // Inner confirm layer, inline conditional JSX within this always-mounted-while-open component.
  useBackDismiss(() => setConfirmMismatch(false), confirmMismatch)
  useScrollLock(confirmMismatch)

  const current = currentQuery.data ?? null
  const busy = openSession.isPending || closeSession.isPending
  // The live expected CASH in the drawer (float + cash sales − cash refunds) — the same preview the
  // cashier sees above. Null until the preview loads → the mismatch guard simply doesn't fire, and
  // the server stays the source of truth for the recorded over/short either way.
  const expectedCashMinor =
    expectedQuery.data?.tenders.find((td) => td.tenderType === 'CASH')?.expectedMinor ?? null
  // The counted-drawer entry as minor units — drives the mismatch guard and the confirm preview.
  const countedPreviewMinor = parseDiscountInput(countedInput, currency)
  const mismatchOverShort =
    expectedCashMinor != null ? countedPreviewMinor - expectedCashMinor : 0

  // Float default (owner request): "the float should be filled at start of the day, defaulting to
  // the last day's cash count" — the cash-stays-in-drawer-overnight model. Fetched only while the
  // OPEN form is showing. DERIVED, not copied into state (react-hooks/set-state-in-effect): the
  // input shows the default until the cashier types, then their entry wins unconditionally.
  const showOpenForm = !closed && !currentQuery.isLoading && !current
  const lastClosedQuery = useLastClosedSession(session, showOpenForm)
  const lastClosed = lastClosedQuery.data ?? null
  const floatDefault = lastClosed
    ? minorToMajorInput(lastClosed.countedCashMinor ?? 0, currency)
    : null
  const floatValue = floatTouched ? floatInput : (floatDefault ?? floatInput)

  // Friendly copy for a known register fault, else the server's detail message (rule 9).
  const faultMessage = (err: unknown): string => {
    const key = registerErrorKey(err)
    if (key) return t(key as Parameters<typeof t>[0])
    return err instanceof Error ? err.message : ''
  }

  function handleOpen() {
    openSession.mutate(
      { openingFloatMinor: parseDiscountInput(floatValue, currency), currency },
      {
        onSuccess: () => {
          setFloatInput('')
          setFloatTouched(false)
        },
      },
    )
  }

  // Close tapped: if the counted drawer differs from the system's expected cash, make the cashier
  // confirm the entered amount first (owner request) instead of committing the close straight away.
  function handleCloseClick() {
    if (!current) return
    if (needsCountConfirmation(expectedCashMinor, countedPreviewMinor)) {
      setConfirmMismatch(true)
      return
    }
    doClose()
  }

  function doClose() {
    if (!current) return
    setConfirmMismatch(false)
    // Only tenders the cashier actually entered are settled (ADR 0038); the rest are left unsettled.
    const nonCash = Object.entries(tenderCounts)
      .filter(([, raw]) => raw.trim() !== '')
      .map(([tenderType, raw]) => ({
        tenderType: tenderType as 'CARD' | 'QRIS' | 'ONLINE',
        countedMinor: parseDiscountInput(raw, currency),
      }))
    closeSession.mutate(
      {
        sessionId: current.id,
        countedCashMinor: parseDiscountInput(countedInput, currency),
        tenderCounts: nonCash.length > 0 ? nonCash : undefined,
      },
      {
        onSuccess: (res) => {
          if (res) setClosed(res)
          setCountedInput('')
          setTenderCounts({})
        },
      },
    )
  }

  const inputClass =
    'h-12 w-full rounded-xl border border-line bg-surface px-3 text-right font-mono text-lg tnum text-ink placeholder:text-ink-3/50 transition-colors focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/10'

  // State-aware header: the OPEN form must never announce itself as "closing kasir" (owner bug
  // report: the sheet reads as a forced close when it is actually asking to open the drawer).
  const title =
    closed || current
      ? t('register.titleClose')
      : currentQuery.isLoading
        ? t('register.title')
        : t('register.titleOpen')

  return (
    <>
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-scrim p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={title}
    >
      <div className="reveal max-h-full w-full max-w-sm overflow-y-auto overscroll-contain rounded-card border border-line bg-surface shadow-lg">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          <h2 className="flex items-center gap-2 font-display text-lg font-semibold text-ink">
            <Banknote className="size-5 text-emerald-2" aria-hidden="true" />
            {title}
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

        {closed ? (
          /* ── Verdict after close ─────────────────────────────────────────── */
          <div className="space-y-3 px-5 py-5" data-testid="register-verdict">
            <ResultRow label={t('register.float')} minor={closed.openingFloatMinor} currency={currency} locale={locale} />
            <ResultRow label={t('register.cashSales')} minor={closed.cashSalesMinor ?? 0} currency={currency} locale={locale} />
            <ResultRow label={t('register.cashRefunds')} minor={-(closed.cashRefundsMinor ?? 0)} currency={currency} locale={locale} />
            <div className="border-t border-line pt-3">
              <ResultRow label={t('register.expected')} minor={closed.expectedCashMinor ?? 0} currency={currency} locale={locale} strong />
              <ResultRow label={t('register.counted')} minor={closed.countedCashMinor ?? 0} currency={currency} locale={locale} strong />
            </div>
            <div
              className={cn(
                'rounded-xl px-4 py-3 text-center',
                (closed.overShortMinor ?? 0) === 0
                  ? 'bg-ink-50 text-ink-2'
                  : (closed.overShortMinor ?? 0) > 0
                    ? 'bg-tint-profit text-profit-ink'
                    : 'bg-tint-loss text-loss',
              )}
            >
              <div className="text-xs font-semibold uppercase tracking-eyebrow">
                {(closed.overShortMinor ?? 0) === 0
                  ? t('register.resultBalanced')
                  : (closed.overShortMinor ?? 0) > 0
                    ? t('register.resultOver')
                    : t('register.resultShort')}
              </div>
              <div className="tnum mt-1 font-mono text-2xl font-bold">
                {formatMoney(Math.abs(closed.overShortMinor ?? 0), currency, locale)}
              </div>
            </div>
            {/* Owner request — print today's transaction summary (Z-report) right after closing. */}
            {onPrintSummary ? (
              <Button
                variant="outline"
                className="w-full"
                data-testid="register-print-summary"
                onClick={() => onPrintSummary(closed.id)}
              >
                {t('register.summaryPrint')}
              </Button>
            ) : null}
            {onContinueToStocktake ? (
              <div className="space-y-2">
                <Button className="w-full" onClick={onContinueToStocktake}>
                  {t('register.continueStocktake')}
                </Button>
                <Button variant="outline" className="w-full" onClick={onClose}>
                  {t('register.done')}
                </Button>
              </div>
            ) : (
              <Button className="w-full" onClick={onClose}>
                {t('register.done')}
              </Button>
            )}
          </div>
        ) : currentQuery.isLoading ? (
          <div className="px-5 py-5">
            <FormSkeleton fields={3} />
          </div>
        ) : current ? (
          /* ── OPEN session → close form ──────────────────────────────────── */
          <div className="space-y-4 px-5 py-5">
            <div className="rounded-xl bg-ink-50 px-4 py-3 text-sm">
              <div className="flex items-baseline justify-between">
                <span className="text-ink-3">{t('register.openedAt')}</span>
                <span className="font-semibold text-ink">
                  {new Intl.DateTimeFormat(locale, { timeStyle: 'short', dateStyle: 'medium' }).format(
                    new Date(current.openedAt),
                  )}
                </span>
              </div>
              <div className="mt-1 flex items-baseline justify-between">
                <span className="text-ink-3">{t('register.float')}</span>
                <span className="tnum font-mono font-semibold text-ink">
                  {formatMoney(current.openingFloatMinor, currency, locale)}
                </span>
              </div>
            </div>
            {expectedQuery.data && expectedQuery.data.tenders.length > 0 ? (
              <div
                className="rounded-xl border border-line bg-surface px-4 py-3"
                data-testid="register-expected-breakdown"
              >
                <div className="mb-2 text-xs font-semibold uppercase tracking-eyebrow text-ink-3">
                  {t('register.expectedByTender')}
                </div>
                <dl className="space-y-2">
                  {expectedQuery.data.tenders.map((td) => {
                    const label = TENDER_LABEL_KEY[td.tenderType]
                      ? t(TENDER_LABEL_KEY[td.tenderType] as Parameters<typeof t>[0])
                      : td.tenderType
                    const isCash = td.tenderType === 'CASH'
                    return (
                      <div
                        key={td.tenderType}
                        className="flex items-center justify-between gap-3 text-sm"
                      >
                        <dt className="shrink-0 text-ink-3">{label}</dt>
                        {/* The amount keeps its natural width and the input wraps under it when
                            the row is too narrow (320px) — a fixed 96px amount column clipped
                            "IDR 1,850,000" at 360px. */}
                        <dd className="flex min-w-0 flex-1 flex-wrap items-center justify-end gap-x-2.5 gap-y-1.5">
                          <span className="tnum shrink-0 whitespace-nowrap text-right font-mono text-ink-2">
                            {formatMoney(td.expectedMinor, currency, locale)}
                          </span>
                          {/* Non-cash tenders take an optional counted/settled amount (cash is the
                              drawer count below). Left blank → that tender isn't settled at close. */}
                          {isCash ? (
                            <span className="w-24 shrink-0" aria-hidden />
                          ) : (
                            <input
                              aria-label={t('register.countedForTender', { tender: label })}
                              data-testid={`register-counted-${td.tenderType}`}
                              type="number"
                              min="0"
                              inputMode="numeric"
                              value={tenderCounts[td.tenderType] ?? ''}
                              onChange={(e) =>
                                setTenderCounts((p) => ({ ...p, [td.tenderType]: e.target.value }))
                              }
                              placeholder={t('register.countedPlaceholder')}
                              className="h-9 w-24 shrink-0 rounded-lg border border-line bg-surface px-2 text-right font-mono text-sm tnum text-ink placeholder:text-ink-3/50 focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/10"
                            />
                          )}
                        </dd>
                      </div>
                    )
                  })}
                </dl>
                <p className="mt-2 text-xs leading-relaxed text-ink-3">
                  {t('register.expectedByTenderHint')}
                </p>
              </div>
            ) : null}
            {closeBlocked ? (
              <div
                className="rounded-xl bg-tint-warning px-4 py-3"
                role="status"
                data-testid="register-open-bills"
              >
                <div className="text-sm font-semibold text-amber-2">
                  {t('register.openBillsTitle', { count: openBillCount ?? 0 })}
                </div>
                <p className="mt-1 text-xs leading-relaxed text-amber-2">
                  {t('register.openBillsBody')}
                </p>
                {openBills.length > 0 ? (
                  <ul className="mt-2.5 space-y-1.5">
                    {openBills.slice(0, OPEN_BILLS_SHOWN).map((bill) => (
                      <li
                        key={bill.id}
                        className="flex items-baseline justify-between gap-3 text-sm text-ink"
                      >
                        <span className="min-w-0 truncate">
                          <span className="font-medium">{bill.guestLabel}</span>
                          <span className="text-ink-3">
                            {' · '}
                            {t('bills.lineCount', { n: bill.lineCount })}
                          </span>
                        </span>
                        <span className="tnum shrink-0 font-mono">
                          {formatMoney(bill.runningTotalMinor, billCurrency(bill.currency), locale)}
                        </span>
                      </li>
                    ))}
                    {openBills.length > OPEN_BILLS_SHOWN ? (
                      <li className="text-xs text-ink-3">
                        {t('register.openBillsMore', { count: openBills.length - OPEN_BILLS_SHOWN })}
                      </li>
                    ) : null}
                  </ul>
                ) : null}
                {onOpenBills ? (
                  <Button
                    variant="outline"
                    className="mt-3 w-full"
                    data-testid="register-open-bills-go"
                    onClick={onOpenBills}
                  >
                    {t('register.openBillsGo')}
                  </Button>
                ) : null}
              </div>
            ) : null}
            <div>
              <label htmlFor="register-counted" className="mb-1.5 block text-sm font-medium text-ink">
                {t('register.countedLabel')}
              </label>
              <input
                id="register-counted"
                data-testid="register-counted"
                type="number"
                min="0"
                inputMode="numeric"
                value={countedInput}
                onChange={(e) => setCountedInput(e.target.value)}
                placeholder="0"
                className={inputClass}
              />
              <p className="mt-1.5 text-xs text-ink-3">{t('register.countedHint')}</p>
            </div>
            {closeSession.isError ? (
              <p className="text-xs text-loss" role="alert">
                {faultMessage(closeSession.error)}
              </p>
            ) : null}
            <Button
              className="w-full"
              data-testid="register-close"
              disabled={busy || countedInput.trim() === '' || closeBlocked}
              onClick={handleCloseClick}
            >
              {busy ? <Spinner /> : t('register.closeAction')}
            </Button>
            {/* Live X-report over the still-open session — print/review before committing the close. */}
            {onPrintSummary && current ? (
              <Button
                variant="outline"
                className="w-full"
                data-testid="register-print-summary"
                onClick={() => onPrintSummary(current.id)}
              >
                {t('register.summaryPrint')}
              </Button>
            ) : null}
          </div>
        ) : (
          /* ── No session → open form ─────────────────────────────────────── */
          <div className="space-y-4 px-5 py-5">
            {reasonMessage ? (
              <p
                className="rounded-xl bg-tint-warning px-3.5 py-2.5 text-sm font-medium text-amber-2"
                role="status"
                data-testid="register-reason"
              >
                {reasonMessage}
              </p>
            ) : null}
            <p className="text-sm text-ink-3">{t('register.openHint')}</p>
            <div>
              <label htmlFor="register-float" className="mb-1.5 block text-sm font-medium text-ink">
                {t('register.floatLabel')}
              </label>
              <input
                id="register-float"
                data-testid="register-float"
                type="number"
                min="0"
                inputMode="numeric"
                value={floatValue}
                onChange={(e) => {
                  setFloatTouched(true)
                  setFloatInput(e.target.value)
                }}
                placeholder="0"
                className={inputClass}
              />
              {lastClosed && !floatTouched ? (
                <p className="mt-1.5 text-xs text-ink-3" data-testid="register-float-default-hint">
                  {t('register.floatDefaultHint', {
                    when: new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(
                      new Date(lastClosed.closedAt ?? lastClosed.openedAt),
                    ),
                  })}
                </p>
              ) : null}
            </div>
            {openSession.isError ? (
              <p className="text-xs text-loss" role="alert">
                {faultMessage(openSession.error)}
              </p>
            ) : null}
            <Button
              className="w-full"
              data-testid="register-open"
              disabled={busy}
              onClick={handleOpen}
            >
              {busy ? <Spinner /> : t('register.openAction')}
            </Button>
          </div>
        )}
      </div>
    </div>

    {/* Owner request — mismatch confirm: the counted drawer ≠ the system's expected cash, so make
        the cashier reconfirm the amount before the close commits (a fat-finger safety net; the
        server still records whatever is submitted as the authoritative over/short). */}
    {confirmMismatch && expectedCashMinor != null ? (
      <div
        className="fixed inset-0 z-[60] grid place-items-center bg-scrim p-4 backdrop-blur-sm"
        role="dialog"
        aria-modal="true"
        aria-label={t('register.confirmMismatchTitle')}
      >
        <div
          className="reveal w-full max-w-sm space-y-4 rounded-card border border-line bg-surface p-5 shadow-lg"
          data-testid="register-mismatch-confirm"
        >
          <h3 className="font-display text-lg font-semibold text-ink">
            {t('register.confirmMismatchTitle')}
          </h3>
          <p className="text-sm text-ink-3">{t('register.confirmMismatchBody')}</p>
          <div className="space-y-2 rounded-xl bg-ink-50 px-4 py-3">
            <ResultRow
              label={t('register.expected')}
              minor={expectedCashMinor}
              currency={currency}
              locale={locale}
            />
            <ResultRow
              label={t('register.counted')}
              minor={countedPreviewMinor}
              currency={currency}
              locale={locale}
              strong
            />
            <div
              className={cn(
                'flex items-baseline justify-between border-t border-line pt-2 text-sm font-semibold',
                mismatchOverShort > 0 ? 'text-profit-ink' : 'text-loss',
              )}
            >
              <span>
                {mismatchOverShort > 0 ? t('register.resultOver') : t('register.resultShort')}
              </span>
              <span className="tnum font-mono">
                {formatMoney(Math.abs(mismatchOverShort), currency, locale)}
              </span>
            </div>
          </div>
          <div className="flex gap-2">
            <Button
              variant="outline"
              className="flex-1"
              data-testid="register-mismatch-recount"
              onClick={() => setConfirmMismatch(false)}
            >
              {t('register.confirmRecount')}
            </Button>
            <Button
              className="flex-1"
              data-testid="register-mismatch-proceed"
              disabled={busy}
              onClick={doClose}
            >
              {busy ? <Spinner /> : t('register.confirmProceed')}
            </Button>
          </div>
        </div>
      </div>
    ) : null}
    </>
  )
}

function ResultRow({
  label,
  minor,
  currency,
  locale,
  strong = false,
}: {
  label: string
  minor: number
  currency: string
  locale: string
  strong?: boolean
}) {
  return (
    <div className="flex items-baseline justify-between text-sm">
      <span className={strong ? 'font-semibold text-ink' : 'text-ink-3'}>{label}</span>
      <span className={cn('tnum font-mono', strong ? 'font-bold text-ink' : 'text-ink-2')}>
        {formatMoney(minor, currency, locale)}
      </span>
    </div>
  )
}
