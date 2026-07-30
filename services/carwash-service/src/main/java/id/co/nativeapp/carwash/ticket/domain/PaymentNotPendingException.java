package id.co.nativeapp.carwash.ticket.domain;

import java.util.UUID;

/**
 * Thrown by {@code POST /api/v1/carwash/tickets/{id}/capture} when the ticket's payment is neither
 * already {@code CAPTURED} (the idempotent-replay case, handled separately with no error) nor {@code
 * PENDING} (the only capturable state in this phase). Maps to {@code 409 Conflict} via {@code
 * config.TicketAdvice}.
 */
public class PaymentNotPendingException extends RuntimeException {

  public PaymentNotPendingException(UUID ticketId) {
    super("Ticket " + ticketId + "'s payment is not in a capturable (PENDING) state");
  }
}
