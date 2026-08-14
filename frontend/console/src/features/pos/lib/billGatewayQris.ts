/**
 * billGatewayQris.ts — pure scope guard for the bill (tab) payment modal's dynamic-QRIS GATEWAY
 * flow (ADR 0045 extension to bills). Kept fetch-free (the `effectiveMode.ts`/`channelPicker.ts`
 * idiom) so the "when does BillPaymentModal show the two-step gateway panel instead of the
 * existing one-step Pay" decision is unit-testable without mounting the modal.
 *
 * Scope, deliberately narrower than the ORDER gateway flow (PaymentModal.tsx's
 * `RestaurantDigitalAttempt`): a split check (`isSplitCheck` — the cashier paying only a subset of
 * a bill's lines) always keeps today's one-step `usePayBill` flow, even when the tender is QRIS and
 * the outlet resolves to GATEWAY — the pending-payment/reservation leg is full-bill only this pass.
 */
import type { PosTender } from '@/features/pos-shell/payment/TenderPickerRow'
import type { QrisMode } from '@/features/payments/api'

export function shouldUseBillGatewayFlow(
  tenderType: PosTender,
  qrisMode: QrisMode,
  isSplitCheck: boolean,
): boolean {
  return tenderType === 'QRIS' && qrisMode === 'GATEWAY' && !isSplitCheck
}
