/**
 * CustomerDisplay — Phase 6 (ADR 0029) full-screen, customer-facing view. Driven ENTIRELY by the
 * `pos-display:{outletId}` BroadcastChannel (displayChannel.ts) — this screen makes no API calls of
 * its own; every figure it shows was already fetched/priced by the POS terminal that publishes to
 * the channel (Pos.tsx / PaymentModal.tsx via displayPublisher.ts).
 *
 * Opened as a second browser tab/window (e.g. a second monitor) via the "Customer display" button
 * in Pos.tsx, which passes the outlet id explicitly as `?outlet=` — this screen trusts that param
 * rather than re-resolving the active outlet itself (avoids a mismatch if this tab's own
 * sessionStorage-remembered outlet ever differs from the terminal that opened it). A bookmarked or
 * manually-opened tab with no `?outlet=` shows a friendly explainer instead of guessing.
 *
 * Locale: independent of the cashier's own console language — a per-outlet display preference
 * (default 'id', persisted in localStorage), toggled from a small corner control. Every `t()` call
 * below passes `{ lng: displayLocale }` so the SAME shared en/id resources are reused WITHOUT
 * touching the global i18n instance (which would also flip the cashier's own tab, since both tabs
 * share the same localStorage-persisted language key).
 *
 * Money rule (rule 8): every amount is formatted via formatMoney() from the currency carried on the
 * message itself — never a value the publisher pre-formatted (that would bake in the wrong locale).
 */
import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { Languages, Store, Wallet, CheckCircle2 } from 'lucide-react'
import { useSession } from '@/lib/session'
import { localeOf } from '@/i18n'
import { formatMoney } from '@/lib/money'
import { channelName, isDisplayMessage } from './displayChannel'
import type { DisplayBreakdown, DisplayLine, DisplayMessage, DisplayMoney } from './displayChannel'

type DisplayLocale = 'id' | 'en'

const LOCALE_KEY_PREFIX = 'native.console.posDisplay.locale.'
/** How long a "payment completed" screen holds before reverting — long enough for a customer to
 * actually read their change, short enough not to block the next order's cart from showing. */
const HOLD_MS = 7_000

function loadDisplayLocale(outletId: string): DisplayLocale {
  try {
    const saved = localStorage.getItem(LOCALE_KEY_PREFIX + outletId)
    if (saved === 'en' || saved === 'id') return saved
  } catch {
    /* ignore — falls back to the default below */
  }
  return 'id'
}

function saveDisplayLocale(outletId: string, locale: DisplayLocale): void {
  try {
    localStorage.setItem(LOCALE_KEY_PREFIX + outletId, locale)
  } catch {
    /* localStorage unavailable — the toggle still works for this tab's lifetime */
  }
}

export function CustomerDisplay() {
  const { company } = useSession()
  const [params] = useSearchParams()
  const outletId = params.get('outlet')
  const { t } = useTranslation()

  if (!company) {
    return <CenteredNote text={t('posDisplay.noCompany')} />
  }
  if (!outletId) {
    return <CenteredNote text={t('posDisplay.missingOutlet')} />
  }
  return <CustomerDisplayInner businessName={company.name} outletId={outletId} />
}

function CenteredNote({ text }: { text: string }) {
  return (
    <div className="grid min-h-[100dvh] place-items-center bg-slate-950 px-6 text-center">
      <p className="max-w-sm text-lg font-medium text-white/80">{text}</p>
    </div>
  )
}

