/**
 * ReturnSaleDialog — the manager-gated "Return sale" confirm + refund flow (ADR 0061).
 *
 * Self-contained: owns the confirm step, the refund mutation (full amount of the sale's single
 * payment — a walk-in POS sale has exactly one tender), the honest error surface for the backend's
 * known 400s (gift-card-settled / already-refunded sales cannot be fully refunded), and a success
 * acknowledgement. The CALLER decides eligibility (owner/manager ∧ CAPTURED ∧ not provisional) and
 * mounts this only then; the gateway (SALE_REVERSAL_ROLES) is the real authorization boundary.
 *
 * Money rule (rule 8): the refund amount is the server-issued `payment.amountMinor` in minor units,
 * rendered via formatMoney. Strings rule (rule 9): i18n keys only.
 */
import { useTranslation } from 'react-i18next'
import { AlertTriangle, CheckCircle2 } from 'lucide-react'
import { Card } from '@/components/ui/Card'
import { Button } from '@/components/ui/Button'
import { Spinner } from '@/components/ui/Spinner'
import { formatMoney } from '@/lib/money'
import type { CompanySession } from '@/lib/session'
import { useRefundPayment, type OrderResponse, type PaymentResponse } from '../api'
import { refundErrorKey } from '../lib/returnSale'

export function ReturnSaleDialog({
  session,
  order,
  payment,
  locale,
  onClose,
  onReturned,
}: {
  session: CompanySession
  order: OrderResponse
  payment: PaymentResponse
  locale: string
  onClose: () => void
  /** Called after the operator dismisses the success acknowledgement (parent closes/refetches). */
  onReturned: () => void
}) {
  const { t } = useTranslation()
  const refund = useRefundPayment(session)
  const amountLabel = formatMoney(payment.amountMinor, payment.currency, locale)
  const reference = order.orderId.slice(-8).toUpperCase()

  function confirm() {
    refund.mutate({
      paymentId: payment.paymentId,
      amountMinor: payment.amountMinor,
      currency: payment.currency,
    })
  }

  const errorKey = refund.isError ? refundErrorKey(refund.error) : null

  return (
    <div
      className="fixed inset-0 z-[70] grid place-items-center bg-black/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={t('pos.return.confirmTitle')}
    >
      <Card className="w-full max-w-sm overflow-hidden">
        {refund.isSuccess ? (
          // ---- Success acknowledgement ----
          <>
            <div className="flex items-start gap-3 border-b border-line px-5 py-4">
              <CheckCircle2 className="mt-0.5 size-5 shrink-0 text-profit" aria-hidden="true" />
              <div>
                <h3 className="font-display text-lg font-semibold text-ink">
                  {t('pos.return.successTitle')}
                </h3>
                <p className="mt-1 text-sm text-ink-3">
                  {t('pos.return.successBody', { amount: amountLabel })}
                </p>
              </div>
            </div>
            <div className="px-5 py-4">
              <Button className="w-full" onClick={onReturned}>
                {t('common.done')}
              </Button>
            </div>
          </>
        ) : (
          // ---- Confirm step ----
          <>
            <div className="flex items-start gap-3 border-b border-line px-5 py-4">
              <AlertTriangle className="mt-0.5 size-5 shrink-0 text-loss" aria-hidden="true" />
              <div>
                <h3 className="font-display text-lg font-semibold text-ink">
                  {t('pos.return.confirmTitle')}
                </h3>
                <p className="mt-1 text-sm text-ink-3">
                  {t('pos.return.confirmBody', { amount: amountLabel, reference })}
                </p>
              </div>
            </div>
            {errorKey ? (
              <p className="px-5 pt-3 text-xs text-loss" role="alert">
                {t(errorKey)}
              </p>
            ) : null}
            <div className="flex gap-2 px-5 py-4">
              <Button
                variant="outline"
                className="flex-1"
                onClick={onClose}
                disabled={refund.isPending}
              >
                {t('common.cancel')}
              </Button>
              <Button
                className="flex-1 bg-loss hover:bg-loss/90 focus-visible:outline-loss"
                onClick={confirm}
                disabled={refund.isPending}
              >
                {refund.isPending ? <Spinner /> : t('pos.return.confirmAction')}
              </Button>
            </div>
          </>
        )}
      </Card>
    </div>
  )
}
