package id.co.nativeapp.restaurant.order.dto;

import id.co.nativeapp.restaurant.order.domain.Order;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown;
import java.util.List;
import java.util.UUID;

/**
 * Response body for a checked-out or parked order. {@code payment} is present when the order was
 * paid in the same checkout/pay call (cash), and null otherwise. {@code breakdown} carries the
 * Phase 2 price breakdown populated at checkout/park; it is present on every newly created order.
 *
 * <p>Phase 4 additions: {@code orderType} (DINE_IN / TAKEAWAY / DELIVERY) and {@code tableId}
 * (nullable; only set for DINE_IN orders with an assigned table). These fields are always present
 * on newly created orders; they may be {@code null} on idempotent re-reads of pre-Phase-4 orders
 * whose DB rows carry the DEFAULT values.
 */
public record OrderResponse(
    UUID orderId,
    UUID businessId,
    long totalMinor,
    String currency,
    UUID saleId,
    List<OrderLineResponse> lines,
    PaymentResponse payment,
    PriceBreakdownResponse breakdown,
    String status,
    String orderType,
    UUID tableId) {

  /**
   * Backwards-compatible constructor for callers that do not supply Phase 4 fields (pre-existing
   * tests and the idempotent re-read path).
   */
  public OrderResponse(
      UUID orderId,
      UUID businessId,
      long totalMinor,
      String currency,
      UUID saleId,
      List<OrderLineResponse> lines,
      PaymentResponse payment,
      PriceBreakdownResponse breakdown) {
    this(
        orderId,
        businessId,
        totalMinor,
        currency,
        saleId,
        lines,
        payment,
        breakdown,
        null,
        null,
        null);
  }

  /**
   * Maps the write-path aggregate (with its in-memory lines) to the response shape, with the
   * computed price breakdown. No payment. Phase 4 (ADR 0027): the embedded breakdown's {@code
   * loyaltyRedeemedMinor}/{@code giftCardAppliedMinor}/{@code residualDueMinor} are read off the
   * order's already-stamped redemption columns (the caller must call {@code
   * Order#applyLoyaltyRedemption} before this).
   */
  public static OrderResponse from(Order order, PriceBreakdown bd) {
    List<OrderLineResponse> lineResponses =
        order.getLines().stream().map(OrderLineResponse::from).toList();
    long loyaltyRedeemedMinor =
        order.getLoyaltyRedeemedMinor() != null ? order.getLoyaltyRedeemedMinor() : 0L;
    long giftCardRedeemedMinor =
        order.getGiftCardRedeemedMinor() != null ? order.getGiftCardRedeemedMinor() : 0L;
    return new OrderResponse(
        order.getId(),
        order.getBusinessId(),
        order.getTotal().amountMinor(),
        order.getTotal().currency().getCurrencyCode(),
        order.getSaleId(),
        lineResponses,
        null,
        PriceBreakdownResponse.from(bd, loyaltyRedeemedMinor, giftCardRedeemedMinor),
        order.getStatus(),
        order.getOrderType(),
        order.getTableId());
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
        null,
        order.getStatus(),
        order.getOrderType(),
        order.getTableId());
  }

  /** Returns a copy of this response with the given payment attached. */
  public OrderResponse withPayment(PaymentResponse paymentResponse) {
    return new OrderResponse(
        orderId,
        businessId,
        totalMinor,
        currency,
        saleId,
        lines,
        paymentResponse,
        breakdown,
        status,
        orderType,
        tableId);
  }

  /** Returns a copy of this response with the given breakdown attached. */
  public OrderResponse withBreakdown(PriceBreakdownResponse bd) {
    return new OrderResponse(
        orderId,
        businessId,
        totalMinor,
        currency,
        saleId,
        lines,
        payment,
        bd,
        status,
        orderType,
        tableId);
  }
}
