/**
 * PaymentModal — Step 2 of the POS checkout flow (ADR 0006, slice 5).
 *
 * Three tenders via a Segmented picker:
 *   CASH  — numeric keypad + quick-cash chips; live change line; Pay fires checkout-with-payment
 *            (POST /api/v1/orders with the payment block). CAPTURED immediately.
 *   QRIS  — two-step: checkout creates PENDING order, then "Mark as paid" calls
 *            POST /api/v1/payments/{id}/capture. Clearly badged "Demo · pending provider".
 *   CARD  — same two-step as QRIS.
 *
 * Phase 2: discountMinor is threaded into checkout; the authoritative grandTotalMinor from the
 * price breakdown drives the charge amount rather than a client-side subtotal.
 *
 * Money rule (rule 8): all amounts are integer minor units throughout. Displayed via formatMoney().
 * Strings rule (rule 9): every label is an i18n key — no hardcoded user-facing text.
 */
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { Segmented } from '@/components/ui/Segmented'
import { formatMoney } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import type {
  OrderLineInput,
  OrderResponse,
  PaymentResponse,
  PriceBreakdownResponse,
} from './api'
import { useCheckout, useCapturePayment, usePayParked } from './api'

type TenderTab = 'CASH' | 'QRIS' | 'CARD'

interface Props {
  session: CompanySession
  lines: OrderLineInput[]
  /** Server-authoritative grand total from the quote breakdown; drives the charge amount. */
  grandTotalMinor: number
  /** Optional order-level discount in minor units; passed to checkout. */
  discountMinor: number
  /** Live price breakdown from useQuote — rendered in the modal header. */
  breakdown: PriceBreakdownResponse | null
  currency: string
  locale: string
  onSuccess: (order: OrderResponse, payment: PaymentResponse) => void
  onClose: () => void
  /** Phase 4: when set, the modal finalises a PARKED order rather than creating a new checkout. */
  parkedOrderId?: string | null
  /** Phase 4: order type forwarded to checkout. */
  orderType?: string
  /** Phase 4: table UUID forwarded to checkout (DINE_IN only). */
  tableId?: string | null
}

// Quick-cash chip amounts in IDR minor units (= whole rupiah, exponent 0).
// For USD we build chips relative to the total (exact, +$5, +$10).
const IDR_QUICK_CHIPS = [50_000, 100_000] as const

/** Build quick-cash chip options: [exact, ...preset-overs] all as minor units. */
function quickChips(totalMinor: number, currency: string): number[] {
  if (currency === 'IDR') {
    // Exact + preset IDR chips that exceed the total
    return [totalMinor, ...IDR_QUICK_CHIPS.filter((v) => v > totalMinor)]
  }
  // For non-IDR: exact + a couple of round-up multiples of 500 minor units (e.g. USD cents)
  const round500 = Math.ceil(totalMinor / 500) * 500
  const round1000 = Math.ceil(totalMinor / 1000) * 1000
  const chips = [totalMinor]
  if (round500 > totalMinor) chips.push(round500)
  if (round1000 > round500) chips.push(round1000)
  return chips
}

