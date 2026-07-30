package id.co.nativeapp.barbershop.ticket.service;

import id.co.nativeapp.barbershop.ticket.domain.BarbershopTicket;

/**
 * A test seam invoked inside {@link TicketWriter#create} <em>immediately after</em> the {@code
 * SaleRecorded} + {@code MetricPublished} outbox rows are written (CASH path only), but still
 * inside the transaction. Mirrors carwash-service's {@code ticket.service.TicketPostOutboxHook}
 * exactly.
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
  void afterOutboxWrite(BarbershopTicket ticket);

  /** The production no-op hook. */
  final class Noop implements TicketPostOutboxHook {
    @Override
    public void afterOutboxWrite(BarbershopTicket ticket) {
      // intentionally nothing
    }
  }
}
