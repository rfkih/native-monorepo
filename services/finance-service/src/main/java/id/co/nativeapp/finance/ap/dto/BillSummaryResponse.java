package id.co.nativeapp.finance.ap.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * API response for one row of the bill list. Money is minor units + the bill {@code currency};
 * {@code outstandingMinor} is {@code totalMinor − paidMinor}.
 */
public record BillSummaryResponse(
    UUID id,
    String billNumber,
    UUID vendorId,
    String vendorName,
    String status,
    LocalDate billDate,
    LocalDate dueDate,
    String currency,
    long totalMinor,
    long paidMinor,
    long outstandingMinor) {}
