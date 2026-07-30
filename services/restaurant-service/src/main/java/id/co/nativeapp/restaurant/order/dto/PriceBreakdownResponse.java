package id.co.nativeapp.restaurant.order.dto;

import id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown;
import id.co.nativeapp.restaurant.promotion.dto.EvalResult;
import java.util.List;

/**
 * A structured snapshot of the Phase 2 price breakdown for one order or quote.
 *
 * <p>All amounts are in minor currency units (same {@code currency} as the order). The {@code
 * grandTotalMinor} field equals the order's {@code totalMinor} — it is duplicated here so the UI
 * can render a single self-contained breakdown block without having to join with the parent order
 * field.
 *
 * <p>The {@code usesIllustrativeRules} flag is {@code true} when any resolved {@code
 * tax_charge_rule} row carried {@code provenance = ILLUSTRATIVE_PLACEHOLDER}. The POS must badge
 * the tax / service-charge lines as "estimated" when this is {@code true} so cashiers are not
 * misled into believing the amounts are regulatory-verified.
 *
 * <p><strong>Phase 3 (ADR 0026):</strong> {@code appliedPromotions}/{@code couponStatus} are
 * populated ONLY on the {@code POST /api/v1/orders/quote} response (via {@link
 * #from(PriceBreakdown, EvalResult)}) — the promotions engine's per-rule audit detail for a pricing
 * preview. The checkout/pay-parked/pay-bill responses keep using the plain {@link
 * #from(PriceBreakdown)} factory: {@code discountMinor} already carries the correct COLLAPSED total
 * (the engine's output feeds {@code TaxChargeService} directly), and the per-rule detail is durably
 * persisted as {@code applied_promotion} audit rows rather than echoed on those particular
 * responses.
 *
 * <p><strong>Phase 4 (ADR 0027, additive).</strong> {@code discountMinor} is now PROMO-ONLY — a
 * loyalty-points redemption is reported separately as {@code loyaltyRedeemedMinor} (a
 * contra-revenue deduction; matches the {@code SaleRecorded} wire's {@code discount_minor} /
 * {@code loyalty_redeemed_minor} split, EVENT-CATALOG.md). {@code giftCardAppliedMinor} is the
 * amount TENDERED from a gift card (never a discount) and {@code residualDueMinor = grandTotalMinor
 * - giftCardAppliedMinor} is what remains for the requested payment method to actually authorize —
 * so the POS can render the true amount due honestly. All three default to {@code 0} on every
 * pre-Phase-4 call site (fully backward compatible).
 *
 * @param subtotalMinor sum of line totals before any discount (minor units)
 * @param discountMinor order-level PROMO-ONLY discount applied (clamped to &le; subtotal; minor
 *     units) — the Phase-3 COLLAPSED total of every automatic rule, coupon, and manual discount.
 *     Does NOT include a loyalty-points redemption (see {@code loyaltyRedeemedMinor})
 * @param serviceChargeMinor service charge amount (minor units)
 * @param taxMinor tax amount (minor units)
 * @param grandTotalMinor the customer-pays amount: subtotal − discount − loyaltyRedeemedMinor +
 *     serviceCharge + tax (Phase 4 EXTENDED identity, EVENT-CATALOG.md)
 * @param currency ISO-4217 code for all amounts in this breakdown
 * @param usesIllustrativeRules {@code true} when any resolved pricing rule was
 *     ILLUSTRATIVE_PLACEHOLDER; the UI should badge tax / SC as estimated
 * @param appliedPromotions the per-rule deduction detail (Phase 3); empty when not computed via the
 *     promotions engine (see class javadoc)
 * @param couponStatus {@code "APPLIED"} / {@code "INVALID"} / {@code "EXHAUSTED"} when a coupon
 *     code was supplied, {@code null} when none was supplied or this breakdown was not computed via
 *     the promotions engine
 * @param loyaltyRedeemedMinor Phase 4 (ADR 0027): the currency value of loyalty points redeemed
 *     (contra-revenue), minor units; {@code 0} when none
 * @param giftCardAppliedMinor Phase 4 (ADR 0027): the amount TENDERED from a gift card (never a
 *     discount), minor units; {@code 0} when none
 * @param residualDueMinor Phase 4 (ADR 0027): {@code grandTotalMinor - giftCardAppliedMinor} — what
 *     remains for the requested payment method to authorize
 */
