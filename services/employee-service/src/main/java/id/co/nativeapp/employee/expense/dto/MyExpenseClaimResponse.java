package id.co.nativeapp.employee.expense.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One row of the caller's own expense-claim list. */
public record MyExpenseClaimResponse(
    UUID id,
    String status,
    long amountMinor,
    String currency,
    LocalDate expenseDate,
    String merchant,
    String categoryName,
    String reimbursementMethod,
    String decidedBy,
    Instant decidedAt,
    String decisionComment) {}
