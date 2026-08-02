package id.co.nativeapp.employee.expense.dto;

import id.co.nativeapp.employee.expense.domain.ReimbursementMethod;
import java.time.LocalDate;
import java.util.UUID;

/** The application command for creating (or, unchanged in shape, fully replacing) a claim. */
public record CreateClaimCommand(
    UUID categoryId,
    long amountMinor,
    String currency,
    LocalDate expenseDate,
    String merchant,
    String note,
    ReimbursementMethod reimbursementMethod) {}
