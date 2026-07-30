package id.co.nativeapp.barbershop.ticket.dto;

import java.util.UUID;

/**
 * The payment on a {@link TicketResponse}. {@code status} is a plain string (mirroring carwash's
 * {@code TicketPaymentResponse}) rather than the narrower {@code BarbershopPayment.Status} enum,
 * since the underlying column's {@code CHECK} constraint allows a wider set of values than this
 * phase's code writes.
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
