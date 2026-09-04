package id.co.nativeapp.finance.companyexpense.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A recorded company expense, as the console reads it (detail = summary + lines). */
public record CompanyExpenseResponse(
    UUID id,
    String expenseNo,
    String kind,
    UUID businessId,
    String glHint,
    String description,
    long amountMinor,
    String currency,
    Instant occurredAt,
    String status,
    List<LineResponse> lines) {

  /**
   * One ingredient line of an INVENTORY expense (empty list for GENERAL). {@code description} is
   * the receipt wording when it differs from {@code ingredientName}, else {@code null}.
   */
  public record LineResponse(
      UUID id,
      int lineNo,
      UUID ingredientId,
      String ingredientName,
      long qtyBase,
      long valueMinor,
      String description) {}
}
