package id.co.nativeapp.restaurant.bill.dto;

import id.co.nativeapp.restaurant.order.dto.PriceBreakdownResponse;
import java.util.List;
import java.util.UUID;

/**
 * Wire shape for a bill (full detail: header + lines + modifiers + live price breakdown).
 *
 * <p>The {@code breakdown} is null on the list endpoint (which uses {@link BillSummaryResponse})
 * and non-null on the single-bill GET and on every write response.
 *
 * @param id the bill id
 * @param businessId the originating business unit
 * @param tableId nullable; the physical table (dine-in only)
 * @param guestLabel the guest label
 * @param status OPEN | PAID | CANCELLED
 * @param currency ISO-4217 currency code
 * @param discountMinor optional fixed discount in minor units; null when none
 * @param saleId set only when status = PAID
 * @param lines the bill lines with their modifiers
 * @param breakdown the live price breakdown (null on list response)
 */
public record BillResponse(
    UUID id,
    UUID businessId,
    UUID tableId,
    String guestLabel,
    String status,
    String currency,
    Long discountMinor,
    UUID saleId,
    List<BillLineResponse> lines,
    PriceBreakdownResponse breakdown) {}
