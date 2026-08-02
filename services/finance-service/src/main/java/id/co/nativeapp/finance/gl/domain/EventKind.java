package id.co.nativeapp.finance.gl.domain;

/**
 * The event kind driving an auto-posting — used as the key into the {@code posting_template}
 * reference table. Values align with the event names in the event catalog and the seeds in V13.
 */
public enum EventKind {
  SALE,
  EXPENSE,
  LABOR,
  /** Full reversal of a captured sale before settlement — contra of {@link #SALE}. */
  SALE_VOID,
  /**
   * Partial or full refund of a captured sale after settlement — proportional contra of {@link
   * #SALE}.
   */
  SALE_REFUND,
  /**
   * Phase 1 AR: a customer invoice was issued — Dr AR / Cr revenue (+ Cr output VAT for a taxable
   * invoice).
   */
  INVOICE_ISSUED,
  /** Phase 1 AR: a payment was received against a customer invoice — Dr cash-clearing / Cr AR. */
  PAYMENT_RECEIVED,
  /** Phase 1 AR: a customer invoice was voided — the contra of {@link #INVOICE_ISSUED}. */
  INVOICE_VOID,
  /**
   * Phase 2 AP: a vendor bill was posted — Dr expense (net) / Dr input VAT (tax) / Cr AP (total).
   */
  BILL_POSTED,
  /** Phase 2 AP: a payment was made against a vendor bill — Dr AP / Cr cash-clearing. */
  BILL_PAYMENT_MADE,
  /** Phase 2 AP: a vendor bill was voided — the contra of {@link #BILL_POSTED}. */
  BILL_VOID,
  /**
   * Phase 4 loyalty/gift cards (ADR 0027): a gift card was sold or topped up ({@code GiftCardSold})
   * — a LIABILITY event, never revenue: Dr &lt;tender clearing&gt; / Cr {@link
   * AccountRole#GIFT_CARD_LIABILITY} (both {@code GROSS}, V37 seed).
   */
  GIFT_CARD_SALE,
  /**
   * Expense-claims program (ADR 0030): a manager APPROVED an employee expense claim — recognition
   * at approval: Dr {@link AccountRole#EXPENSE} (generic role; the claim's {@code gl_hint} drives
   * only the dimensional {@code ledger_posting}, resolved separately) / Cr {@link
   * AccountRole#EMPLOYEE_EXPENSE_PAYABLE} (both {@code GROSS}, V39 seed).
   */
  EXPENSE_CLAIM_APPROVED,
  /**
   * Expense-claims program (ADR 0030): an APPROVED, un-settled claim was VOIDED — the exact contra
   * of {@link #EXPENSE_CLAIM_APPROVED}: Dr {@link AccountRole#EMPLOYEE_EXPENSE_PAYABLE} / Cr {@link
   * AccountRole#EXPENSE} (both {@code GROSS}, V39 seed).
   */
  EXPENSE_CLAIM_VOID,
  /**
   * Expense-claims program (ADR 0030): an APPROVED claim was reimbursed (DIRECT pay or a POSTED
   * payroll run) — a balance-sheet-only move, the expense having already been recognised at
   * approval: Dr {@link AccountRole#EMPLOYEE_EXPENSE_PAYABLE} / Cr {@link
   * AccountRole#CASH_CLEARING} (both {@code GROSS}, V39 seed). Settle-once guarded by {@code
   * employee_expense_claim_ledger}.
   */
  EXPENSE_CLAIM_SETTLED
}
