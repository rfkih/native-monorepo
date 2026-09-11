package id.co.nativeapp.finance.ap.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything a draft bill is made of (ADR 0084) — the application-layer input {@code
 * BillController} assembles from {@code CreateBillRequest}.
 *
 * @param vendorId the billing vendor
 * @param currencyCode ISO-4217
 * @param taxBp the PPN rate in basis points (0 / 1100 / 1200) applied to the net
 * @param discountMinor the header discount in minor units (0 when none)
 * @param vendorInvoiceNumber the vendor's own invoice number, or null
 * @param billDate the invoice date, or null (the posting day is used on post)
 * @param termDays the payment term in days, or null (the post-time default)
 * @param note an internal note, or null
 * @param lines the lines (at least one)
 */
public record BillDraftInput(
    UUID vendorId,
    String currencyCode,
    int taxBp,
    long discountMinor,
    String vendorInvoiceNumber,
    LocalDate billDate,
    Integer termDays,
    String note,
    List<BillLineInput> lines) {}
