package id.co.nativeapp.finance.bank.domain;

/**
 * The contra category a {@link BankStatementLine} is reconciled against (Phase 3 bank
 * reconciliation, ADR 0016). Each category resolves to a different GL contra account via {@link
 * id.co.nativeapp.finance.gl.service.RoleAccountResolver} and is valid only for a matching
 * statement-line direction:
 *
 * <ul>
 *   <li>{@link #CLEARING} — sweeps the amount from cash-in-transit ({@code CASH_CLEARING}, 1900)
 *       into the bank. Valid for BOTH a deposit and a withdrawal.
 *   <li>{@link #INTEREST} — bank-paid interest ({@code INTEREST_INCOME}, 4100). Valid ONLY for a
 *       deposit (a positive amount).
 *   <li>{@link #BANK_FEE} — a bank-charged fee ({@code BANK_CHARGES}, 5400). Valid ONLY for a
 *       withdrawal (a negative amount).
 *   <li>{@link #QRIS_CLEARING} — settles a QRIS payout ({@code QRIS_CLEARING}, 1901) into the bank,
 *       carrying an OPTIONAL acquirer MDR fee leg ({@code QRIS_FEE_EXPENSE}, 5720 — ADR 0045).
 *       Valid ONLY for a deposit (a positive amount) — a QRIS settlement is always money coming IN.
 * </ul>
 */
public enum ReconciliationCategory {
  CLEARING,
  BANK_FEE,
  INTEREST,
  QRIS_CLEARING
}
