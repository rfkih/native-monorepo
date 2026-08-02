package id.co.nativeapp.employee.expense.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One row of the manager-facing expense-claim list. */
public record ExpenseClaimSummaryResponse(
    UUID id,
    UUID employeeId,
    String employeeName,
    String status,
    long amountMinor,
    String currency,
    LocalDate expenseDate,
    String merchant,
    String categoryName,
    UUID orgUnitId,
    String reimbursementMethod,
    String decidedBy,
    Instant decidedAt,
    String decisionComment) {}
