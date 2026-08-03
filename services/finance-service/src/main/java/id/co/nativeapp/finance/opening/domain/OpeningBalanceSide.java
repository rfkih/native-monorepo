package id.co.nativeapp.finance.opening.domain;

/**
 * Which side of the ledger an opening-balance line posts to (ADR 0037). An ASSET carrying value is
 * a {@code DEBIT}; a LIABILITY or EQUITY carrying value is a {@code CREDIT}. The writer validates
 * that the account's type is consistent with normal balances only implicitly — it posts exactly the
 * side the caller states and lets the auto-plug to Opening Balance Equity absorb any residual.
 */
public enum OpeningBalanceSide {
  DEBIT,
  CREDIT
}
