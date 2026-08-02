package id.co.nativeapp.employee.expense.projection;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The caller's own expense-claim list row (native-query read model, {@code
 * ExpenseClaimRepository#findMyClaims}) — snake_case column aliases mapped to camelCase accessors
 * (CODE-STRUCTURE §3.3). No PII on this resource.
 */
public interface MyExpenseClaimView {

  UUID getId();

  String getStatus();

  long getAmountMinor();

  String getAmountCurrency();

  LocalDate getExpenseDate();

  String getMerchant();

  String getCategoryName();

  String getReimbursementMethod();

  String getDecidedBy();

  Instant getDecidedAt();

  String getDecisionComment();
}
