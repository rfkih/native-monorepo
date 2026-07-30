package id.co.nativeapp.restaurant.promotion.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * One cart/order/bill line as seen by {@link
 * id.co.nativeapp.restaurant.promotion.service.PromotionEngineService PromotionEngineService}.
 *
 * @param lineId the persisted {@code order_line}/{@code bill_line} id this line will become (or
 *     already is, on a pay-parked/pay-bill recompute); {@code null} for a quote, which persists
 *     nothing — a line-scope deduction produced from a quote-time evaluation therefore carries a
 *     {@code null} {@code lineRef} on the wire (there is no real line to reference yet)
 * @param menuItemId the menu item this line is for (ITEM-scope matching)
 * @param categoryId the menu item's category at evaluation time, or {@code null} if uncategorized
 *     (CATEGORY-scope matching)
 * @param unitPriceMinor the EFFECTIVE per-unit price in minor units (base price + modifier deltas) —
 *     {@code unitPriceMinor * qty == lineTotalMinor()}, exactly mirroring how {@code OrderLine}/{@code
 *     BillLine} compute their own {@code line_total_minor}
 * @param qty the line quantity; must be &ge; 1
 */
public record EvalLine(UUID lineId, UUID menuItemId, UUID categoryId, long unitPriceMinor, int qty) {

  public EvalLine {
    Objects.requireNonNull(menuItemId, "menuItemId");
    if (qty < 1) {
      throw new IllegalArgumentException("qty must be >= 1, got: " + qty);
    }
  }

  /** The pre-computed line total in minor units — {@code unitPriceMinor * qty}, exact integer math. */
  public long lineTotalMinor() {
    return Math.multiplyExact(unitPriceMinor, (long) qty);
  }
}
