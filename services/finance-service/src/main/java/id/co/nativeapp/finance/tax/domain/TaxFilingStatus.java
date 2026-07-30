package id.co.nativeapp.finance.tax.domain;

/**
 * The lifecycle state of a {@link TaxFiling} (Phase 4 Tax / PPN, ADR 0017).
 *
 * <ul>
 *   <li>{@code FILED} — the return has been filed: the period-end netting entry (Dr VAT_OUTPUT / Cr
 *       VAT_INPUT / net → VAT_PAYABLE or VAT_CREDIT_CARRYFORWARD) is posted and the period is
 *       sealed.
 *   <li>{@code SETTLED} — a net-PAYABLE return's balance has been paid to the tax authority (Dr
 *       VAT_PAYABLE / Cr CASH_CLEARING). Terminal.
 * </ul>
 *
 * <p>A CREDITABLE (or zero-net) return is terminal at {@code FILED} — there is nothing to settle.
 */
public enum TaxFilingStatus {
  FILED,
  SETTLED
}
