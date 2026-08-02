package id.co.nativeapp.employee.expense.domain;

/**
 * How an APPROVED claim is reimbursed (ADR 0030 §6, Odoo-parity two-path model): {@code DIRECT}
 * (paid immediately by a manager, AP-payment style) or {@code PAYROLL} (rides the employee's next
 * payslip as a non-taxable {@code EXPENSE_REIMBURSEMENT} earning line). The pay/link writers that
 * consume this land in a later phase; E1 only records the employee's chosen method on the claim.
 */
public enum ReimbursementMethod {
  PAYROLL,
  DIRECT
}
