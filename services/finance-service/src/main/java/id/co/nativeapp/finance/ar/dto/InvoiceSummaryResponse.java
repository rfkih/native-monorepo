package id.co.nativeapp.finance.ar.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * API response for one row of the invoice list. Money is minor units + the invoice {@code
 * currency}; {@code outstandingMinor} is {@code totalMinor − paidMinor}.
 */
public record InvoiceSummaryResponse(
    UUID id,
    String invoiceNumber,
    UUID customerId,
    String customerName,
    String status,
    LocalDate issueDate,
    LocalDate dueDate,
    String currency,
    long totalMinor,
    long paidMinor,
    long outstandingMinor) {}
