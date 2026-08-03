package id.co.nativeapp.finance.opening.dto;

import id.co.nativeapp.finance.opening.domain.CompanyOpeningBalance;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The opening-balance summary returned on record (201/200) and on the GET read (ADR 0037). {@code
 * plugMinor} is the signed residual credited to Opening Balance Equity (negative = a debit; zero =
 * the sheet already balanced), surfaced so the console can show it transparently.
 */
public record OpeningBalanceResponse(
    UUID id,
    UUID openingEntryId,
    LocalDate asOfDate,
    String period,
    long totalMinor,
    long plugMinor,
    String currency,
    Instant recordedAt) {

  public static OpeningBalanceResponse from(CompanyOpeningBalance row) {
    return new OpeningBalanceResponse(
        row.getId(),
        row.getOpeningEntryId(),
        row.getAsOfDate(),
        row.getPeriod(),
        row.getTotalMinor(),
        row.getPlugMinor(),
        row.getCurrency() == null ? null : row.getCurrency().strip(),
        row.getCreatedAt());
  }
}
