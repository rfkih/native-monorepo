package id.co.nativeapp.restaurant.order.dto;

import id.co.nativeapp.restaurant.order.domain.Order;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown;
import java.util.List;
import java.util.UUID;

/**
 * Response body for a checked-out order. {@code payment} is present when the order was paid in the
 * same checkout call (cash), and null otherwise (no payment, or an existing-order re-read). {@code
 * breakdown} carries the Phase 2 price breakdown (subtotal, discount, service charge, tax, grand
 * total) populated at checkout; it is present on every newly created order.
 */
public record OrderResponse(
    UUID orderId,
    UUID businessId,
    long totalMinor,
    String currency,
    UUID saleId,
    List<OrderLineResponse> lines,
    PaymentResponse payment,
    PriceBreakdownResponse breakdown) {

  /**
   * Maps the write-path aggregate (with its in-memory lines) to the response shape, with the
   * computed price breakdown. No payment.
   */
  public static OrderResponse from(Order order, PriceBreakdown bd) {
    List<OrderLineResponse> lineResponses =
        order.getLines().stream().map(OrderLineResponse::from).toList();
    return new OrderResponse(
        order.getId(),
        order.getBusinessId(),
        order.getTotal().amountMinor(),
        order.getTotal().currency().getCurrencyCode(),
        order.getSaleId(),
        lineResponses,
        null,
        PriceBreakdownResponse.from(bd));
  }

  /**
   * Maps the write-path aggregate (with its in-memory lines) to the response shape, no breakdown
   * (backwards-compatible convenience — used for idempotent re-reads where the breakdown is not
   * re-computed).
   */
  public static OrderResponse from(Order order) {
    List<OrderLineResponse> lineResponses =
        order.getLines().stream().map(OrderLineResponse::from).toList();
    return new OrderResponse(
        order.getId(),
        order.getBusinessId(),
        order.getTotal().amountMinor(),
        order.getTotal().currency().getCurrencyCode(),
        order.getSaleId(),
        lineResponses,
        null,
        null);
  }

  /** Returns a copy of this response with the given payment attached. */
  public OrderResponse withPayment(PaymentResponse paymentResponse) {
    return new OrderResponse(
        orderId, businessId, totalMinor, currency, saleId, lines, paymentResponse, breakdown);
  }

  /** Returns a copy of this response with the given breakdown attached. */
  public OrderResponse withBreakdown(PriceBreakdownResponse bd) {
    return new OrderResponse(orderId, businessId, totalMinor, currency, saleId, lines, payment, bd);
  }
}
