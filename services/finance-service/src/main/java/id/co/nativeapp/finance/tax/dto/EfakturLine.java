package id.co.nativeapp.finance.tax.dto;

import java.time.LocalDate;

/**
 * One output tax invoice in the e-Faktur export (Phase 4 Tax / PPN). {@code dppMinor} is the tax
 * base (<em>Dasar Pengenaan Pajak</em> = invoice subtotal); {@code ppnMinor} is the output VAT — both
 * minor units. ILLUSTRATIVE flat layout, NOT the certified DJP e-Faktur schema (ADR 0017 SME gate).
 */
public record EfakturLine(
    String invoiceNumber,
    LocalDate issueDate,
    String customerName,
    String customerTaxId,
    long dppMinor,
    long ppnMinor,
    long totalMinor,
    String currency) {}
