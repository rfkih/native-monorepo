package id.co.nativeapp.barbershop.promotion.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * One cart/ticket line as seen by {@link
 * id.co.nativeapp.barbershop.promotion.service.PromotionEngineService PromotionEngineService}.
 * Ported verbatim from restaurant-service via carwash-service.
 *
 * @param lineId the persisted {@code barbershop_ticket_line} id this line will become (or already
 *     is); {@code null} for a quote, which persists nothing — a line-scope deduction produced from
 *     a quote-time evaluation therefore carries a {@code null} {@code lineRef} on the wire (there
 *     is no real line to reference yet)
 * @param menuItemId the {@code service_item}/{@code service_addon} id this line is for (ITEM-scope
 *     matching) — named {@code menuItemId} to keep this record's shape identical to restaurant's
 *     port source; barbershop has no catalog "menu item" concept, this is simply "the item id"
 * @param categoryId ALWAYS {@code null} for barbershop — there is no category dimension in the
 *     service catalog (unlike restaurant's {@code menu_category}), so a CATEGORY-scoped rule can
 *     never match (see {@link id.co.nativeapp.barbershop.promotion.domain.PromoScopeKind
 *     PromoScopeKind})
 * @param unitPriceMinor the per-unit price in minor units — {@code unitPriceMinor * qty ==
 *     lineTotalMinor()}
 * @param qty the line quantity; must be &ge; 1
 */
public record EvalLine(
    UUID lineId, UUID menuItemId, UUID categoryId, long unitPriceMinor, int qty) {

  public EvalLine {
    Objects.requireNonNull(menuItemId, "menuItemId");
    if (qty < 1) {
      throw new IllegalArgumentException("qty must be >= 1, got: " + qty);
    }
  }

  /**
   * The pre-computed line total in minor units — {@code unitPriceMinor * qty}, exact integer math.
   */
  public long lineTotalMinor() {
    return Math.multiplyExact(unitPriceMinor, (long) qty);
  }
}