export function PaymentModal({
  session,
  lines,
  grandTotalMinor,
  discountMinor,
  breakdown,
  currency,
  locale,
  onSuccess,
  onClose,
  parkedOrderId,
  orderType,
  tableId,
}: Props) {
  const { t } = useTranslation()
  const [tender, setTender] = useState<TenderTab>('CASH')

  const tenderOptions: { value: TenderTab; label: string }[] = [
    { value: 'CASH', label: t('pos.payment.tenderCash') },
    { value: 'QRIS', label: t('pos.payment.tenderQris') },
    { value: 'CARD', label: t('pos.payment.tenderCard') },
  ]

  return (
    // Overlay — same backdrop pattern as ChargeSuccess in Pos.tsx
    <div
      className="fixed inset-0 z-40 grid place-items-center bg-black/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('pos.payment.title')}
    >
      <Card className="reveal w-full max-w-sm overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          <h2 className="font-display text-lg font-semibold text-ink">{t('pos.payment.title')}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label={t('pos.payment.cancel')}
            className="grid size-8 place-items-center rounded-lg text-ink-3 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
          >
            <X className="size-4" />
          </button>
        </div>

        {/* Inline breakdown */}
        <ModalBreakdown breakdown={breakdown} grandTotalMinor={grandTotalMinor} currency={currency} locale={locale} />

        {/* Tender picker */}
        <div className="flex justify-center px-5 py-4">
          <Segmented
            options={tenderOptions}
            value={tender}
            onChange={setTender}
            ariaLabel={t('pos.payment.selectTender')}
          />
        </div>

        {/* Tender-specific panel */}
        {tender === 'CASH' ? (
          <CashPanel
            session={session}
            lines={lines}
            grandTotalMinor={grandTotalMinor}
            discountMinor={discountMinor}
            currency={currency}
            locale={locale}
            onSuccess={onSuccess}
            onClose={onClose}
            parkedOrderId={parkedOrderId}
            orderType={orderType}
            tableId={tableId}
          />
        ) : (
          <DigitalPanel
            session={session}
            lines={lines}
            grandTotalMinor={grandTotalMinor}
            discountMinor={discountMinor}
            currency={currency}
            locale={locale}
            tenderType={tender}
            onSuccess={onSuccess}
            onClose={onClose}
            parkedOrderId={parkedOrderId}
            orderType={orderType}
            tableId={tableId}
          />
        )}
      </Card>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Inline breakdown in the modal header
// ---------------------------------------------------------------------------

function ModalBreakdown({
  breakdown,
  grandTotalMinor,
  currency,
  locale,
}: {
  breakdown: PriceBreakdownResponse | null
  grandTotalMinor: number
  currency: string
  locale: string
}) {
  const { t } = useTranslation()

  if (!breakdown) {
    // Fallback: just the total row
    return (
      <div className="flex items-baseline justify-between px-5 py-3 text-sm text-ink-3 border-b border-line">
        <span>{t('pos.total')}</span>
        <span className="tnum font-mono text-xl font-medium text-ink">
          {formatMoney(grandTotalMinor, currency, locale)}
        </span>
      </div>
    )
  }

  const illustrative = breakdown.usesIllustrativeRules

  return (
    <div className="border-b border-line px-5 py-3 space-y-1.5 text-sm">
      {/* Subtotal */}
      <div className="flex items-baseline justify-between text-ink-3">
        <span>{t('pos.subtotal')}</span>
        <span className="tnum font-mono">{formatMoney(breakdown.subtotalMinor, currency, locale)}</span>
      </div>

      {/* Discount */}
      {breakdown.discountMinor > 0 ? (
        <div className="flex items-baseline justify-between text-ink-3">
          <span>{t('pos.discount')}</span>
          <span className="tnum font-mono text-loss">
            − {formatMoney(breakdown.discountMinor, currency, locale)}
          </span>
        </div>
      ) : null}

      {/* Service charge */}
      <div className="flex items-center justify-between text-ink-3">
        <span className="flex items-center gap-1.5">
          {t('pos.serviceCharge')}
          {illustrative ? <InlineEstimatedBadge hint={t('pos.illustrativeHint')} /> : null}
        </span>
        <span className="tnum font-mono">{formatMoney(breakdown.serviceChargeMinor, currency, locale)}</span>
      </div>

      {/* Tax */}
      <div className="flex items-center justify-between text-ink-3">
        <span className="flex items-center gap-1.5">
          {t('pos.tax')}
          {illustrative ? <InlineEstimatedBadge hint={t('pos.illustrativeHint')} /> : null}
        </span>
        <span className="tnum font-mono">{formatMoney(breakdown.taxMinor, currency, locale)}</span>
      </div>

      {/* Grand total */}
      <div className="flex items-baseline justify-between border-t border-line pt-1.5 mt-0.5 font-medium">
        <span className="text-ink">{t('pos.total')}</span>
        <span className="tnum font-mono text-xl text-ink">
          {formatMoney(breakdown.grandTotalMinor, currency, locale)}
        </span>
      </div>
    </div>
  )
}

function InlineEstimatedBadge({ hint }: { hint: string }) {
  const { t } = useTranslation()
  return (
    <span title={hint} aria-label={hint}>
      <Badge tone="amber" className="text-[10px] py-0 px-1.5">
        {t('pos.estimated')}
      </Badge>
    </span>
  )
}

// ---------------------------------------------------------------------------
// Cash panel
// ---------------------------------------------------------------------------

interface CashPanelProps {
  session: CompanySession
  lines: OrderLineInput[]
  grandTotalMinor: number
  discountMinor: number
  currency: string
  locale: string
  onSuccess: (order: OrderResponse, payment: PaymentResponse) => void
  onClose: () => void
  parkedOrderId?: string | null
  orderType?: string
  tableId?: string | null
}

function CashPanel({
  session,
  lines,
  grandTotalMinor,
  discountMinor,
  currency,
  locale,
  onSuccess,
  onClose,
  parkedOrderId,
  orderType,
  tableId,
}: CashPanelProps) {
  const { t } = useTranslation()
  const checkout = useCheckout(session)
  const payParked = usePayParked(session)

  const isBusy = checkout.isPending || payParked.isPending

  // tenderedMinor is held as an integer; the keypad appends digits to a string, then parsed.
  const [keyStr, setKeyStr] = useState<string>('')

  const tenderedMinor = keyStr === '' ? 0 : parseInt(keyStr, 10)
  const changeMinor = tenderedMinor - grandTotalMinor
  const canPay = tenderedMinor >= grandTotalMinor && !isBusy

  const chips = quickChips(grandTotalMinor, currency)

  function pressDigit(d: string) {
    if (keyStr === '0') return // prevent leading zero accumulation
    setKeyStr((s) => (s.length >= 12 ? s : s + d))
  }
  function pressBackspace() {
    setKeyStr((s) => s.slice(0, -1))
  }
  function pressClear() {
    setKeyStr('')
  }
  function setChip(minor: number) {
    setKeyStr(String(minor))
  }

  function pay() {
    if (!canPay) return

    if (parkedOrderId) {
      // Resume path: finalise a PARKED order
      payParked.mutate(
        { orderId: parkedOrderId, payment: { tenderType: 'CASH', tenderedMinor } },
        {
          onSuccess: (res) => {
            if (res?.payment) {
              onSuccess(res, res.payment)
            } else {
              onClose()
            }
          },
        },
      )
    } else {
      // Normal checkout path
      checkout.mutate(
        {
          lines,
          payment: { tenderType: 'CASH', tenderedMinor },
          discountMinor,
          orderType,
          tableId,
        },
        {
          onSuccess: (res) => {
            if (res?.payment) {
              onSuccess(res, res.payment)
            } else {
              onClose()
            }
          },
        },
      )
    }
  }

  return (
    <div className="px-5 pb-5">
      {/* Quick-cash chips */}
      <div className="mb-3">
        <p className="mb-1.5 text-xs text-ink-3">{t('pos.payment.quickCash')}</p>
        <div className="flex flex-wrap gap-2">
          {chips.map((chip, i) => (
            <button
              key={chip}
              type="button"
              onClick={() => setChip(chip)}
              className={[
                'rounded-xl border px-3 py-1.5 text-xs font-semibold transition-colors',
                tenderedMinor === chip
                  ? 'border-brand-500 bg-brand-50 text-brand-700'
                  : 'border-line bg-surface text-ink-2 hover:bg-hover',
              ].join(' ')}
            >
              {i === 0 ? t('pos.payment.exactAmount') : formatMoney(chip, currency, locale)}
            </button>
          ))}
        </div>
      </div>

      {/* Tendered display */}
      <div className="mb-3 rounded-lg border border-line bg-paper px-4 py-3 text-right">
        <p className="text-xs text-ink-3">{t('pos.payment.tendered')}</p>
        <p className="tnum font-mono text-2xl font-medium text-ink">
          {tenderedMinor > 0 ? formatMoney(tenderedMinor, currency, locale) : '—'}
        </p>
      </div>

      {/* Numeric keypad (3×4 grid) */}
      <div className="mb-3 grid grid-cols-3 gap-1.5">
        {['7', '8', '9', '4', '5', '6', '1', '2', '3'].map((d) => (
          <KeypadButton key={d} label={d} onClick={() => pressDigit(d)} />
        ))}
        <KeypadButton label="C" onClick={pressClear} />
        <KeypadButton label="0" onClick={() => pressDigit('0')} />
        <KeypadButton label="⌫" onClick={pressBackspace} />
      </div>

      {/* Change line */}
      <div className="mb-4 flex items-baseline justify-between rounded-xl bg-tint-profit px-4 py-2.5">
        <span className="text-sm font-semibold text-brand-700">{t('pos.payment.change')}</span>
        <span className="tnum font-mono text-lg font-bold text-brand-700">
          {changeMinor >= 0 ? formatMoney(changeMinor, currency, locale) : '—'}
        </span>
      </div>

      {(checkout.isError || payParked.isError) ? (
        <p className="mb-3 text-xs text-loss">
          {((checkout.error ?? payParked.error) as Error).message}
        </p>
      ) : null}

      {!canPay && tenderedMinor > 0 && tenderedMinor < grandTotalMinor ? (
        <p className="mb-3 text-xs text-amber-2">{t('pos.payment.insufficientTendered')}</p>
      ) : null}

      <Button className="w-full" disabled={!canPay} onClick={pay}>
        {isBusy ? <Spinner /> : t('pos.payment.payAmount', { amount: formatMoney(grandTotalMinor, currency, locale) })}
      </Button>
    </div>
  )
}

function KeypadButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="tnum flex h-11 items-center justify-center rounded-xl border border-line bg-surface font-mono text-base font-semibold text-ink transition-colors hover:bg-hover active:bg-brand-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500"
    >
      {label}
    </button>
  )
}

