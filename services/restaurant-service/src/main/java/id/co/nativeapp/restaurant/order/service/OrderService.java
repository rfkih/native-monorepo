package id.co.nativeapp.restaurant.order.service;

import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.restaurant.order.dto.ParkOrderRequest;
import id.co.nativeapp.restaurant.order.dto.ParkedOrderSummary;
import id.co.nativeapp.restaurant.order.dto.PayParkedRequest;
import id.co.nativeapp.restaurant.order.dto.PriceBreakdownResponse;
import id.co.nativeapp.restaurant.order.dto.QuoteRequest;
import id.co.nativeapp.restaurant.order.projection.ParkedOrderView;
import id.co.nativeapp.restaurant.order.repository.OrderRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates order checkout, park/resume/pay, idempotency-safe re-reads, and price quotes.
 *
 * <p>Exactly one {@code SaleRecorded} per {@code (company_id, idempotency_key)} on checkout or
 * pay-parked; a successful idempotent result for every caller on retry.
 *
 * <p>The price-quote path ({@link #quote}) is read-only: it computes a {@link
 * PriceBreakdownResponse} for a given cart without persisting anything.
 *
 * <p>Not itself {@code @Transactional} — transactional units live in {@link OrderWriter} (writes)
 * and {@link OrderReader} (reads) so the proxy and the RLS aspect engage. Follows the same {@code
 * SaleService} pattern exactly.
 */
@Service
public class OrderService {

  private final OrderWriter writer;
  private final OrderReader reader;
  private final OrderRepository orderRepository;

  public OrderService(OrderWriter writer, OrderReader reader, OrderRepository orderRepository) {
    this.writer = writer;
    this.reader = reader;
    this.orderRepository = orderRepository;
  }

  /**
   * Checks out an order idempotently and emits exactly one {@code SaleRecorded} on first checkout.
   * A retry — sequential or concurrent — with the same {@code idempotency_key} resolves to the
   * existing order ({@code created=false}) and emits no second event.
   */
  public CheckoutResult checkout(CheckoutRequest request) {
    TenantContext.require();
    try {
      return writer.checkout(request);
    } catch (DataIntegrityViolationException conflict) {
      // A concurrent racer won the (company_id, idempotency_key) unique constraint.
      // Re-read the winner's order in a fresh transaction.
      return writer
          .findExistingByKey(request.idempotencyKey())
          .map(response -> new CheckoutResult(response, false))
          .orElseThrow(() -> conflict);
    }
  }

  /**
   * Re-reads an existing order (used by the controller to build a 200-idempotent response). Not
   * directly exposed; callers use {@link #checkout} which handles both paths.
   */
  public OrderResponse findExistingByKey(String idempotencyKey) {
    TenantContext.require();
    return writer
        .findExistingByKey(idempotencyKey)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Order not found for idempotency key: " + idempotencyKey));
  }

  /**
   * Parks a cart as a PARKED order — no sale, no payment, no revenue. Idempotent on {@code
   * (company_id, idempotency_key)} like checkout. Revenue is recognised only when {@link
   * #payParked} is called.
   *
   * @throws IllegalArgumentException on invalid items, cross-business table, or tableId on
   *     non-DINE_IN
   */
  public CheckoutResult park(ParkOrderRequest request) {
    TenantContext.require();
    try {
      return writer.park(request);
    } catch (DataIntegrityViolationException conflict) {
      return writer
          .findExistingByKey(request.idempotencyKey())
          .map(response -> new CheckoutResult(response, false))
          .orElseThrow(() -> conflict);
    }
  }

  /**
   * Returns the full order (with lines, modifiers, and breakdown) for the given order id. Used by
   * the POS "resume parked order" flow.
   *
   * @throws IllegalArgumentException if the order is not found or not visible to this tenant
   */
  public OrderResponse getOrder(UUID orderId) {
    TenantContext.require();
    return writer
        .findById(orderId)
        .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
  }

  /**
   * Lists parked orders for a business (the parked-orders tray). Read-only.
   *
   * <p>Projection-to-DTO mapping is performed here (service layer) so that the ArchUnit rule
   * ({@code Projection accessed only by Service + Repository}) is respected.
   *
   * @param businessId the business unit whose parked orders to list
   */
  @Transactional(readOnly = true)
  public List<ParkedOrderSummary> listParked(UUID businessId) {
    TenantContext.require();
    return orderRepository.findParkedViewsByBusinessId(businessId).stream()
        .map(OrderService::toParkedSummary)
        .toList();
  }

  private static ParkedOrderSummary toParkedSummary(ParkedOrderView view) {
    return new ParkedOrderSummary(
        view.getId(),
        view.getBusinessId(),
        view.getTotalMinor(),
        view.getCurrency().strip(),
        view.getTableLabel(),
        view.getLineCount(),
        view.getOccurredAt(),
        view.getOrderType());
  }

  /**
   * Finalises a PARKED order: records the Sale + optional payment, transitions PARKED → COMPLETED
   * (cash / no-payment) or AWAITING_PAYMENT (digital). This is the moment revenue is recognised for
   * a parked order.
   *
   * <p>Idempotent: re-paying a COMPLETED order returns the existing state.
   *
   * @throws IllegalArgumentException if the order is not found, not PARKED, or not visible
   */
  public OrderResponse payParked(UUID orderId, PayParkedRequest request) {
    TenantContext.require();
    try {
      return writer.payParked(orderId, request);
    } catch (DataIntegrityViolationException conflict) {
      // Concurrent collision on the sale idempotency key — re-read the current state.
      return writer.findById(orderId).orElseThrow(() -> conflict);
    }
  }

  /**
   * Computes a price breakdown for the given cart without persisting any order, sale, payment, or
   * outbox row. Read-only and safe to call repeatedly.
   */
  public PriceBreakdownResponse quote(QuoteRequest request) {
    TenantContext.require();
    return reader.computeQuote(request);
  }
}
