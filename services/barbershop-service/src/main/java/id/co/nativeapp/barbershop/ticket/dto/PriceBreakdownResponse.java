package id.co.nativeapp.barbershop.ticket.dto;

import java.util.List;

/**
 * The response shape of a resolved price breakdown (quote, or a checked-out ticket's pricing).
 *
 * <p><strong>Phase 3 (ADR 0026):</strong> {@code appliedPromotions}/{@code couponStatus} are
 * populated ONLY on the {@code POST /api/v1/barbershop/tickets/quote} response — the promotions
 * engine's per-rule audit detail for a pricing preview (mirrors carwash-service's identical port of
 * restaurant-service's {@code PriceBreakdownResponse}). The checkout response's embedded breakdown
 * keeps using the plain 7-arg shape below: {@code discountMinor} already carries the correct
 * COLLAPSED total (the engine's output feeds {@code TaxChargeService} directly), and the per-rule
 * detail is durably persisted as {@code applied_promotion} audit rows rather than echoed there.
 *
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
   * Convenience for the pre-Phase-3 seven-arg shape (no promotions detail) — preserves every
   * existing call site that constructs this record directly (the checkout/capture/get responses via
   * {@code TicketResponseFactory}).
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
}
