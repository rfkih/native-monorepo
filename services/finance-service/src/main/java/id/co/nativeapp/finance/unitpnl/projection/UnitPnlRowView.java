package id.co.nativeapp.finance.unitpnl.projection;

import java.util.UUID;

/**
 * One org-unit row of the per-unit P&amp;L rollup query (the requested unit itself OR one of its
 * child outlets), with signed sums over its {@code ledger_posting} rows for the period. Snake_case
 * aliases map to these camelCase getters (CLAUDE.md native-query convention). Reachable only from
 * the service + repository layers (ArchUnit projection rule).
 */
public interface UnitPnlRowView {

  UUID getOrgUnitId();

  String getName();

  /** The org-unit kind as stored in {@code org_unit_ref} — LOWERCASE, event-published values. */
  String getType();

  boolean getActive();

  long getRevenueMinor();

  long getExpenseMinor();

  Boolean getUsesIllustrativeRules();

  /** The (single) posting currency for this node, or {@code null} when it has no postings. */
  String getCurrency();

  /** Distinct posting currencies for this node — the reader fails loud when &gt; 1 (M1.5). */
  long getCurrencyCount();
}
