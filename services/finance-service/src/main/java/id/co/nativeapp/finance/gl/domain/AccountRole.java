package id.co.nativeapp.finance.gl.domain;

/**
 * A semantic role used in a {@link PostingTemplate} line, resolved to a concrete {@code
 * chart_of_account.account_code} via the {@code role_account_map} reference data. Roles are
 * SME-pluggable: an accountant seeds a new {@code role_account_map} row pointing an {@code
 * AccountRole} to the correct account for their jurisdiction without touching Java code.
 *
 * <p>The illustrative defaults seeded in V13/V15/V17 use a minimal set of roles sufficient for an
 * end-to-end balanced journal for each {@link EventKind}. The full Indonesian COA roles ({@code
 * TAX_PAYABLE}, {@code AR}, {@code AP}, etc.) are SME-gated.
 *
 * <p><strong>Phase 2 additions (pricing: tax + service charge + discount):</strong>
 *
 * <ul>
 *   <li>{@code GROSS_REVENUE} — the gross revenue credit line (= subtotal, before discount). Maps
 *       to the same revenue account as {@code REVENUE} in the illustrative seed.
 *   <li>{@code SALES_DISCOUNT} — the debit line for an order-level discount (contra-revenue). SME
 *       must confirm whether this is a contra-revenue account or a separate expense.
 *   <li>{@code SERVICE_CHARGE_REVENUE} — the credit line for the service charge when treated as
 *       revenue. SME NOTE: service charge may instead be a tip-pool liability; in that case, {@code
 *       SERVICE_CHARGE_PAYABLE} is the correct credit (defined but unused until SME confirmation).
 *   <li>{@code TAX_PAYABLE} — the credit line for the restaurant tax (PB1 / PPN regime is
 *       SME-gated; ILLUSTRATIVE placeholder seeded at ~10%).
 *   <li>{@code SERVICE_CHARGE_PAYABLE} — defined but UNUSED in Phase 2 posting templates; reserved
 *       for the SME-confirmed tip-pool-as-liability treatment.
 * </ul>
 */
public enum AccountRole {
  CASH_CLEARING,
  /** Illustrative clearing account for QRIS (QR-code) digital payments (ADR 0006, slice 2). */
  QRIS_CLEARING,
  /** Illustrative clearing account for CARD (debit/credit) payments (ADR 0006, slice 2). */
  CARD_CLEARING,
  REVENUE,
  /**
   * Phase 2: the gross revenue credit line (= subtotal, before discount). The debit of
   * SALES_DISCOUNT is the contra. Maps to the revenue account in the illustrative seed.
   */
  GROSS_REVENUE,
  /**
   * Phase 2: order-level discount (contra-revenue debit). SME must confirm account classification.
   * ILLUSTRATIVE placeholder maps to account 4010.
   */
  SALES_DISCOUNT,
  /**
   * Phase 2: service charge treated as revenue (credit). SME-gated: may be {@code
   * SERVICE_CHARGE_PAYABLE} if treated as a tip-pool liability. ILLUSTRATIVE maps to account 4020.
   */
  SERVICE_CHARGE_REVENUE,
  /**
   * Phase 2: tax payable credit (PB1 / PPN regime is SME-gated; ILLUSTRATIVE 10%). Maps to account
   * 2100 (ILLUSTRATIVE).
   */
  TAX_PAYABLE,
  /**
   * Phase 2: service charge as liability (tip pool). DEFINED but UNUSED in Phase 2 templates;
   * reserved for SME-confirmed tip-pool treatment. Maps to account 2110 (ILLUSTRATIVE).
   */
  SERVICE_CHARGE_PAYABLE,
  /**
   * Phase 1 AR: the Accounts Receivable control account. Debited when a customer invoice is issued
   * (the amount the customer owes), credited when a payment is received. Maps to account 1200
   * (ILLUSTRATIVE — SME-gated).
   */
  AR,
  /**
   * Phase 1 AR: output VAT payable — the credit for the tax leg of an issued customer invoice. The
   * PPN regime + rate are SME-gated (ILLUSTRATIVE placeholder computed in {@code InvoiceWriter}).
   * Maps to account 2200 (ILLUSTRATIVE).
   */
  VAT_OUTPUT,
  /**
   * Phase 2 AP: the Accounts Payable control account. Credited when a vendor bill is posted (the
   * amount owed to the vendor), debited when the bill is paid. Maps to account 2000 (ILLUSTRATIVE —
   * SME-gated).
   */
  AP,
  /**
   * Phase 2 AP: recoverable input VAT — the debit for the tax leg of a posted vendor bill (an
   * asset, VAT receivable from the tax authority). PPN regime + rate are SME-gated (ILLUSTRATIVE
   * placeholder computed in {@code BillWriter}). Maps to account 1300 (ILLUSTRATIVE).
   */
  VAT_INPUT,
  EXPENSE,
  LABOR_EXPENSE,
  LABOR_CLEARING,
  SUSPENSE
}
