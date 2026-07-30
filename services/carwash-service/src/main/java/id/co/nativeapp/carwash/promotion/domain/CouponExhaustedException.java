package id.co.nativeapp.carwash.promotion.domain;

/**
 * Thrown at checkout time when a coupon's redemption count is exhausted — either the advisory check
 * inside {@code PromotionEngineService.evaluate} already saw {@code redeemed_count >=
 * max_redemptions}, or (the definitive, race-safe guard) the atomic {@code
 * CouponRepository#redeemIfAvailable} UPDATE returned zero rows because a concurrent checkout won
 * the last redemption first. Ported verbatim from restaurant-service (ADR 0026).
 *
 * <p>Maps to {@code 409 Conflict} via {@code config.PromotionAdvice}. The whole checkout
 * transaction rolls back — no ticket, no payment, no other promotion's applied row.
 */
public class CouponExhaustedException extends RuntimeException {

  private final String code;

  public CouponExhaustedException(String code) {
    super("Coupon is exhausted (redemption limit reached): " + code);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
