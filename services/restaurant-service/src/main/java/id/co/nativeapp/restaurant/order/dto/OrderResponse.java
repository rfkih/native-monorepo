package id.co.nativeapp.restaurant.order.dto;

import id.co.nativeapp.restaurant.order.domain.Order;
import java.util.List;
import java.util.UUID;

/** Response body for a checked-out order. */
public record OrderResponse(
    UUID orderId,
    UUID businessId,
    long totalMinor,
    String currency,
    UUID saleId,
    List<OrderLineResponse> lines) {

  /** Maps the write-path aggregate (with its in-memory lines) to the response shape. */
  public static OrderResponse from(Order order) {
    List<OrderLineResponse> lineResponses =
        order.getLines().stream().map(OrderLineResponse::from).toList();
    return new OrderResponse(
        order.getId(),
        order.getBusinessId(),
        order.getTotal().amountMinor(),
        order.getTotal().currency().getCurrencyCode(),
        order.getSaleId(),
        lineResponses);
  }
}
