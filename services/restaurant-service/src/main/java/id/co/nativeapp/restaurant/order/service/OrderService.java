package id.co.nativeapp.restaurant.order.service;

import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.restaurant.order.dto.PriceBreakdownResponse;
import id.co.nativeapp.restaurant.order.dto.QuoteRequest;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates order checkout, idempotency-safe re-reads, and price quotes.
 *
 * <p>Exactly one {@code SaleRecorded} per {@code (company_id, idempotency_key)} on checkout; a
 * successful idempotent result for every caller (the first creates, every subsequent call reads the
 * existing order back) — never an unhandled 500.
 *
 * <p>The price-quote path ({@link #quote}) is read-only: it computes a {@link
 * PriceBreakdownResponse} for a given cart without persisting anything, so it is safe to call
 * repeatedly from the POS live-total display.
 *
 * <p>Not itself {@code @Transactional} — transactional units live in {@link OrderWriter} (writes)
 * and {@link OrderReader} (reads) so the proxy and the RLS aspect engage. Follows the same {@code
 * SaleService} pattern exactly.
 */
@Service
public class OrderService {

  private final OrderWriter writer;
  private final OrderReader reader;

  public OrderService(OrderWriter writer, OrderReader reader) {
    this.writer = writer;
    this.reader = reader;
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

  /**
   * Computes a price breakdown for the given cart (businessId + lines + optional discount) without
   * persisting any order, sale, payment, or outbox row. Read-only and safe to call repeatedly.
   *
   * <p>Applies the same item validation as checkout: items must exist, be active, belong to {@code
   * businessId}, and share the same currency. The breakdown reflects the effective tax and
   * service-charge rules for the current tenant at the time of the call.
   *
   * <p>Surfaces {@code usesIllustrativeRules} so the POS UI can badge the tax line as estimated
   * when the resolved pricing rules are illustrative placeholders.
   *
   * @param request the cart to price
   * @return the computed price breakdown, never {@code null}
   * @throws IllegalArgumentException if any item is unknown/inactive/cross-business, or if items
   *     span more than one currency
   */
  public PriceBreakdownResponse quote(QuoteRequest request) {
    TenantContext.require();
    return reader.computeQuote(request);
  }
}
