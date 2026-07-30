package id.co.nativeapp.barbershop.ticket.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/barbershop/tickets/quote} request body — a stateless price preview: given
 * the requested lines, what would the breakdown be. No ticket is persisted; no side effect.
 *
 * @param businessId the barbershop outlet the quote is for
 * @param lines the requested lines; must be non-empty
 * @param discountMinor an optional fixed discount in minor units; {@code null} for no discount
 */
public record QuoteRequest(
    @NotNull UUID businessId,
    @NotEmpty List<@Valid TicketLineInput> lines,
    @PositiveOrZero Long discountMinor) {}
