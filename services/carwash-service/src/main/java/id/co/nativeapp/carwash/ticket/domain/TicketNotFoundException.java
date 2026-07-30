package id.co.nativeapp.carwash.ticket.domain;

import java.util.UUID;

/**
 * Thrown when a {@code carwash_ticket} id is not found in the bound tenant — either it genuinely
 * does not exist, or it belongs to another company and RLS makes it invisible (indistinguishable
 * from "unknown", no existence disclosure). Maps to {@code 404} via {@code config.TicketAdvice}.
 */
public class TicketNotFoundException extends RuntimeException {

  public TicketNotFoundException(UUID ticketId) {
    super("Ticket not found: " + ticketId);
  }
}
