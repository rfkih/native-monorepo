package id.co.nativeapp.employee.expense.dto;

import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The full detail response for one expense claim (never the {@code @Entity} itself). */
public record ExpenseClaimResponse(
    UUID id,
    UUID employeeId,
    UUID categoryId,
    UUID orgUnitId,
    String status,
    long amountMinor,
    String currency,
    LocalDate expenseDate,
    String merchant,
    String note,
    String reimbursementMethod,
    UUID reimbursementRunId,
    Instant settledAt,
    Instant approvedAt,
    String decidedBy,
    Instant decidedAt,
    String decisionComment) {

  public static ExpenseClaimResponse from(ExpenseClaim claim) {
    return new ExpenseClaimResponse(
        claim.getId(),
        claim.getEmployeeId(),
        claim.getCategoryId(),
        claim.getOrgUnitId(),
        claim.getStatus().name(),
        claim.getAmount().amountMinor(),
        claim.getAmount().currency().getCurrencyCode(),
        claim.getExpenseDate(),
        claim.getMerchant(),
        claim.getNote(),
        claim.getReimbursementMethod().name(),
        claim.getReimbursementRunId(),
        claim.getSettledAt(),
        claim.getApprovedAt(),
        claim.getDecidedBy(),
        claim.getDecidedAt(),
        claim.getDecisionComment());
  }
}
