package id.co.nativeapp.restaurant.bill.dto;

import java.util.UUID;

/**
 * Wire shape for a bill summary on the list endpoint — header + running total + line count. No line
 * detail and no breakdown (use GET /api/v1/bills/{id} for the full view).
 *
 * @param id the bill id
 * @param businessId the originating business unit
 * @param tableId nullable; the physical table
 * @param guestLabel the guest label
 * @param status OPEN | PAID | CANCELLED
 * @param currency ISO-4217 currency code
 * @param discountMinor optional fixed discount in minor units; null when none
 * @param runningTotalMinor sum of all line totals in minor units (pre-tax/SC subtotal)
 * @param lineCount number of lines on this bill
 */
public record BillSummaryResponse(
    UUID id,
    UUID businessId,
    UUID tableId,
    String guestLabel,
    String status,
    String currency,
    Long discountMinor,
    long runningTotalMinor,
    int lineCount) {}
