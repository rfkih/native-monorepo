package id.co.nativeapp.employee.expense.projection;

import java.util.UUID;

/**
 * One employee's aggregated PAYROLL-linked expense-claim total for a payroll run (ADR 0030 §6,
 * Phase E5 — {@code ExpenseClaimRepository#findLinkedClaimTotalsByEmployee}). Track P Phase P7
 * consumes this to build the non-taxable {@code EXPENSE_REIMBURSEMENT} payslip line per employee.
 *
 * <p>Grouped by {@code (employee_id, currency)}: v1 claims are single-currency per tenant, so in
 * practice exactly one row per employee — the currency grouping guards a hypothetical future
 * multi-currency tenant from silently summing mixed currencies into one corrupted total (money is
 * never mixed across currencies, rule 8); it would instead surface as multiple rows.
 */
public interface LinkedClaimTotalView {

  UUID getEmployeeId();

  long getTotalMinor();

  String getCurrency();

  long getClaimCount();
}
