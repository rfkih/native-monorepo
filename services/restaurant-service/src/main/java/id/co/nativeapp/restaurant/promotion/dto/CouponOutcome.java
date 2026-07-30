package id.co.nativeapp.restaurant.promotion.dto;

import id.co.nativeapp.restaurant.promotion.domain.CouponStatus;
import java.util.UUID;

/**
 * The resolved outcome of a caller-supplied coupon code — present on {@link EvalResult} whenever
 * {@link EvalInput#couponCode()} was non-blank (regardless of whether it actually applied), {@code
 * null} when no code was supplied at all.
 *
 * @param status the resolution outcome
 * @param couponId the resolved coupon id, or {@code null} if the code is unknown
 * @param ruleId the coupon's linked rule id, or {@code null} if the code is unknown
 * @param code the normalized (trimmed, uppercased) code that was looked up
 */
public record CouponOutcome(CouponStatus status, UUID couponId, UUID ruleId, String code) {

  public static CouponOutcome invalid(String code) {
    return new CouponOutcome(CouponStatus.INVALID, null, null, code);
  }

  public static CouponOutcome invalid(String code, UUID couponId, UUID ruleId) {
    return new CouponOutcome(CouponStatus.INVALID, couponId, ruleId, code);
  }

  public static CouponOutcome exhausted(String code, UUID couponId, UUID ruleId) {
    return new CouponOutcome(CouponStatus.EXHAUSTED, couponId, ruleId, code);
  }

  public static CouponOutcome applied(String code, UUID couponId, UUID ruleId) {
    return new CouponOutcome(CouponStatus.APPLIED, couponId, ruleId, code);
  }
}
