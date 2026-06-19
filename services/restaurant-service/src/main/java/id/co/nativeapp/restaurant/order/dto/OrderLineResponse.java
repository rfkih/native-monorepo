package id.co.nativeapp.restaurant.order.dto;

import id.co.nativeapp.restaurant.order.domain.OrderLine;
import java.util.UUID;

/** Response shape for a single order line. */
public record OrderLineResponse(
    UUID menuItemId, String name, long unitPriceMinor, int qty, long lineTotalMinor) {

  public static OrderLineResponse from(OrderLine line) {
    return new OrderLineResponse(
        line.getMenuItemId(),
        line.getNameSnapshot(),
        line.getUnitPriceMinor(),
        line.getQty(),
        line.getLineTotalMinor());
  }
}
