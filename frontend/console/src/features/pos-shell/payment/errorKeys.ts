/**
 * errorKeys.ts — checkout/pay-time error → i18n-key mapping, extracted from the three payment
 * modals (redesign P1). PaymentModal's resolveSimpleErrorKey and ServicePaymentModal's
 * errorMessageKey were the same logic ± the module-entitlement branch; the union is safe for
 * every caller (the restaurant API never emits module-not-entitled, so the extra branch is
 * unreachable there).
 */
import { ApiError, isOutletNotAssigned } from '@/lib/api'
import { isGiftCardUnusable, isLoyaltyBalanceInsufficient } from '@/features/loyalty/api'
import { isModuleNotEntitled } from '@/features/servicepos/api'

/**
 * Detects the checkout-time coupon rejections (ADR 0026: unlike the quote, checkout/pay-parked
 * REJECT a bad/exhausted coupon rather than reporting couponStatus) — 422 coupon-invalid or 409
 * coupon-exhausted (PromotionAdvice.java). Returns the matching i18n key, reusing the same copy
 * the CouponField's inline error uses, or null for any other error shape.
 */
export function couponCheckoutErrorKey(
  err: unknown,
): 'pos.coupon.invalid' | 'pos.coupon.exhausted' | null {
  if (!(err instanceof ApiError)) return null
  const type = err.problem?.type ?? ''
  if (err.status === 422 && type.includes('coupon-invalid')) return 'pos.coupon.invalid'
  if (err.status === 409 && type.includes('coupon-exhausted')) return 'pos.coupon.exhausted'
  return null
}

/**
 * Detects a 422 insufficient-stock problem+json and returns a structured object with itemName
 * and available so the UI can surface a precise message (interpolated params — which is why it
 * is separate from {@link checkoutErrorKey}). Returns null for any other error shape.
 */
export function parseInsufficientStock(
  err: unknown,
): { itemName: string; available: number } | null {
  if (!(err instanceof ApiError)) return null
  if (err.status !== 422) return null
  const p = err.problem
  if (!p) return null
  if (typeof p.type !== 'string' || !p.type.includes('insufficient-stock')) return null
  const raw = p as Record<string, unknown>
  if (typeof raw.itemName === 'string' && typeof raw.available === 'number') {
    return { itemName: raw.itemName, available: raw.available }
  }
  return null
}

/**
 * Simple (non-parameterised) checkout-time error keys shared by every tender panel — outlet
 * assignment, module entitlement (service verticals, ADR 0024), coupon, and the Phase 4
 * (ADR 0027) loyalty/gift-card redemption faults. Stock is handled separately
 * ({@link parseInsufficientStock}) since it needs interpolated params. Returns null for any
 * other error (callers fall back to a generic message).
 */
export function checkoutErrorKey(err: unknown): string | null {
  if (isOutletNotAssigned(err)) return 'pos.payment.outletNotAssigned'
  if (isModuleNotEntitled(err)) return 'servicePos.errors.moduleNotEntitled'
  const couponKey = couponCheckoutErrorKey(err)
  if (couponKey) return couponKey
  if (isLoyaltyBalanceInsufficient(err)) return 'pos.loyalty.member.insufficientBalance'
  if (isGiftCardUnusable(err)) return 'pos.loyalty.giftCard.unusableAtCheckout'
  return null
}
