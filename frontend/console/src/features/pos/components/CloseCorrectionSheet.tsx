/**
 * CloseCorrectionSheet — the manager/owner "Perbaiki closing" flow (ADR 0064), launched from a
 * closed day in ClosingHistorySheet. Two corrections:
 *
 *  1. CASH re-count: the corrected physical drawer count + a required reason → POST correct-close.
 *     The server re-derives the over/short and reverses + re-posts the finance variance (books stay
 *     balanced). Correcting to the recorded value is a server-side no-op.
 *  2. STOCK opname: hands back to the outlet's opname (the existing StocktakeSheet via onOpenStocktake)
 *     — a fresh count self-compensates (absolute set + a netting shrinkage entry), so there is no
 *     separate "correct stocktake" endpoint.
 *
 * Money rule (rule 8): server minor units through formatMoney; the input is major units parsed via
 * parseDiscountInput. Strings rule (rule 9): i18n keys only.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CalendarClock, Coins, Package, TriangleAlert, X } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { ApiError } from '@/lib/api'
import { formatMoney } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import { parseDiscountInput } from '../lib/discountInput'
import { minorToMajorInput } from '../lib/registerFloat'
import { useCorrectClose, useRegisterSummary } from '../registerApi'

// Map the server's RFC-7807 error slug → a localized message (rule 9: never show raw English
// diagnostics). Falls back to a generic save error for anything unmapped.
const CORRECT_ERROR_KEY: Record<string, string> = {
  'register-session-not-closed': 'closeCorrection.errorNotClosed',
  'register-close-correction-not-allowed': 'closeCorrection.errorNotAllowed',
}

function correctErrorKey(err: unknown): string {
  if (err instanceof ApiError && typeof err.problem?.type === 'string') {
    for (const slug of Object.keys(CORRECT_ERROR_KEY)) {
      if (err.problem.type.includes(slug)) return CORRECT_ERROR_KEY[slug]
    }
  }
  return 'closeCorrection.saveError'
}

export function CloseCorrectionSheet({
  session,
  locale,
  currency,
  sessionId,
  onClose,
  onCorrected,
  onOpenStocktake,
}: {
  session: CompanySession
  locale: string
  currency: string
  sessionId: string
  onClose: () => void
  onCorrected: () => void
  onOpenStocktake: () => void
}) {
  const { t } = useTranslation()
  const summaryQuery = useRegisterSummary(session, sessionId, true)
  const correct = useCorrectClose(session)
  const summary = summaryQuery.data

  const [countedInput, setCountedInput] = useState<string | null>(null)
  const [reason, setReason] = useState('')

  const money = (minor: number) => formatMoney(minor, currency, locale)
  const expected = summary?.expectedCashMinor ?? 0
  const currentCounted = summary?.countedCashMinor ?? 0
  // The input shows the recorded count until the manager edits it (derived, not copied to state).
  const countedValue = countedInput ?? minorToMajorInput(currentCounted, currency)
  const parsedCounted = parseDiscountInput(countedValue, currency)
  const newOverShort = parsedCounted - expected
  const unchanged = parsedCounted === currentCounted
  const canSubmit = !unchanged && reason.trim() !== '' && !correct.isPending

  function submit() {
    correct.mutate(
      { sessionId, countedCashMinor: parsedCounted, reason: reason.trim() },
      { onSuccess: () => onCorrected() },
    )
  }

  const overShortRow = (label: string, value: number) => (
    <div className="flex items-center justify-between text-[13px]">
      <span className="text-ink-3">{label}</span>
      <span
        className={
          'tnum font-mono font-semibold ' +
          (value > 0 ? 'text-emerald-2' : value < 0 ? 'text-loss' : 'text-ink-2')
        }
      >
        {money(value)}
      </span>
    </div>
  )

  return (
    <div
      className="fixed inset-0 z-[85] flex flex-col bg-paper"
      role="dialog"
      aria-modal="true"
      aria-label={t('closeCorrection.title')}
    >
      <header className="flex h-14 shrink-0 items-center gap-2.5 border-b border-line bg-surface px-4">
        <CalendarClock className="size-[18px] text-emerald-2" aria-hidden />
        <span className="min-w-0 flex-1 truncate text-[16px] font-bold text-ink">
          {t('closeCorrection.title')}
        </span>
        <button
          type="button"
          onClick={onClose}
          aria-label={t('common.close')}
          className="grid size-11 place-items-center rounded-full text-ink-3 hover:bg-hover hover:text-ink"
        >
          <X className="size-5" aria-hidden />
        </button>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
        {summaryQuery.isLoading ? (
          <div className="grid place-items-center py-16">
            <Spinner />
          </div>
        ) : summaryQuery.isError || !summary ? (
          <div className="mx-auto mt-10 max-w-sm rounded-2xl border border-loss/30 bg-tint-loss px-6 py-6 text-center text-sm text-loss">
            <TriangleAlert className="mx-auto mb-2 size-5" aria-hidden />
            {t('closeCorrection.loadError')}
          </div>
        ) : (
          <div className="mx-auto flex max-w-[520px] flex-col gap-5">
            {/* Cash re-count */}
            <section className="rounded-card border border-line bg-surface p-4">
              <div className="mb-3 flex items-center gap-2">
                <Coins className="size-4 text-ink-2" aria-hidden />
                <h2 className="text-[14px] font-bold text-ink">{t('closeCorrection.cashTitle')}</h2>
              </div>
              <div className="flex flex-col gap-1.5">
                {overShortRow(t('register.expected'), expected)}
                {overShortRow(t('closeCorrection.currentCounted'), currentCounted)}
                {overShortRow(t('closeCorrection.currentOverShort'), summary.overShortMinor ?? 0)}
              </div>

              <label
                htmlFor="correct-counted"
                className="mt-4 block text-[12px] font-semibold text-ink-2"
              >
                {t('closeCorrection.newCounted')}
              </label>
              <input
                id="correct-counted"
                inputMode="decimal"
                value={countedValue}
                onChange={(e) => setCountedInput(e.target.value)}
                className="mt-1 w-full rounded-xl border border-line bg-paper px-3 py-2.5 text-right font-mono text-[15px] font-bold text-ink outline-none focus:border-emerald-line"
              />
              <div className="mt-2">
                {overShortRow(t('closeCorrection.newOverShort'), newOverShort)}
              </div>

              <label
                htmlFor="correct-reason"
                className="mt-4 block text-[12px] font-semibold text-ink-2"
              >
                {t('closeCorrection.reasonLabel')}
              </label>
              <textarea
                id="correct-reason"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                rows={2}
                maxLength={500}
                placeholder={t('closeCorrection.reasonPlaceholder')}
                className="mt-1 w-full resize-none rounded-xl border border-line bg-paper px-3 py-2.5 text-[13px] text-ink outline-none focus:border-emerald-line"
              />

              {correct.isError ? (
                <p className="mt-2 text-[12.5px] font-semibold text-loss">
                  {t(correctErrorKey(correct.error) as Parameters<typeof t>[0])}
                </p>
              ) : null}

              <Button className="mt-3 w-full" onClick={submit} disabled={!canSubmit}>
                {correct.isPending ? t('closeCorrection.saving') : t('closeCorrection.save')}
              </Button>
              {unchanged ? (
                <p className="mt-2 text-center text-[11.5px] text-ink-3">
                  {t('closeCorrection.unchangedHint')}
                </p>
              ) : null}
            </section>

            {/* Stock opname re-count (hands off to the outlet opname) */}
            <section className="rounded-card border border-line bg-surface p-4">
              <div className="mb-2 flex items-center gap-2">
                <Package className="size-4 text-ink-2" aria-hidden />
                <h2 className="text-[14px] font-bold text-ink">{t('closeCorrection.stockTitle')}</h2>
              </div>
              <p className="text-[12.5px] text-ink-3">{t('closeCorrection.stockHint')}</p>
              <Button
                variant="outline"
                className="mt-3 w-full"
                onClick={onOpenStocktake}
              >
                {t('closeCorrection.stockAction')}
              </Button>
            </section>
          </div>
        )}
      </div>
    </div>
  )
}
