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
 * populated ONLY on the {@code POST /api/v1/orders/quote} response (via {@link #from(PriceBreakdown,
 * EvalResult)}) — the promotions engine's per-rule audit detail for a pricing preview. The
 * checkout/pay-parked/pay-bill responses keep using the plain {@link #from(PriceBreakdown)} factory:
 * {@code discountMinor} already carries the correct COLLAPSED total (the engine's output feeds {@code
 * TaxChargeService} directly), and the per-rule detail is durably persisted as {@code
 * applied_promotion} audit rows rather than echoed on those particular responses.
 *
 * @param subtotalMinor sum of line totals before any discount (minor units)
 * @param discountMinor order-level discount applied (clamped to &le; subtotal; minor units) — the
 *     Phase-3 COLLAPSED total of every automatic rule, coupon, and manual discount
 * @param serviceChargeMinor service charge amount (minor units)
 * @param taxMinor tax amount (minor units)
 * @param grandTotalMinor the customer-pays amount: subtotal − discount + serviceCharge + tax
 * @param currency ISO-4217 code for all amounts in this breakdown
 * @param usesIllustrativeRules {@code true} when any resolved pricing rule was
 *     ILLUSTRATIVE_PLACEHOLDER; the UI should badge tax / SC as estimated
 * @param appliedPromotions the per-rule deduction detail (Phase 3); empty when not computed via the
 *     promotions engine (see class javadoc)
 * @param couponStatus {@code "APPLIED"} / {@code "INVALID"} / {@code "EXHAUSTED"} when a coupon code
 *     was supplied, {@code null} when none was supplied or this breakdown was not computed via the
 *     promotions engine
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
    String couponStatus) {

  /**
   * Convenience for the pre-Phase-3 seven-arg shape (no promotions detail) — preserves existing call
   * sites that construct this record directly rather than via {@link #from(PriceBreakdown)}.
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
        null);
  }

  /** Factory: maps a domain {@link PriceBreakdown} to the wire shape (no promotions detail). */
  public static PriceBreakdownResponse from(PriceBreakdown bd) {
    return from(bd, null);
  }

  /**
   * Factory: maps a domain {@link PriceBreakdown} PLUS the promotions engine's {@link EvalResult} to
   * the wire shape — used by the quote endpoint.
   */
  public static PriceBreakdownResponse from(PriceBreakdown bd, EvalResult evalResult) {
    List<AppliedPromotionResponse> applied =
        evalResult == null
            ? List.of()
            : evalResult.deductions().stream().map(AppliedPromotionResponse::from).toList();
    String couponStatus =
        (evalResult == null || evalResult.couponOutcome() == null)
            ? null
            : evalResult.couponOutcome().status().name();
    return new PriceBreakdownResponse(
        bd.subtotal().amountMinor(),
        bd.discount().amountMinor(),
        bd.serviceCharge().amountMinor(),
        bd.tax().amountMinor(),
        bd.grandTotal().amountMinor(),
        bd.grandTotal().currency().getCurrencyCode(),
        bd.usesIllustrativeRules(),
        applied,
        couponStatus);
  }
}
