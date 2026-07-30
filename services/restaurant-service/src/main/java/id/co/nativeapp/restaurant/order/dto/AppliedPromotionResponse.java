package id.co.nativeapp.restaurant.order.dto;

import id.co.nativeapp.restaurant.promotion.dto.AppliedDeduction;
import java.util.UUID;

/**
 * Wire shape for one applied promotion deduction (Phase 3, ADR 0026) — echoed on {@link
 * PriceBreakdownResponse#appliedPromotions()}.
 *
 * @param name the rule's name at application time
 * @param type the rule's type ({@code PERCENT_OFF_ORDER} / {@code AMOUNT_OFF_ORDER} / {@code
 *     PERCENT_OFF_LINE})
 * @param amountMinor the actual amount this deduction discounted, in minor units
 * @param lineRef the {@code order_line}/{@code bill_line} id this deduction landed on (line-scope
 *     rules only), or {@code null} for an order-level rule. Always {@code null} on a quote response
 *     (a quote persists nothing, so there is no real line to reference yet).
 */
public record AppliedPromotionResponse(String name, String type, long amountMinor, UUID lineRef) {

  /** Maps the engine's internal deduction record to the wire shape. */
  public static AppliedPromotionResponse from(AppliedDeduction d) {
    return new AppliedPromotionResponse(
        d.nameSnapshot(),
        d.typeSnapshot() == null ? null : d.typeSnapshot().name(),
        d.amount().amountMinor(),
        d.lineRef());
  }
}
