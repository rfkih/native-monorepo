package id.co.nativeapp.finance.consolidation;

/**
 * The kind of a {@link ConsolidationLedgerEntry} (P3d SEAM 3a).
 *
 * <ul>
 *   <li>{@link #ELIMINATION} — an intercompany contra that nets a matched related-party trade to
 *       zero in the consolidated books (a member's intercompany revenue is cancelled against the
 *       counterparty member's intercompany expense, so internal trade does not inflate the group).
 *   <li>{@link #ADJUSTMENT} — a manual consolidation adjustment. Modelled for completeness; SEAM
 *       3a's automatic pass posts only ELIMINATIONs (and their REVERSALs), so no ADJUSTMENT is
 *       produced here, but the entry_type and the reversal machinery generalise to it.
 *   <li>{@link #REVERSAL} — a supersession contra negating a prior PRIMARY entry append-only when a
 *       higher {@code close_run_seq} re-closes the period (#23 analog).
 * </ul>
 */
public enum ConsolidationEntryType {
  ELIMINATION,
  ADJUSTMENT,
  REVERSAL
}
