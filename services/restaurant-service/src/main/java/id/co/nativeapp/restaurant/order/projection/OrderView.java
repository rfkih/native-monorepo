package id.co.nativeapp.restaurant.order.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection over the {@code restaurant_order} row — only the columns a response needs, never
 * the {@link id.co.nativeapp.tenant.Auditable Auditable} bookkeeping.
 *
 * <p>Backs the native read queries on {@code OrderRepository}. Lives in its own {@code projection}
 * package — a read model is neither the write-side {@code domain} entity nor a request/response
 * {@code dto}.
 */
public interface OrderView {

  UUID getId();

  UUID getBusinessId();

  String getStatus();

  long getTotalMinor();

  String getCurrency();

  UUID getSaleId();

  Instant getOccurredAt();

  String getIdempotencyKey();

  /** Phase 4: order type (DINE_IN / TAKEAWAY / DELIVERY). */
  String getOrderType();

  /** Phase 4: nullable table id (DINE_IN only). */
  UUID getTableId();
}
