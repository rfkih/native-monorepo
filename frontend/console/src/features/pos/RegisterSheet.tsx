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
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/cn'
import { formatMoney } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import { parseDiscountInput } from './lib/discountInput'
import {
  useCloseRegisterSession,
  useCurrentRegisterSession,
  useOpenRegisterSession,
  useRegisterExpected,
  type RegisterSessionResponse,
} from './registerApi'

// Per-tender label keys (ADR 0038 daily close v2) — i18n only (rule 9).
const TENDER_LABEL_KEY: Record<string, string> = {
  CASH: 'register.tenderCash',
  CARD: 'register.tenderCard',
  QRIS: 'register.tenderQris',
  ONLINE: 'register.tenderOnline',
}

// Map the register fault `type` slugs (RegisterAdvice) to friendly i18n keys — the day-final 409 is
// a routine end-of-day event, not a raw English diagnostic with an internal id (rule 9). Returns the
// key, or null to fall back to the server's detail message.
const REGISTER_ERROR_KEY: Record<string, string> = {
  'register-session-day-closed': 'register.errorDayClosed',
  'register-session-already-open': 'register.errorAlreadyOpen',
  'register-session-not-open': 'register.errorNotOpen',
  'register-session-idempotency-key-conflict': 'register.errorKeyConflict',
}

function registerErrorKey(err: unknown): string | null {
  if (err instanceof ApiError && typeof err.problem?.type === 'string') {
    for (const slug of Object.keys(REGISTER_ERROR_KEY)) {
      if (err.problem.type.includes(slug)) return REGISTER_ERROR_KEY[slug]
    }
  }
  return null
}

export function RegisterSheet({
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
  const currentQuery = useCurrentRegisterSession(session)
  const openSession = useOpenRegisterSession(session)
  const closeSession = useCloseRegisterSession(session)
  const currentId = currentQuery.data?.id ?? null
  // Live per-tender expected for the OPEN session (ADR 0038) — shown on the close form.
  const expectedQuery = useRegisterExpected(session, currentId, !!currentId)

  const [floatInput, setFloatInput] = useState('')
  const [countedInput, setCountedInput] = useState('')
  // Held after a successful close so the verdict stays visible (the query flips to 204/null).
  const [closed, setClosed] = useState<RegisterSessionResponse | null>(null)

  const current = currentQuery.data ?? null
  const busy = openSession.isPending || closeSession.isPending

  // Friendly copy for a known register fault, else the server's detail message (rule 9).
  const faultMessage = (err: unknown): string => {
    const key = registerErrorKey(err)
    if (key) return t(key as Parameters<typeof t>[0])
    return err instanceof Error ? err.message : ''
  }

  function handleOpen() {
    openSession.mutate(
      { openingFloatMinor: parseDiscountInput(floatInput, currency), currency },
      { onSuccess: () => setFloatInput('') },
    )
  }

  function handleClose() {
    if (!current) return
    closeSession.mutate(
      { sessionId: current.id, countedCashMinor: parseDiscountInput(countedInput, currency) },
      {
        onSuccess: (res) => {
          if (res) setClosed(res)
          setCountedInput('')
        },
      },
    )
  }

  const inputClass =
    'h-12 w-full rounded-xl border border-line bg-surface px-3 text-right font-mono text-lg tnum text-ink placeholder:text-ink-3/50 transition-colors focus:border-emerald focus:outline-none focus:ring-4 focus:ring-emerald/10'

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('register.title')}
    >
      <div className="reveal max-h-full w-full max-w-sm overflow-y-auto overscroll-contain rounded-card border border-line bg-surface shadow-lg">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          <h2 className="flex items-center gap-2 font-display text-lg font-semibold text-ink">
            <Banknote className="size-5 text-emerald-2" aria-hidden="true" />
            {t('register.title')}
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
              <div className="text-[12px] font-semibold uppercase tracking-[.06em]">
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
            <Button className="w-full" onClick={onClose}>
              {t('register.done')}
            </Button>
          </div>
        ) : currentQuery.isLoading ? (
          <div className="grid place-items-center py-14">
            <Spinner />
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
                <div className="mb-2 text-[12px] font-semibold uppercase tracking-[.06em] text-ink-3">
                  {t('register.expectedByTender')}
                </div>
                <dl className="space-y-1.5">
                  {expectedQuery.data.tenders.map((td) => (
                    <div key={td.tenderType} className="flex items-baseline justify-between text-sm">
                      <dt className="text-ink-3">
                        {TENDER_LABEL_KEY[td.tenderType]
                          ? t(TENDER_LABEL_KEY[td.tenderType] as Parameters<typeof t>[0])
                          : td.tenderType}
                      </dt>
                      <dd className="tnum font-mono text-ink-2">
                        {formatMoney(td.expectedMinor, currency, locale)}
                      </dd>
                    </div>
                  ))}
                </dl>
                <p className="mt-2 text-xs leading-relaxed text-ink-3">
                  {t('register.expectedByTenderHint')}
                </p>
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
              disabled={busy || countedInput.trim() === ''}
              onClick={handleClose}
            >
              {busy ? <Spinner /> : t('register.closeAction')}
            </Button>
          </div>
        ) : (
          /* ── No session → open form ─────────────────────────────────────── */
          <div className="space-y-4 px-5 py-5">
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
                value={floatInput}
                onChange={(e) => setFloatInput(e.target.value)}
                placeholder="0"
                className={inputClass}
              />
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
