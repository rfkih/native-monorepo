/**
 * ClosingHistorySheet — the manager/owner "past closed-day sales history" browse (till menu). Lists
 * the outlet's recent CLOSED register sessions (newest first) with each day's net sales + transaction
 * count; tapping a day opens its full Z-report (the SAME {@link DailySummary} the close flow prints),
 * from which "Lihat transaksi" drills into that day's individual sales for a receipt REPRINT.
 *
 * Three levels, back-navigated: list → Z-report → transactions. The middle + inner levels reuse the
 * existing report/receipt surfaces (no bespoke money rendering here). Owner/manager only — the entry
 * point hides it for others; this component re-checks (defense in depth). The gateway is the real
 * boundary (the read is POS_ROLES, like the summary), so the check is a UI affordance.
 *
 * Money rule (rule 8): server minor units through formatMoney only. Strings rule (rule 9): i18n keys
 * only; the transaction COUNT is locale-formatted, never money.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CalendarClock, ReceiptText, TriangleAlert, X } from 'lucide-react'
import { useBackDismiss } from '@/components/mobile/useBackDismiss'
import { ListSkeleton } from '@/components/ui/Skeleton'
import { formatMoney } from '@/lib/money'
import { useAuth, hasAnyRole, effectiveRoles } from '@/lib/authContext'
import type { CompanySession } from '@/lib/session'
import { DailySummary } from './DailySummary'
import { ClosedDayTransactionsSheet } from './components/ClosedDayTransactionsSheet'
import { CloseCorrectionSheet } from './components/CloseCorrectionSheet'
import { useClosedSessions, type ClosedSessionSummary } from './registerApi'

export function ClosingHistorySheet({
  session,
  locale,
  onClose,
  onOpenStocktake,
}: {
  session: CompanySession
  locale: string
  onClose: () => void
  /** Hands off to the outlet's stock opname (Pos's StocktakeSheet) for a stock-count correction. */
  onOpenStocktake: () => void
}) {
  const { t } = useTranslation()
  // Covers both render branches below (denied / normal) — one mounted instance, one onClose target.
  useBackDismiss(onClose)
  const auth = useAuth()
  // Owner/manager only. Merged roles so an ELEVATED device terminal lights it up (ADR 0049 P3b); the
  // gateway is the real boundary regardless.
  const canView = hasAnyRole(effectiveRoles(auth.roles, auth.elevatedRoles), 'owner', 'manager')

  const history = useClosedSessions(session, canView)
  // Level 2 (the Z-report), level 3 (that day's transactions), and the correction flow — each keyed
  // off a picked closed day.
  const [detail, setDetail] = useState<ClosedSessionSummary | null>(null)
  const [txns, setTxns] = useState<ClosedSessionSummary | null>(null)
  const [correct, setCorrect] = useState<ClosedSessionSummary | null>(null)

  const rows = history.data ?? []
  // businessDate is a plain YYYY-MM-DD calendar date: new Date() parses it as UTC midnight, so it
  // MUST be formatted in UTC too (the lib/period.ts convention) — a device in a UTC-negative zone
  // would otherwise label every closed day one day early (flaw-audit W1).
  const dateFmt = new Intl.DateTimeFormat(locale, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    timeZone: 'UTC',
  })
  const timeFmt = new Intl.DateTimeFormat(locale, { timeStyle: 'short' })

  if (!canView) {
    return (
      <div
        className="fixed inset-0 z-[70] grid place-items-center bg-black/60 p-6 backdrop-blur-sm"
        role="dialog"
        aria-modal="true"
        aria-label={t('pos.closingHistory.title')}
      >
        <div className="w-full max-w-sm rounded-card border border-line bg-surface px-6 py-6 text-center">
          <p className="text-sm font-semibold text-ink">{t('pos.closingHistory.title')}</p>
          <p className="mt-1.5 text-[13px] text-ink-3">{t('pos.closingHistory.denied')}</p>
          <button
            type="button"
            onClick={onClose}
            className="mx-auto mt-4 block rounded-xl border border-line px-5 py-2 text-sm font-semibold text-ink hover:bg-hover"
          >
            {t('common.close')}
          </button>
        </div>
      </div>
    )
  }

  return (
    <div
      className="fixed inset-0 z-[60] flex flex-col bg-paper"
      role="dialog"
      aria-modal="true"
      aria-label={t('pos.closingHistory.title')}
    >
      <header className="flex h-14 shrink-0 items-center gap-2.5 border-b border-line bg-surface px-4">
        <CalendarClock className="size-[18px] text-emerald-2" aria-hidden />
        <span className="min-w-0 flex-1 truncate text-[16px] font-bold text-ink">
          {t('pos.closingHistory.title')}
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

      <div className="min-h-0 flex-1 overflow-y-auto px-3 py-3">
        {history.isLoading ? (
          <ListSkeleton rows={6} />
        ) : history.isError ? (
          <div className="mx-auto mt-10 max-w-sm rounded-2xl border border-loss/30 bg-tint-loss px-6 py-6 text-center text-sm text-loss">
            <TriangleAlert className="mx-auto mb-2 size-5" aria-hidden />
            {t('pos.closingHistory.error')}
          </div>
        ) : rows.length === 0 ? (
          <div className="mx-auto mt-14 max-w-sm text-center">
            <ReceiptText className="mx-auto mb-3 size-8 text-ink-3/50" aria-hidden />
            <p className="text-sm font-semibold text-ink">{t('pos.closingHistory.emptyTitle')}</p>
            <p className="mt-1 text-[13px] text-ink-3">{t('pos.closingHistory.emptyHint')}</p>
          </div>
        ) : (
          <div className="mx-auto flex max-w-[640px] flex-col gap-1.5">
            {rows.map((row) => (
              <div
                key={row.sessionId}
                className="flex items-stretch gap-2 rounded-2xl border border-line bg-surface transition-colors hover:border-emerald-line"
              >
                <button
                  type="button"
                  onClick={() => setDetail(row)}
                  className="flex min-w-0 flex-1 items-center gap-3 rounded-l-2xl px-3.5 py-3 text-left hover:bg-emerald-tint/40"
                >
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-[13.5px] font-semibold text-ink">
                      {dateFmt.format(new Date(row.businessDate))}
                    </span>
                    <span className="mt-0.5 block text-[11.5px] text-ink-3">
                      {timeFmt.format(new Date(row.openedAt))}
                      {row.closedAt ? `–${timeFmt.format(new Date(row.closedAt))}` : ''}
                    </span>
                  </span>
                  <span className="shrink-0 text-right">
                    <span className="tnum block font-mono text-[14px] font-bold text-ink">
                      {formatMoney(row.netSalesMinor, row.currency, locale)}
                    </span>
                    <span className="mt-0.5 block text-[11px] text-ink-3">
                      {t('pos.closingHistory.txnCount', {
                        formatted: new Intl.NumberFormat(locale).format(row.transactionCount),
                      })}
                    </span>
                  </span>
                </button>
                {/* Manager/owner correction affordance (the whole sheet is already owner/manager). */}
                <button
                  type="button"
                  onClick={() => setCorrect(row)}
                  className="shrink-0 rounded-r-2xl border-l border-line px-3 text-[12px] font-semibold text-ink-2 hover:bg-hover hover:text-ink"
                >
                  {t('pos.closingHistory.correctAction')}
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Level 2 — the day's Z-report (reused). "Lihat transaksi" opens level 3. */}
      {detail ? (
        <DailySummary
          session={session}
          locale={locale}
          sessionId={detail.sessionId}
          onClose={() => setDetail(null)}
          secondaryAction={
            detail.closedAt
              ? {
                  label: t('pos.closingHistory.viewTransactions'),
                  tone: 'neutral',
                  onClick: () => setTxns(detail),
                }
              : undefined
          }
        />
      ) : null}

      {/* Level 3 — that day's individual transactions (read-only reprint). */}
      {txns && txns.closedAt ? (
        <ClosedDayTransactionsSheet
          session={session}
          locale={locale}
          from={txns.openedAt}
          to={txns.closedAt}
          title={dateFmt.format(new Date(txns.businessDate))}
          onClose={() => setTxns(null)}
        />
      ) : null}

      {/* Correction (ADR 0064) — cash re-count (reverse + re-post) or hand off to the stock opname. */}
      {correct ? (
        <CloseCorrectionSheet
          session={session}
          locale={locale}
          currency={correct.currency}
          sessionId={correct.sessionId}
          onClose={() => setCorrect(null)}
          onCorrected={() => setCorrect(null)}
          onOpenStocktake={() => {
            setCorrect(null)
            onOpenStocktake()
          }}
        />
      ) : null}
    </div>
  )
}
