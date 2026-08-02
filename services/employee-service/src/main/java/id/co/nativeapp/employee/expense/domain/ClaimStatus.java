package id.co.nativeapp.employee.expense.domain;

/**
 * The {@code expense_claim} lifecycle (ADR 0030 §5, the AP-Bill idiom): {@code DRAFT -> SUBMITTED
 * -> APPROVED | REFUSED}; cancel from {@code DRAFT}/{@code SUBMITTED}; {@code APPROVED -> VOIDED}
 * only while un-settled and un-linked; {@code APPROVED -> REIMBURSED} via a DIRECT pay or a POSTED
 * payroll run. Phase E1 wires the guarded transitions into {@code DRAFT}, {@code SUBMITTED}, {@code
 * APPROVED}, {@code REFUSED}, and {@code CANCELLED}; {@code VOIDED} and {@code REIMBURSED} are
 * declared here (and in the {@code chk_expense_claim_status} constraint) so later phases need no
 * further migration, but no method in this phase can reach them yet.
 */
public enum ClaimStatus {
  DRAFT,
  SUBMITTED,
  APPROVED,
  REFUSED,
  CANCELLED,
  VOIDED,
  REIMBURSED
}
