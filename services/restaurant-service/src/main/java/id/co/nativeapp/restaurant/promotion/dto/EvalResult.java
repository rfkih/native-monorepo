package id.co.nativeapp.restaurant.promotion.dto;

import id.co.nativeapp.money.Money;
import java.util.List;
import java.util.Objects;

/**
 * The result of {@link id.co.nativeapp.restaurant.promotion.service.PromotionEngineService#evaluate
 * PromotionEngineService.evaluate} — the single collapsed discount that feeds {@code
 * TaxChargeService.resolve} as the {@code fixedDiscount} argument, plus the per-rule audit detail.
 *
 * @param totalDiscount the SUM of every applied deduction (line-scope + automatics + coupon +
 *     manual), already clamped so it never exceeds the input subtotal — this is what the caller
 *     passes to {@code TaxChargeService.resolve} in place of the raw manual discount
 * @param deductions the RULE-backed deductions only (line-scope + automatics + coupon) — excludes
 *     the manual discount, which has no {@code ruleId} and is therefore never persisted as an
 *     {@code applied_promotion} row. This is also what the quote endpoint echoes as {@code
 *     appliedPromotions}
 * @param couponOutcome the resolved coupon outcome, or {@code null} if no coupon code was supplied
 */
public record EvalResult(
    Money totalDiscount, List<AppliedDeduction> deductions, CouponOutcome couponOutcome) {

  public EvalResult {
    Objects.requireNonNull(totalDiscount, "totalDiscount");
    Objects.requireNonNull(deductions, "deductions");
    deductions = List.copyOf(deductions);
  }
}
