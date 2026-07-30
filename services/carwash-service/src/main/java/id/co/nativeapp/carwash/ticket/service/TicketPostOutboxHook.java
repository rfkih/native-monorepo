package id.co.nativeapp.carwash.ticket.service;

import id.co.nativeapp.carwash.ticket.domain.CarwashTicket;

/**
 * A test seam invoked inside {@link TicketWriter#create} <em>immediately after</em> the {@code
 * SaleRecorded} + {@code MetricPublished} outbox rows are written (CASH path only), but still
 * inside the transaction. Mirrors {@code wash.service.PostOutboxHook} exactly; a ticket-local seam
 * (rather than reusing the wash one) keeps the two features independently testable without one
 * atomicity test's throwing hook leaking into the other feature's Spring context.
 *
 * <p>The production bean ({@link Noop}) does nothing, so this is invisible at runtime. The
 * atomicity test installs a hook that throws here to force a failure AFTER the outbox writes —
 * proving the ticket, its lines, its payment, and the outbox rows roll back together (rule 3).
 */
@FunctionalInterface
public interface TicketPostOutboxHook {

  /**
   * Called just after the {@code SaleRecorded} + {@code MetricPublished} outbox rows are written,
   * within the same transaction. A thrown exception rolls back the ticket, its lines, its payment,
   * and the outbox rows together.
   *
   * @param ticket the ticket just persisted in this transaction
   */
  void afterOutboxWrite(CarwashTicket ticket);

  /** The production no-op hook. */
  final class Noop implements TicketPostOutboxHook {
    @Override
    public void afterOutboxWrite(CarwashTicket ticket) {
      // intentionally nothing
    }
  }
}
