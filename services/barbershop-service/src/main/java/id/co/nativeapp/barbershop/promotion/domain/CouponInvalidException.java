package id.co.nativeapp.barbershop.promotion.domain;

/**
 * Thrown at checkout time (never at quote time — quote reports {@code couponStatus} instead of
 * throwing, by design) when a caller-supplied coupon code does not resolve to a usable coupon.
 * Ported verbatim from restaurant-service via carwash-service (ADR 0026).
 *
 * <p>Maps to {@code 422 Unprocessable Entity} via {@code config.PromotionAdvice}. Covers four
 * resolution failures, distinguished only in the exception message (the wire {@code type} URI is
 * uniform — {@code https://errors.nativeapp.id/coupon-invalid}):
 *
 * <ul>
 *   <li><strong>unknown</strong> — no {@code coupon} row for the normalized code in this tenant;
 *   <li><strong>expired</strong> — {@code expires_at} has passed;
 *   <li><strong>inactive</strong> — the coupon or its linked rule is {@code active = FALSE};
 *   <li><strong>requires-coupon-mismatch</strong> — the linked {@code promo_rule} is not currently
 *       effective (outside its {@code effective_from}/{@code effective_to} window or its
 *       happy-hour/day-of-week window), so the coupon cannot be honoured right now even though the
 *       code itself is recognised.
 * </ul>
 */
public class CouponInvalidException extends RuntimeException {

  private final String code;

  public CouponInvalidException(String code) {
    super("Coupon code is invalid, unknown, expired, or its rule is not currently effective: " + code);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
