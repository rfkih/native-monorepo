/**
 * DigitalPanelViews — the shared digital-tender (QRIS/CARD) panel views (redesign P3),
 * extracted VERBATIM from the three payment modals.
 *
 * The two-step contract (ADR 0006) is adapter-owned: checkout creates a PENDING order/ticket →
 * "Mark as paid" captures. Bills are one-step (pay directly) and render only the initiate view.
 * These views are stateless: the adapter holds the pending state and mutations.
 */
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Spinner } from '@/components/ui/Spinner'
import { formatMoney } from '@/lib/money'

/** Step 1 — invite the cashier to initiate the digital payment (also the bills' one-step Pay). */
export function DigitalInitiateView({
  chargeMinor,
  currency,
  locale,
  busy,
  errorSlot,
  onInitiate,
  onCancel,
  qrSlot,
  hintText,
  badgeText,
}: {
  chargeMinor: number
  currency: string
  locale: string
  busy: boolean
  errorSlot?: React.ReactNode
  onInitiate: () => void
  /** Bills show a ghost cancel under the one-step Pay; the two-step surfaces don't. */
  onCancel?: () => void
  /**
   * ADR 0045: an optional slot rendered above the Pay button — the STATIC QRIS mode's own image
   * (or its load-error fallback), so the customer can scan before the cashier taps Pay. Undefined
   * (every non-STATIC surface) renders nothing — MANUAL stays pixel-identical.
   */
  qrSlot?: React.ReactNode
  /** Overrides the default `pos.payment.pendingHint` copy (e.g. STATIC's scan instruction).
   *  Undefined keeps today's copy. */
  hintText?: string
  /** Overrides the default badge label; `null` HIDES the badge entirely (STATIC has no "provider"
   *  to badge). Undefined keeps today's `pos.payment.providerPendingBadge`. */
  badgeText?: string | null
}) {
  const { t } = useTranslation()
  return (
    <div className="px-5 pb-5">
      <div className="mb-4 rounded-lg border border-amber/30 bg-amber-tint px-4 py-3 text-sm text-amber-2">
        {badgeText !== null ? (
          <Badge tone="amber" className="mb-2">
            {badgeText ?? t('pos.payment.providerPendingBadge')}
          </Badge>
        ) : null}
        <p className="mt-1 leading-relaxed">{hintText ?? t('pos.payment.pendingHint')}</p>
      </div>

      {errorSlot}

      {qrSlot ? <div className="mb-4">{qrSlot}</div> : null}

      <Button className="w-full" data-testid="payment-pay" disabled={busy} onClick={onInitiate}>
        {busy ? <Spinner /> : t('pos.payment.payAmount', { amount: formatMoney(chargeMinor, currency, locale) })}
      </Button>

      {onCancel ? (
        <Button variant="ghost" className="mt-2 w-full text-xs" disabled={busy} onClick={onCancel}>
          {t('pos.payment.cancel')}
        </Button>
      ) : null}
    </div>
  )
}

/** Step 2 — a PENDING order/ticket exists; the cashier confirms the device showed "paid". */
export function DigitalPendingView({
  pendingAmountMinor,
  currency,
  locale,
  busy,
  captureError,
  onConfirm,
  onCancel,
  qrSlot,
  hintText,
  badgeText,
}: {
  pendingAmountMinor: number
  currency: string
  locale: string
  busy: boolean
  captureError: boolean
  onConfirm: () => void
  onCancel: () => void
  /** ADR 0045: an optional slot rendered between the badge block and the amount row — the STATIC
   *  QRIS mode's own image (or its load-error fallback). Undefined renders nothing (MANUAL/CARD
   *  stay pixel-identical). */
  qrSlot?: React.ReactNode
  /** Overrides the default `pos.payment.pendingHint` copy. Undefined keeps today's copy. */
  hintText?: string
  /** Overrides the default badge label; `null` HIDES the badge. Undefined keeps today's
   *  `pos.payment.providerPendingBadge`. */
  badgeText?: string | null
}) {
  const { t } = useTranslation()
  return (
    <div className="px-5 pb-5">
      {/* Prominent pending badge */}
      <div className="mb-4 rounded-lg border border-amber/30 bg-amber-tint px-4 py-3">
        {badgeText !== null ? (
          <div className="flex items-center gap-2">
            <Badge tone="amber">{badgeText ?? t('pos.payment.providerPendingBadge')}</Badge>
          </div>
        ) : null}
        <p className="mt-2 text-sm leading-relaxed text-amber-2">{hintText ?? t('pos.payment.pendingHint')}</p>
      </div>

      {qrSlot ? <div className="mb-4">{qrSlot}</div> : null}

      <div className="mb-4 flex items-baseline justify-between rounded-lg border border-line bg-paper px-4 py-3">
        <span className="text-sm text-ink-3">{t('pos.payment.pending')}</span>
        <span className="tnum font-mono text-lg font-medium text-ink">
          {formatMoney(pendingAmountMinor, currency, locale)}
        </span>
      </div>

      {captureError ? <p className="mb-3 text-xs text-loss">{t('pos.payment.errorCapture')}</p> : null}

      <Button className="w-full" data-testid="payment-mark-paid" disabled={busy} onClick={onConfirm}>
        {busy ? <Spinner /> : t('pos.payment.markAsPaid')}
      </Button>

      <Button variant="ghost" className="mt-2 w-full text-xs" disabled={busy} onClick={onCancel}>
        {t('pos.payment.cancel')}
      </Button>
    </div>
  )
}
