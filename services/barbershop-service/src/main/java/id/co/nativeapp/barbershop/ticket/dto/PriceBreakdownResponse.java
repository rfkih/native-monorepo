package id.co.nativeapp.barbershop.ticket.dto;

import java.util.List;

/**
 * The response shape of a resolved price breakdown (quote, or a checked-out ticket's pricing).
 *
 * <p><strong>Phase 3 (ADR 0026):</strong> {@code appliedPromotions}/{@code couponStatus} are
 * populated ONLY on the {@code POST /api/v1/barbershop/tickets/quote} response — the promotions
 * engine's per-rule audit detail for a pricing preview (mirrors restaurant-service's {@code
 * PriceBreakdownResponse}). The checkout response's embedded breakdown keeps using the plain 7-arg
 * shape below: {@code discountMinor} already carries the correct COLLAPSED total (the engine's
 * output feeds {@code TaxChargeService} directly), and the per-rule detail is durably persisted as
 * {@code applied_promotion} audit rows rather than echoed there.
 *
 * <p><strong>Phase 4 (ADR 0027, additive).</strong> {@code discountMinor} is now PROMO-ONLY — a
 * loyalty-points redemption is reported separately as {@code loyaltyRedeemedMinor} (matches the
 * {@code SaleRecorded} wire's {@code discount_minor} / {@code loyalty_redeemed_minor} split).
 * {@code giftCardAppliedMinor} is the amount TENDERED from a gift card (never a discount) and
 * {@code residualDueMinor = grandTotalMinor - giftCardAppliedMinor} is what remains for the
 * requested payment method to actually authorize. All three default to {@code 0} on every
 * pre-Phase-4 call site.
 *
 * @param appliedPromotions the per-rule deduction detail (Phase 3); empty when not computed via the
 *     promotions engine (see class javadoc)
 * @param couponStatus {@code "APPLIED"} / {@code "INVALID"} / {@code "EXHAUSTED"} when a coupon
 *     code was supplied, {@code null} when none was supplied or this breakdown was not computed via
 *     the promotions engine
 * @param loyaltyRedeemedMinor Phase 4 (ADR 0027): the currency value of loyalty points redeemed
 *     (contra-revenue), minor units; {@code 0} when none
 * @param giftCardAppliedMinor Phase 4 (ADR 0027): the amount TENDERED from a gift card (never a
 *     discount), minor units; {@code 0} when none
 * @param residualDueMinor Phase 4 (ADR 0027): {@code grandTotalMinor - giftCardAppliedMinor}
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
   * Convenience for the pre-Phase-3 seven-arg shape (no promotions detail, no Phase 4 fields).
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
   * Convenience for the checkout/capture/get responses (no promotions detail) PLUS the Phase 4
   * (ADR 0027) redemption amounts.
   */
  public PriceBreakdownResponse(
      long subtotalMinor,
      long discountMinor,
      long serviceChargeMinor,
      long taxMinor,
      long grandTotalMinor,
      String currency,
      boolean usesIllustrativeRules,
      long loyaltyRedeemedMinor,
      long giftCardAppliedMinor) {
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
        loyaltyRedeemedMinor,
        giftCardAppliedMinor,
        grandTotalMinor - giftCardAppliedMinor);
  }
}
