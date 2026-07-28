package id.co.nativeapp.finance.ar.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * API response for an invoice detail — the header, its lines, and its payments. Money is minor
 * units + the invoice {@code currency}; {@code outstandingMinor} is {@code totalMinor − paidMinor}.
 * {@code usesIllustrativeRules} drives the console's amber "Estimated" tax badge.
 */
public record InvoiceDetailResponse(
    UUID id,
    String invoiceNumber,
    UUID customerId,
    String customerName,
    String status,
    LocalDate issueDate,
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

  /** One billed line of the invoice. */
  public record LineResponse(
      int lineNo, String description, int quantity, long unitPriceMinor, long lineTotalMinor) {}

  /** One receipt recorded against the invoice. */
  public record PaymentResponse(
      UUID id, long amountMinor, String currency, Instant paidAt, String method) {}
}
