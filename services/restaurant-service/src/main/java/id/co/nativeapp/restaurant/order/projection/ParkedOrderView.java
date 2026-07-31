package id.co.nativeapp.restaurant.order.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight read projection for the parked-orders tray: id, table label, grand total, line count,
 * and the occurred-at timestamp. Backs the native list query on {@code OrderRepository}.
 *
 * <p>Lives in its own {@code projection} package — a read model is neither the write-side domain
 * entity nor a request/response DTO.
 */
public interface ParkedOrderView {

  UUID getId();

  UUID getBusinessId();

  long getTotalMinor();

  String getCurrency();

  /** The label of the associated table, or {@code null} when no table is linked. */
  String getTableLabel();

  int getLineCount();

  Instant getOccurredAt();

  String getOrderType();

  /** Phase 6 (ADR 0029): POS (default) or SELF_ORDER — badges an anonymous QR-scanned park. */
  String getSource();
}
