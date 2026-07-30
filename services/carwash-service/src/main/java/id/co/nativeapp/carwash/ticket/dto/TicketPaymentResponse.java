package id.co.nativeapp.carwash.ticket.dto;

import java.util.UUID;

/**
 * The payment on a {@link TicketResponse}. {@code status} is a plain string (mirroring restaurant's
 * {@code PaymentResponse}) rather than the narrower {@code CarwashPayment.Status} enum, since the
 * underlying column's {@code CHECK} constraint allows a wider set of values than this phase's code
 * writes.
 */
public record TicketPaymentResponse(
    UUID paymentId,
    UUID ticketId,
    String tenderType,
    String status,
    long amountMinor,
    String currency,
    Long tenderedMinor,
    Long changeMinor,
    boolean providerPending,
    UUID saleId) {}
