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
}: {
  chargeMinor: number
  currency: string
  locale: string
  busy: boolean
  errorSlot?: React.ReactNode
  onInitiate: () => void
  /** Bills show a ghost cancel under the one-step Pay; the two-step surfaces don't. */
  onCancel?: () => void
}) {
  const { t } = useTranslation()
  return (
    <div className="px-5 pb-5">
      <div className="mb-4 rounded-lg border border-amber/30 bg-amber-tint px-4 py-3 text-sm text-amber-2">
        <Badge tone="amber" className="mb-2">
          {t('pos.payment.providerPendingBadge')}
        </Badge>
        <p className="mt-1 leading-relaxed">{t('pos.payment.pendingHint')}</p>
      </div>

      {errorSlot}

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
}: {
  pendingAmountMinor: number
  currency: string
  locale: string
  busy: boolean
  captureError: boolean
  onConfirm: () => void
  onCancel: () => void
}) {
  const { t } = useTranslation()
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
