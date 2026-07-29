package id.co.nativeapp.finance.ap.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * API response for the AP aging report as of a date. Outstanding balances are bucketed by
 * days-overdue vs {@code asOf}: current (not yet due), 1–30, 31–60, 61–90, and 90+ days overdue.
 * Per the single-currency slice, all rows share one {@code currency} (the company base currency);
 * {@code currency} is {@code null} only when there is nothing outstanding.
 */
public record AgingResponse(
    LocalDate asOf, String currency, List<AgingRow> rows, AgingTotals totals) {

  /** One vendor's aged outstanding balance. {@code outstandingMinor} is the sum of the buckets. */
  public record AgingRow(
      UUID vendorId,
      String vendorName,
      long currentMinor,
      long overdue1To30Minor,
      long overdue31To60Minor,
      long overdue61To90Minor,
      long overdue90PlusMinor,
      long outstandingMinor) {}

  /** The column totals across all vendors. */
  public record AgingTotals(
      long currentMinor,
      long overdue1To30Minor,
      long overdue31To60Minor,
      long overdue61To90Minor,
      long overdue90PlusMinor,
      long outstandingMinor) {}
}
