package id.co.nativeapp.finance.ap.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * API response for a bill detail — the header, its lines, and its payments. Money is minor units +
 * the bill {@code currency}; {@code outstandingMinor} is {@code totalMinor − paidMinor}. {@code
 * usesIllustrativeRules} drives the console's amber "Estimated" tax badge.
 */
public record BillDetailResponse(
    UUID id,
    String billNumber,
    UUID vendorId,
    String vendorName,
    String status,
    LocalDate billDate,
    LocalDate dueDate,
    String currency,
    long subtotalMinor,
    long taxMinor,
    long totalMinor,
    long paidMinor,
    long outstandingMinor,
    boolean usesIllustrativeRules,
    List<LineResponse> lines,
    List<PaymentResponse> payments) {

  /**
   * One billed line of the bill. {@code inventory} + the ingredient triple surface the ADR 0072
   * linkage (ingredient fields null for a plain or unlinked line).
   */
  public record LineResponse(
      int lineNo,
      String description,
      int quantity,
      long unitPriceMinor,
      long lineTotalMinor,
      boolean inventory,
      UUID ingredientId,
      String ingredientName,
      Long ingredientQtyBase) {}

  /** One payment recorded against the bill. */
  public record PaymentResponse(
      UUID id, long amountMinor, String currency, Instant paidAt, String method) {}
}