// ---------------------------------------------------------------------------
// Digital panel (QRIS / Card) — two-step: checkout → PENDING → capture
// ---------------------------------------------------------------------------

interface DigitalPanelProps {
  session: CompanySession
  lines: OrderLineInput[]
  grandTotalMinor: number
  discountMinor: number
  currency: string
  locale: string
  tenderType: 'QRIS' | 'CARD'
  onSuccess: (order: OrderResponse, payment: PaymentResponse) => void
  onClose: () => void
  parkedOrderId?: string | null
  orderType?: string
  tableId?: string | null
}

function DigitalPanel({
  session,
  lines,
  grandTotalMinor,
  discountMinor,
  currency,
  locale,
  tenderType,
  onSuccess,
  onClose,
  parkedOrderId,
  orderType,
  tableId,
}: DigitalPanelProps) {
  const { t } = useTranslation()
  const checkout = useCheckout(session)
  const payParked = usePayParked(session)
  const capture = useCapturePayment(session)

  // After initiation we hold the PENDING order + payment to drive the "Mark as paid" step.
  const [pendingOrder, setPendingOrder] = useState<OrderResponse | null>(null)
  const [pendingPayment, setPendingPayment] = useState<PaymentResponse | null>(null)

  function initiatePayment() {
    if (parkedOrderId) {
      // Resume path: finalise a PARKED order with a digital tender → AWAITING_PAYMENT
      payParked.mutate(
        { orderId: parkedOrderId, payment: { tenderType } },
        {
          onSuccess: (res) => {
            if (res?.payment) {
              setPendingOrder(res)
              setPendingPayment(res.payment)
            }
          },
        },
      )
    } else {
      checkout.mutate(
        { lines, payment: { tenderType }, discountMinor, orderType, tableId },
        {
          onSuccess: (res) => {
            if (res?.payment) {
              setPendingOrder(res)
              setPendingPayment(res.payment)
            }
          },
        },
      )
    }
  }

  function confirmPayment() {
    if (!pendingPayment) return
    capture.mutate(pendingPayment.paymentId, {
      onSuccess: (captured) => {
        if (captured && pendingOrder) {
          onSuccess(pendingOrder, captured)
        }
      },
    })
  }

  const isInitiating = checkout.isPending || payParked.isPending

  // Phase 1 — invite cashier to initiate the digital payment
  if (!pendingPayment) {
    return (
      <div className="px-5 pb-5">
        <div className="mb-4 rounded-lg border border-amber/30 bg-amber-tint px-4 py-3 text-sm text-amber-2">
          <Badge tone="amber" className="mb-2">
            {t('pos.payment.providerPendingBadge')}
          </Badge>
          <p className="mt-1 leading-relaxed">{t('pos.payment.pendingHint')}</p>
        </div>

        {(checkout.isError || payParked.isError) ? (
          <p className="mb-3 text-xs text-loss">
            {((checkout.error ?? payParked.error) as Error).message}
          </p>
        ) : null}

        <Button className="w-full" disabled={isInitiating} onClick={initiatePayment}>
          {isInitiating ? <Spinner /> : t('pos.payment.payAmount', { amount: formatMoney(grandTotalMinor, currency, locale) })}
        </Button>
      </div>
    )
  }

  // Phase 2 — PENDING order created; cashier confirms the device showed "paid"
  return (
    <div className="px-5 pb-5">
      {/* Prominent pending badge */}
      <div className="mb-4 rounded-lg border border-amber/30 bg-amber-tint px-4 py-3">
        <div className="flex items-center gap-2">
          <Badge tone="amber">{t('pos.payment.providerPendingBadge')}</Badge>
        </div>
        <p className="mt-2 text-sm leading-relaxed text-amber-2">{t('pos.payment.pendingHint')}</p>
      </div>

      <div className="mb-4 flex items-baseline justify-between rounded-lg border border-line bg-paper px-4 py-3">
        <span className="text-sm text-ink-3">{t('pos.payment.pending')}</span>
        <span className="tnum font-mono text-lg font-medium text-ink">
          {formatMoney(pendingPayment.amountMinor, currency, locale)}
        </span>
      </div>

      {capture.isError ? (
        <p className="mb-3 text-xs text-loss">{t('pos.payment.errorCapture')}</p>
      ) : null}

      <Button className="w-full" disabled={capture.isPending} onClick={confirmPayment}>
        {capture.isPending ? <Spinner /> : t('pos.payment.markAsPaid')}
      </Button>

      <Button
        variant="ghost"
        className="mt-2 w-full text-xs"
        disabled={capture.isPending}
        onClick={onClose}
      >
        {t('pos.payment.cancel')}
      </Button>
    </div>
  )
}
