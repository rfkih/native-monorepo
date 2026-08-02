/**
 * BillPaymentModal — payment step for a guest bill (tab), incl. split checks.
 *
 * Redesign P3: now the bill ADAPTER over the shared payment surface in
 * features/pos-shell/payment. Wired to POST /api/v1/bills/{id}/pay instead of the order
 * checkout flow. Bill-mode deltas the adapter owns (all pre-existing behavior):
 *   - The idempotency key is minted by BillDetail per pay-initiation and passed as a PROP
 *     (freshIdempotencyKey — see usePaymentAttempt's doc for why bills are the exception).
 *   - Digital tenders are ONE-step (pay directly; no PENDING/capture leg) with a ghost cancel.
 *   - No gift-card field, no coupon/loyalty detail (ADR 0026/0027 scope), 'simple' breakdown.
 *   - Stacks over the bill sheet at z-[60].
 *
 * Money rule (rule 8): all amounts are integer minor units.
 * Strings rule (rule 9): all user-facing text is in i18n keys.
 */
import { useState } from 'react'
import type { CompanySession } from '@/lib/session'
import { PaymentSurfaceFrame } from '@/features/pos-shell/payment/PaymentSurfaceFrame'
import { PaymentBreakdown } from '@/features/pos-shell/payment/PaymentBreakdown'
import { TenderPickerRow, type PosTender } from '@/features/pos-shell/payment/TenderPickerRow'
import { CashPanelView } from '@/features/pos-shell/payment/CashPanelView'
import { DigitalInitiateView } from '@/features/pos-shell/payment/DigitalPanelViews'
import { CheckoutErrorText } from '@/features/pos-shell/payment/CheckoutErrorText'
import { usePayBill, type BillResponse } from './billsApi'

interface Props {
  session: CompanySession
  bill: BillResponse
  locale: string
  /** When set, this modal pays only the selected lines (split check). */
  lineIds?: string[]
  /** Pre-computed total for the selected lines in minor units (used for split checks). */
  checkTotalMinor?: number
  /** Fresh idempotency key per check-pay attempt — required for split checks. */
  idempotencyKey?: string
  onSuccess: () => void
  onClose: () => void
}

export function BillPaymentModal({
  session,
  bill,
  locale,
  lineIds,
  checkTotalMinor,
  idempotencyKey,
  onSuccess,
  onClose,
}: Props) {
  const [tender, setTender] = useState<PosTender>('CASH')
  const payBill = usePayBill(session)

  const currency = bill.currency
  // For a split check use the caller-supplied subtotal; otherwise fall back to the full breakdown.
  const grandTotalMinor =
    checkTotalMinor ??
    bill.breakdown?.grandTotalMinor ??
    bill.lines.reduce((s, l) => s + l.lineTotalMinor, 0)
  // Only show the full breakdown when this is NOT a split check (partial checks have no server breakdown).
  const breakdown = lineIds && lineIds.length > 0 ? null : bill.breakdown

  const errorSlot = payBill.isError ? <CheckoutErrorText error={payBill.error} /> : null

  function pay(payment: { tenderType: PosTender; tenderedMinor?: number }) {
    payBill.mutate(
      { billId: bill.id, payment, lineIds, idempotencyKey },
      { onSuccess: () => onSuccess() },
    )
  }

  return (
    <PaymentSurfaceFrame zIndexClass="z-[60]" subtitle={bill.guestLabel} onClose={onClose}>
      <PaymentBreakdown
        breakdown={breakdown}
        grandTotalMinor={grandTotalMinor}
        currency={currency}
        locale={locale}
        variant="simple"
      />

      <TenderPickerRow value={tender} onChange={setTender} />

      {tender === 'CASH' ? (
        <CashPanelView
          chargeMinor={grandTotalMinor}
          currency={currency}
          locale={locale}
          initialTenderedMinor={grandTotalMinor}
          busy={payBill.isPending}
          errorSlot={errorSlot}
          onPay={(tenderedMinor) => pay({ tenderType: 'CASH', tenderedMinor })}
        />
      ) : (
        // Bills settle digital tenders in ONE step (no PENDING/capture leg) with a ghost cancel.
        <DigitalInitiateView
          chargeMinor={grandTotalMinor}
          currency={currency}
          locale={locale}
          busy={payBill.isPending}
          errorSlot={errorSlot}
          onInitiate={() => pay({ tenderType: tender })}
          onCancel={onClose}
        />
      )}
    </PaymentSurfaceFrame>
  )
}
