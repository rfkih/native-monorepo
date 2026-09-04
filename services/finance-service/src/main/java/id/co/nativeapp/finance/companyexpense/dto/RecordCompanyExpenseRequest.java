package id.co.nativeapp.finance.companyexpense.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Input for {@code POST /api/v1/company-expenses} (ADR 0072). {@code kind} decides the shape:
 * GENERAL uses {@code glHint} + {@code amountMinor} (no lines); INVENTORY uses {@code lines} (the
 * amount is their sum — the server computes it, the client's total is display-only). {@code
 * occurredAt} is optional (defaults to now, server clock). The currency is always the company's
 * base currency — the server re-guards against the period's GL currency regardless.
 *
 * @param lines INVENTORY only; {@code qtyBase} is in the ingredient's BASE unit (the console
 *     converts display units before submit)
 */
public record RecordCompanyExpenseRequest(
    String kind,
    UUID businessId,
    String glHint,
    String description,
    Long amountMinor,
    String currency,
    Instant occurredAt,
    List<LineRequest> lines) {

  /** One ingredient line; {@code ingredientName} is a display snapshot (finance-side lists). */
  public record LineRequest(
      UUID ingredientId, String ingredientName, Long qtyBase, Long valueMinor) {}
}
