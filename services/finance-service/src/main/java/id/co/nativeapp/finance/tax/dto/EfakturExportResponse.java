package id.co.nativeapp.finance.tax.dto;

import java.util.List;

/**
 * The e-Faktur export for a period (Phase 4 Tax / PPN): the output tax invoices issued in the
 * period. The console renders this to a CSV download (client-side, like the other finance exports).
 * ILLUSTRATIVE — NOT the certified DJP e-Faktur import format (ADR 0017 SME gate).
 */
public record EfakturExportResponse(String period, List<EfakturLine> lines) {}
