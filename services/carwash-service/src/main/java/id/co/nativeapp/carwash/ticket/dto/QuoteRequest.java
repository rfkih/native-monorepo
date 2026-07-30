package id.co.nativeapp.carwash.ticket.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/carwash/tickets/quote} request body — a stateless price preview: given the
 * requested lines, what would the breakdown be. No ticket is persisted; no side effect.
 *
 * <p>The resulting {@link PriceBreakdownResponse} reflects the currently effective tax/service-
 * charge rules for this tenant PLUS (Phase 3, ADR 0026) every currently-effective automatic
 * promotion and the resolved outcome of {@code couponCode}. A quote NEVER throws for a bad/expired/
 * exhausted coupon code — it reports {@code couponStatus} instead, since it is only a pricing
 * preview.
 *
 * @param businessId the carwash outlet the quote is for
 * @param lines the requested lines; must be non-empty
 * @param discountMinor an optional fixed discount in minor units; {@code null} for no discount —
 *     ONE input to the promotions engine (the manual-discount layer, applied last)
 * @param couponCode Phase 3 (ADR 0026): optional coupon code; case-insensitive. {@code null}/blank
 *     means no coupon.
 */
public record QuoteRequest(
    @NotNull UUID businessId,
    @NotEmpty List<@Valid TicketLineInput> lines,
    @PositiveOrZero Long discountMinor,
    String couponCode) {}
