package id.co.nativeapp.restaurant.order.dto;

import id.co.nativeapp.restaurant.order.domain.OrderLine;
import java.util.List;
import java.util.UUID;

/**
 * Response shape for a single order line.
 *
 * <p>Phase 3 additions: {@code modifiers} — the list of modifier snapshots selected for this line
 * (receipt-reproducible; empty when no modifiers were chosen).
 */
public record OrderLineResponse(
    UUID menuItemId,
    String name,
    long unitPriceMinor,
    int qty,
    long lineTotalMinor,
    List<OrderLineModifierResponse> modifiers) {

  /** Compatibility constructor with no modifiers (preserves existing call sites). */
  public OrderLineResponse(
      UUID menuItemId, String name, long unitPriceMinor, int qty, long lineTotalMinor) {
    this(menuItemId, name, unitPriceMinor, qty, lineTotalMinor, List.of());
  }

  public static OrderLineResponse from(OrderLine line) {
    List<OrderLineModifierResponse> modifierResponses =
        line.getModifiers().stream()
            .map(
                m ->
                    new OrderLineModifierResponse(
                        m.getOptionId(), m.getNameSnapshot(), m.getPriceDeltaMinor()))
            .toList();
    return new OrderLineResponse(
        line.getMenuItemId(),
        line.getNameSnapshot(),
        line.getUnitPriceMinor(),
        line.getQty(),
        line.getLineTotalMinor(),
        modifierResponses);
  }
}
