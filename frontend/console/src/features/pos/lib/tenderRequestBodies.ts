/**
 * tenderRequestBodies.ts — pure builders for the three tender-carrying write bodies
 * (POST /api/v1/orders, /orders/{id}/pay, /bills/{id}/pay), extracted so the REQUEST SHAPE is
 * unit-testable in isolation (this repo's vitest runs `environment: 'node'` — no DOM, no
 * renderHook — so the contract is locked here, not at the hook layer).
 *
 * CONTRACT (ADR 0036 Phase B2): the server reads `channelCode` from the TOP LEVEL of
 * CheckoutRequest / PayParkedRequest / PayBillRequest — NOT from inside the payment block
 * (the server-side PaymentRequest has no channelCode field; a nested one is silently ignored).
 * The UI carries the picked code on `payment.channelCode`, so every builder MIRRORS it to the
 * top level. Forgetting that mirror is exactly the born-broken bug (2026-08-03 → v0.1.29):
 * every ONLINE-tender checkout 400'd with "channelCode is required when tenderType is ONLINE"
 * and prod never recorded a single ONLINE sale.
 */
import type { CheckoutInput, PayParkedInput } from '../api'
import type { PayBillInput } from '../billsApi'

/** Body for POST /api/v1/orders (one-shot checkout) — mirrors backend CheckoutRequest. */
export function checkoutRequestBody(businessId: string, input: CheckoutInput) {
  return {
    businessId,
    idempotencyKey: input.idempotencyKey,
    lines: input.lines,
    payment: input.payment ?? null,
    discountMinor: input.discountMinor && input.discountMinor > 0 ? input.discountMinor : null,
    orderType: input.orderType ?? null,
    tableId: input.tableId ?? null,
    couponCode: input.couponCode || null,
    loyaltyMemberId: input.loyaltyMemberId || null,
    loyaltyRedeemPoints:
      input.loyaltyRedeemPoints && input.loyaltyRedeemPoints > 0 ? input.loyaltyRedeemPoints : null,
    giftCardId: input.giftCardId || null,
    giftCardRedeemMinor:
      input.giftCardRedeemMinor && input.giftCardRedeemMinor > 0 ? input.giftCardRedeemMinor : null,
    channelCode: input.payment?.channelCode ?? null,
  }
}

/** Body for POST /api/v1/orders/{id}/pay (pay a parked order) — mirrors backend PayParkedRequest. */
export function payParkedRequestBody(input: PayParkedInput) {
  return {
    payment: input.payment ?? null,
    couponCode: input.couponCode || null,
    loyaltyMemberId: input.loyaltyMemberId || null,
    loyaltyRedeemPoints:
      input.loyaltyRedeemPoints && input.loyaltyRedeemPoints > 0 ? input.loyaltyRedeemPoints : null,
    giftCardId: input.giftCardId || null,
    giftCardRedeemMinor:
      input.giftCardRedeemMinor && input.giftCardRedeemMinor > 0 ? input.giftCardRedeemMinor : null,
    channelCode: input.payment?.channelCode ?? null,
  }
}

/** Body for POST /api/v1/bills/{id}/pay (settle a guest check) — mirrors backend PayBillRequest. */
export function payBillRequestBody(input: PayBillInput) {
  return {
    payment: input.payment ?? null,
    discountMinor: input.discountMinor != null && input.discountMinor > 0 ? input.discountMinor : null,
    lineIds: input.lineIds && input.lineIds.length > 0 ? input.lineIds : null,
    idempotencyKey: input.idempotencyKey ?? null,
    channelCode: input.payment?.channelCode ?? null,
  }
}
