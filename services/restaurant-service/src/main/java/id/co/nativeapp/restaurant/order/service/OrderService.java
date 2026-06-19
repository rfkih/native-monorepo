package id.co.nativeapp.restaurant.order.service;

import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates order checkout and owns the concurrency-safe idempotency contract: exactly one
 * {@code SaleRecorded} per {@code (company_id, idempotency_key)}, and a successful idempotent
 * result for every caller (the first creates, every subsequent call reads the existing order back)
 * — never an unhandled 500.
 *
 * <p>Not itself {@code @Transactional} — transactional units live in {@link OrderWriter} so the
 * proxy and the RLS aspect engage. Follows the same {@code SaleService} pattern exactly.
 */
@Service
public class OrderService {

  private final OrderWriter writer;

  public OrderService(OrderWriter writer) {
    this.writer = writer;
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
      // The create transaction is now aborted; re-read the winner's order in a fresh
      // transaction. No SaleRecorded is written — the winner already emitted it.
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
}
