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

  /**
   * One ingredient line. {@code ingredientName} is the inventory item's own name (a display
   * snapshot for finance-side lists); {@code description} is what the RECEIPT calls it, sent only
   * when the vendor's wording differs — blank, absent, or equal to the ingredient name all mean
   * "same" and are stored as null.
   */
  public record LineRequest(
      UUID ingredientId, String ingredientName, Long qtyBase, Long valueMinor, String description) {

    /** A line named after its inventory item (no separate receipt wording). */
    public LineRequest(UUID ingredientId, String ingredientName, Long qtyBase, Long valueMinor) {
      this(ingredientId, ingredientName, qtyBase, valueMinor, null);
    }
  }
}
