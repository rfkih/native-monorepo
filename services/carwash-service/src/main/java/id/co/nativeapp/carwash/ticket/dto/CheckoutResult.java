package id.co.nativeapp.carwash.ticket.dto;

/**
 * Outcome of a checkout call.
 *
 * @param ticket the checked-out (or pre-existing) ticket, as its response projection
 * @param created {@code true} if this call inserted a new ticket + authorized a tender (and, for
 *     CASH, emitted the {@code SaleRecorded} + {@code MetricPublished} events); {@code false} if an
 *     existing ticket with the same {@code (company_id, idempotency_key)} was returned and NO
 *     second write/events occurred (idempotency on retry)
 */
public record CheckoutResult(TicketResponse ticket, boolean created) {}
