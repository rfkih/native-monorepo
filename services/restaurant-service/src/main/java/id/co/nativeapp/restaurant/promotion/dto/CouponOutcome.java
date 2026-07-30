package id.co.nativeapp.restaurant.promotion.dto;

import id.co.nativeapp.restaurant.promotion.domain.CouponStatus;
import java.util.UUID;

/**
 * The resolved outcome of a caller-supplied coupon code — present on {@link EvalResult} whenever
 * {@link EvalInput#couponCode()} was non-blank (regardless of whether it actually applied), {@code
 * null} when no code was supplied at all.
 *
 * @param status the resolution outcome (the wire value — the console renders exactly these three)
 * @param couponId the resolved coupon id, or {@code null} if the code is unknown
 * @param ruleId the coupon's linked rule id, or {@code null} if the code is unknown
 * @param code the normalized (trimmed, uppercased) code that was looked up
 * @param redeemable whether a redemption should actually be BURNED at commit. {@code false} when
 *     the coupon resolved fine but granted no new benefit — its linked rule already fired
 *     automatically, its natural amount was zero, or the clamp zeroed it out. The customer still
 *     sees {@code APPLIED} (they have the deal); the redemption count must not move for a coupon
 *     that contributed nothing (a burned-for-nothing single-use code is a support ticket).
 */
public record CouponOutcome(
    CouponStatus status, UUID couponId, UUID ruleId, String code, boolean redeemable) {

  public static CouponOutcome invalid(String code) {
    return new CouponOutcome(CouponStatus.INVALID, null, null, code, false);
  }

  public static CouponOutcome invalid(String code, UUID couponId, UUID ruleId) {
    return new CouponOutcome(CouponStatus.INVALID, couponId, ruleId, code, false);
  }

  public static CouponOutcome exhausted(String code, UUID couponId, UUID ruleId) {
    return new CouponOutcome(CouponStatus.EXHAUSTED, couponId, ruleId, code, false);
  }

  public static CouponOutcome applied(String code, UUID couponId, UUID ruleId) {
    return new CouponOutcome(CouponStatus.APPLIED, couponId, ruleId, code, true);
  }

  /** APPLIED on the wire, but nothing new was granted — do not burn a redemption. */
  public static CouponOutcome appliedNoNewBenefit(String code, UUID couponId, UUID ruleId) {
    return new CouponOutcome(CouponStatus.APPLIED, couponId, ruleId, code, false);
  }

  /** A copy of this outcome downgraded to non-redeemable (post-clamp zeroing). */
  public CouponOutcome withoutRedemption() {
    return new CouponOutcome(status, couponId, ruleId, code, false);
  }
}
