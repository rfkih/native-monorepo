package id.co.nativeapp.barbershop.ticket.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code TicketResponse} — the full checkout/receipt shape returned by quote (breakdown only, via
 * {@link PriceBreakdownResponse} embedded in a synthetic response — see {@code TicketController}),
 * checkout, capture, and GET.
 *
 * <p>Domain difference from carwash's {@code TicketResponse} (ADR 0024): {@code chair} replaces
 * {@code bay}; there is NO {@code vehiclePlate} field; {@code staffProfileId} is never {@code null}
 * (mandatory at checkout).
 *
 * @param payment {@code null} until a tender is authorized; present (PENDING or CAPTURED) once
 *     checkout has run
 */
public record TicketResponse(
    UUID ticketId,
    UUID businessId,
    String chair,
    UUID staffProfileId,
    String staffLabel,
    UUID saleId,
    PriceBreakdownResponse breakdown,
    Instant occurredAt,
    List<TicketLineResponse> lines,
    TicketPaymentResponse payment) {}