public record PriceBreakdownResponse(
    long subtotalMinor,
    long discountMinor,
    long serviceChargeMinor,
    long taxMinor,
    long grandTotalMinor,
    String currency,
    boolean usesIllustrativeRules,
    List<AppliedPromotionResponse> appliedPromotions,
    String couponStatus,
    long loyaltyRedeemedMinor,
    long giftCardAppliedMinor,
    long residualDueMinor) {

  /**
   * Convenience for the pre-Phase-3 seven-arg shape (no promotions detail, no Phase 4 fields) —
   * preserves existing call sites that construct this record directly rather than via {@link
   * #from(PriceBreakdown)}.
   */
  public PriceBreakdownResponse(
      long subtotalMinor,
      long discountMinor,
      long serviceChargeMinor,
      long taxMinor,
      long grandTotalMinor,
      String currency,
      boolean usesIllustrativeRules) {
    this(
        subtotalMinor,
        discountMinor,
        serviceChargeMinor,
        taxMinor,
        grandTotalMinor,
        currency,
        usesIllustrativeRules,
        List.of(),
        null,
        0L,
        0L,
        grandTotalMinor);
  }

  /**
   * Factory: maps a domain {@link PriceBreakdown} to the wire shape (no promotions detail, no
   * Phase 4 redemption).
   */
  public static PriceBreakdownResponse from(PriceBreakdown bd) {
    return from(bd, null, 0L, 0L);
  }

  /**
   * Factory: maps a domain {@link PriceBreakdown} PLUS the promotions engine's {@link EvalResult}
   * to the wire shape — used by the quote endpoint (no Phase 4 redemption).
   */
  public static PriceBreakdownResponse from(PriceBreakdown bd, EvalResult evalResult) {
    return from(bd, evalResult, 0L, 0L);
  }

  /**
   * Factory: maps a domain {@link PriceBreakdown} PLUS the Phase 4 (ADR 0027) redemption amounts
   * (no promotions detail) — used by the checkout/pay-parked responses.
   *
   * @param loyaltyRedeemedMinor the ACTUAL points-redemption currency value, or {@code 0}
   * @param giftCardRedeemedMinor the ACTUAL gift-card tender amount, or {@code 0}
   */
  public static PriceBreakdownResponse from(
      PriceBreakdown bd, long loyaltyRedeemedMinor, long giftCardRedeemedMinor) {
    return from(bd, null, loyaltyRedeemedMinor, giftCardRedeemedMinor);
  }

  /**
   * Factory: maps a domain {@link PriceBreakdown} PLUS the promotions engine's {@link EvalResult}
   * PLUS the Phase 4 (ADR 0027) redemption amounts to the wire shape.
   *
   * <p>{@code bd.discount()} is the COMBINED deduction (promo + loyalty — the figure {@code
   * TaxChargeService.resolve} was actually called with, ADR 0027 pinned semantics); this factory
   * decomposes it back to PROMO-ONLY by subtracting {@code loyaltyRedeemedMinor}, exactly mirroring
   * {@code SaleRecordedSchema#toRecord}'s wire decomposition.
   *
   * @param loyaltyRedeemedMinor the ACTUAL points-redemption currency value, or {@code 0}
   * @param giftCardRedeemedMinor the ACTUAL gift-card tender amount, or {@code 0}
   */
  public static PriceBreakdownResponse from(
      PriceBreakdown bd, EvalResult evalResult, long loyaltyRedeemedMinor, long giftCardRedeemedMinor) {
    List<AppliedPromotionResponse> applied =
        evalResult == null
            ? List.of()
            : evalResult.deductions().stream().map(AppliedPromotionResponse::from).toList();
    String couponStatus =
        (evalResult == null || evalResult.couponOutcome() == null)
            ? null
            : evalResult.couponOutcome().status().name();
    long grandTotalMinor = bd.grandTotal().amountMinor();
    long promoOnlyDiscountMinor = bd.discount().amountMinor() - loyaltyRedeemedMinor;
    return new PriceBreakdownResponse(
        bd.subtotal().amountMinor(),
        promoOnlyDiscountMinor,
        bd.serviceCharge().amountMinor(),
        bd.tax().amountMinor(),
        grandTotalMinor,
        bd.grandTotal().currency().getCurrencyCode(),
        bd.usesIllustrativeRules(),
        applied,
        couponStatus,
        loyaltyRedeemedMinor,
        giftCardRedeemedMinor,
        grandTotalMinor - giftCardRedeemedMinor);
  }
}
