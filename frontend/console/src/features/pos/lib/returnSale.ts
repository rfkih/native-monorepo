/**
 * returnSale.ts — pure helpers for the manager-gated "Return sale" refund (ADR 0061).
 *
 * Kept side-effect-free (no React, no network) so the eligibility rule and the error mapping are
 * unit-testable in isolation — the refund itself is the existing `useRefundPayment` /
 * `POST /api/v1/payments/{id}/refund` path, gated to owner/manager at the gateway.
 */
import { ApiError } from '@/lib/api'
import type { PaymentResponse } from '../api'

/**
 * Whether a payment can be RETURNED (full refund). Only a settled `CAPTURED` tender is refundable —
 * a `PENDING` capture has taken no money yet, and `VOIDED` / `REFUNDED` / `PARTIALLY_REFUNDED` were
 * already reversed. This is a display gate only; the gateway (role) and restaurant-service (state
 * machine) are the real boundaries, so a stale `true` here can never widen anything — it only risks
 * showing a button the server then rejects.
 */
export function canReturnPayment(payment: Pick<PaymentResponse, 'status'>): boolean {
  return payment.status === 'CAPTURED'
}

/**
 * Maps a failed refund to a user-facing i18n key. The backend rejects a full refund it cannot post
 * with a 400 (partial-only shapes: a gift-card-settled sale, or a sale already partly refunded — see
 * ADR 0061 limitations); a 403 means the caller is not owner/manager (or a device terminal has not
 * elevated). Everything else is a generic failure.
 */
export function refundErrorKey(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 403) return 'pos.return.errorForbidden'
    if (err.status === 400 || err.status === 409 || err.status === 422) {
      return 'pos.return.errorRejected'
    }
  }
  return 'pos.return.errorGeneric'
}
