package id.co.nativeapp.carwash.promotion.domain;

/**
 * The outcome of resolving a coupon code during {@link
 * id.co.nativeapp.carwash.promotion.service.PromotionEngineService#evaluate} — carried on {@link
 * id.co.nativeapp.carwash.promotion.dto.CouponOutcome} and surfaced on the wire as {@code
 * PriceBreakdownResponse.couponStatus}. Ported verbatim from restaurant-service.
 */
public enum CouponStatus {
  /** The coupon resolved, its rule is currently effective, and its deduction was applied. */
  APPLIED,

  /**
   * The code does not resolve to a coupon this tenant owns, the coupon (or its linked rule) is
   * inactive/expired, or the linked rule is not currently effective (outside its effective window or
   * happy-hour window).
   */
  INVALID,

  /** The coupon resolved and is otherwise valid, but its redemption count is exhausted. */
  EXHAUSTED
}
