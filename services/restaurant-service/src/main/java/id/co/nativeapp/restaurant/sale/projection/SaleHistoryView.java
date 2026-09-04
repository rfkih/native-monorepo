package id.co.nativeapp.restaurant.sale.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection over a {@code sale} row LEFT JOINed to its {@code restaurant_order} (for the
 * reprint link) — the cashier's "today's transactions" list ({@code GET /api/v1/sales}).
 *
 * <p>Backs {@code SaleRepository.findHistory}. Snake_case native-query aliases map to these
 * accessors via Spring Data's projection-interface convention (CLAUDE.md "native-query aliases
 * snake_case; map via projection interfaces"). Lives in its own {@code projection} package — a read
 * model is neither the write-side {@code domain} entity nor a request/response {@code dto}.
 */
public interface SaleHistoryView {

  UUID getId();

  /** The linked order id, or {@code null} when no order backs this sale (LEFT JOIN). */
  UUID getOrderId();

  Instant getOccurredAt();

  long getAmountMinor();

  String getCurrency();

  /** The settling tender, or {@code null} for legacy/no-payment sales. */
  String getTenderType();

  /** The sales-channel code, or {@code null} for a non-ONLINE tender. */
  String getChannelCode();

  /**
   * The settling payment's lifecycle status ({@code CAPTURED | VOIDED | REFUNDED |
   * PARTIALLY_REFUNDED}), or {@code null} when no payment backs the sale (LEFT JOIN — e.g.
   * bill-cash and legacy {@code POST /api/v1/sales} sales have no payment row).
   */
  String getPaymentStatus();

  /**
   * The settling payment's cumulative refunded amount in minor units — 0 when nothing was refunded
   * AND for a sale with no payment row (the query COALESCEs the LEFT JOIN's null), so the client's
   * net-of-reversals day total can subtract it unconditionally. A void leaves this at 0 (a void is
   * not a refund); {@link #getPaymentStatus} is what zeroes a voided sale out of a net figure.
   */
  long getRefundedMinor();
}