function CustomerDisplayInner({ businessName, outletId }: { businessName: string; outletId: string }) {
  const { t } = useTranslation()
  const [displayLocale, setDisplayLocale] = useState<DisplayLocale>(() => loadDisplayLocale(outletId))
  const [message, setMessage] = useState<DisplayMessage>({ type: 'IDLE' })

  // "Payment completed" hold: while `holdUntilRef` is in the future, incoming messages are stashed
  // in `pendingRef` instead of applied immediately — see the class doc above.
  const holdUntilRef = useRef(0)
  const pendingRef = useRef<DisplayMessage | null>(null)
  const revertTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    if (typeof BroadcastChannel === 'undefined') return
    const channel = new BroadcastChannel(channelName(outletId))

    function apply(msg: DisplayMessage) {
      setMessage(msg)
      if (revertTimerRef.current) {
        clearTimeout(revertTimerRef.current)
        revertTimerRef.current = null
      }
      if (msg.type === 'PAYMENT_COMPLETED') {
        holdUntilRef.current = Date.now() + HOLD_MS
        revertTimerRef.current = setTimeout(() => {
          holdUntilRef.current = 0
          const next = pendingRef.current ?? { type: 'IDLE' as const }
          pendingRef.current = null
          setMessage(next)
        }, HOLD_MS)
      }
    }

    channel.onmessage = (ev: MessageEvent) => {
      if (!isDisplayMessage(ev.data)) return
      if (Date.now() < holdUntilRef.current) {
        pendingRef.current = ev.data
        return
      }
      apply(ev.data)
    }

    return () => {
      if (revertTimerRef.current) clearTimeout(revertTimerRef.current)
      channel.close()
    }
  }, [outletId])

  function toggleLocale() {
    const next: DisplayLocale = displayLocale === 'id' ? 'en' : 'id'
    setDisplayLocale(next)
    saveDisplayLocale(outletId, next)
  }

  return (
    <div className="relative flex min-h-[100dvh] flex-col overflow-hidden bg-slate-950 font-sans text-white">
      {/* Locale toggle — small corner control, always reachable regardless of screen state */}
      <button
        type="button"
        onClick={toggleLocale}
        aria-label={t('posDisplay.localeToggleAria')}
        className="absolute right-4 top-4 z-10 flex items-center gap-1.5 rounded-full border border-white/15 bg-white/5 px-3 py-1.5 text-xs font-semibold text-white/70 backdrop-blur transition-colors hover:bg-white/10 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white"
      >
        <Languages className="size-3.5" aria-hidden="true" />
        {displayLocale.toUpperCase()}
      </button>

      {message.type === 'PAYMENT_STARTED' ? (
        <PaymentStartedScreen due={message.due} displayLocale={displayLocale} />
      ) : message.type === 'PAYMENT_COMPLETED' ? (
        <PaymentCompletedScreen change={message.change} displayLocale={displayLocale} />
      ) : message.type === 'CART_UPDATED' && message.breakdown ? (
        <CartScreen lines={message.lines} breakdown={message.breakdown} displayLocale={displayLocale} />
      ) : (
        <IdleScreen businessName={businessName} displayLocale={displayLocale} />
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Screens
// ---------------------------------------------------------------------------

function IdleScreen({ businessName, displayLocale }: { businessName: string; displayLocale: DisplayLocale }) {
  const { t } = useTranslation()
  return (
    <div className="grid flex-1 place-items-center px-8 text-center">
      <div className="reveal flex flex-col items-center gap-6">
        <span className="floaty grid size-20 place-items-center rounded-3xl bg-white/10">
          <Store className="size-10 text-white" aria-hidden="true" />
        </span>
        <div>
          <p className="font-display text-4xl font-bold tracking-tight sm:text-5xl">{businessName}</p>
          <p className="mt-4 text-lg text-white/60">{t('posDisplay.idle.welcome', { lng: displayLocale })}</p>
        </div>
      </div>
    </div>
  )
}

function CartScreen({
  lines,
  breakdown,
  displayLocale,
}: {
  lines: DisplayLine[]
  breakdown: DisplayBreakdown
  displayLocale: DisplayLocale
}) {
  const { t } = useTranslation()
  const locale = localeOf(displayLocale)
  return (
    <div className="reveal flex flex-1 flex-col">
      <div className="flex-1 overflow-y-auto px-6 py-8 sm:px-16">
        <p className="mb-6 text-sm font-semibold uppercase tracking-[0.15em] text-white/50">
          {t('posDisplay.cart.yourOrder', { lng: displayLocale })}
        </p>
        {lines.length > 0 ? (
          <ul className="space-y-4">
            {lines.map((line, i) => (
              <li
                key={i}
                className="flex items-baseline justify-between gap-4 border-b border-white/10 pb-3"
              >
                <span className="flex items-baseline gap-3 text-xl font-medium sm:text-2xl">
                  <span className="tnum font-mono text-white/50">{line.qty}×</span>
                  {line.name}
                </span>
                <span className="tnum shrink-0 font-mono text-xl font-semibold sm:text-2xl">
                  {formatMoney(line.lineTotalMinor, breakdown.currency, locale)}
                </span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-lg text-white/60">{t('posDisplay.cart.preparing', { lng: displayLocale })}</p>
        )}
      </div>
      <div className="border-t border-white/10 bg-white/5 px-6 py-6 sm:px-16">
        <div className="flex items-baseline justify-between">
          <span className="text-lg font-semibold text-white/70">
            {t('posDisplay.cart.total', { lng: displayLocale })}
          </span>
          <span className="tnum font-mono text-4xl font-bold sm:text-5xl">
            {formatMoney(breakdown.grandTotalMinor, breakdown.currency, locale)}
          </span>
        </div>
      </div>
    </div>
  )
}

function PaymentStartedScreen({ due, displayLocale }: { due: DisplayMoney; displayLocale: DisplayLocale }) {
  const { t } = useTranslation()
  const locale = localeOf(displayLocale)
  return (
    <div className="grid flex-1 place-items-center px-8 text-center">
      <div className="reveal flex flex-col items-center gap-5">
        <span className="grid size-20 place-items-center rounded-3xl bg-brand-500/20">
          <Wallet className="size-10 text-brand-300" aria-hidden="true" />
        </span>
        <p className="text-lg font-semibold text-white/70">
          {t('posDisplay.payment.dueLabel', { lng: displayLocale })}
        </p>
        <p className="tnum font-mono text-5xl font-bold sm:text-6xl">
          {formatMoney(due.amountMinor, due.currency, locale)}
        </p>
        <p className="max-w-sm text-white/50">{t('posDisplay.payment.hint', { lng: displayLocale })}</p>
      </div>
    </div>
  )
}

function PaymentCompletedScreen({
  change,
  displayLocale,
}: {
  change: DisplayMoney
  displayLocale: DisplayLocale
}) {
  const { t } = useTranslation()
  const locale = localeOf(displayLocale)
  return (
    <div className="grid flex-1 place-items-center px-8 text-center">
      <div className="reveal flex flex-col items-center gap-5">
        <span className="grid size-20 place-items-center rounded-3xl bg-profit/20">
          <CheckCircle2 className="size-10 text-profit" aria-hidden="true" />
        </span>
        <p className="font-display text-3xl font-bold sm:text-4xl">
          {t('posDisplay.payment.thankYou', { lng: displayLocale })}
        </p>
        {change.amountMinor > 0 ? (
          <div>
            <p className="text-lg font-semibold text-white/70">
              {t('posDisplay.payment.changeLabel', { lng: displayLocale })}
            </p>
            <p className="tnum mt-1 font-mono text-4xl font-bold sm:text-5xl">
              {formatMoney(change.amountMinor, change.currency, locale)}
            </p>
          </div>
        ) : null}
      </div>
    </div>
  )
}
