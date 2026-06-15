package id.co.nativeapp.finance.mapping.domain;

/**
 * The classic five account classes of a {@code chart_of_account}. Reference data seeded by Flyway
 * (V2); the {@code account_type} column carries one of these names, constrained by a {@code CHECK}.
 * REVENUE and EXPENSE drive the P&amp;L revenue-vs-expense split; the others exist so the chart
 * generalises to a full ledger (balance-sheet accounts) without a schema change.
 */
public enum AccountType {
  ASSET,
  LIABILITY,
  EQUITY,
  REVENUE,
  EXPENSE
}
