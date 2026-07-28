package id.co.nativeapp.finance.unitpnl.projection;

import java.util.UUID;

/**
 * One org-unit row of the per-unit P&amp;L rollup query (the requested unit itself OR one of its
 * child outlets), with two pre-aggregated legs: NET revenue from the {@code outlet_revenue}
 * accumulator and expenses (signed) from the dimensional ledger. Snake_case aliases map to these
 * camelCase getters (CLAUDE.md native-query convention). Reachable only from the service +
 * repository layers (ArchUnit projection rule).
 */
public interface UnitPnlRowView {

  UUID getOrgUnitId();

  String getName();

  /**
   * The org-unit kind as stored in {@code org_unit_ref} — the event-published value ({@code
   * OrgUnitCreatedSchema} emits the enum NAME, uppercase). Compare case-insensitively.
   */
  String getType();

  boolean getActive();

  /** NET revenue (subtotal − discount, reversals already netted by the accumulator). */
  long getRevenueMinor();

  /** Signed expense sum from the dimensional ledger (labor REVERSAL rows net PRIMARY). */
  long getExpenseMinor();

  Boolean getUsesIllustrativeRules();

  /** The revenue leg's currency, or {@code null} when the node has no revenue this period. */
  String getRevenueCurrency();

  /** The expense leg's currency, or {@code null} when the node has no expense postings. */
  String getExpenseCurrency();

  /** Distinct revenue currencies for this node — the reader fails loud when &gt; 1 (M1.5). */
  long getRevenueCurrencyCount();

  /** Distinct expense currencies for this node — the reader fails loud when &gt; 1 (M1.5). */
  long getExpenseCurrencyCount();
}
