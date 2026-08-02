package id.co.nativeapp.employee.expense.projection;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The manager-facing expense-claim list row (native-query read model, {@code
 * ExpenseClaimRepository#findForManager}) — joins the claiming employee's plain {@code full_name}
 * (not PII-encrypted) and the category name, snake_case aliases mapped to camelCase accessors
 * (CODE-STRUCTURE §3.3).
 */
public interface ExpenseClaimSummaryView {

  UUID getId();

  UUID getEmployeeId();

  String getEmployeeName();

  String getStatus();

  long getAmountMinor();

  String getAmountCurrency();

  LocalDate getExpenseDate();

  String getMerchant();

  String getCategoryName();

  UUID getOrgUnitId();

  String getReimbursementMethod();

  String getDecidedBy();

  Instant getDecidedAt();

  String getDecisionComment();
}
