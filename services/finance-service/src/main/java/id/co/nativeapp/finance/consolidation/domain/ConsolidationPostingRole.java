package id.co.nativeapp.finance.consolidation.domain;

/**
 * Whether a {@link ConsolidationLedgerEntry} is an original ({@link #PRIMARY}) or a supersession
 * contra ({@link #REVERSAL}) — the consolidation analog of the labor {@code
 * id.co.nativeapp.finance.revenue.domain.PostingRole} (#23).
 *
 * <p>A {@link #REVERSAL} carries a NEGATED {@code Money} (it unwinds a prior PRIMARY's
 * contribution) and a DETERMINISTIC synthetic {@code source_entry_id} (see {@link
 * ConsolidationReversalEventIds}), so a re-run derives the same id and the UNIQUE {@code
 * source_entry_id} backstop makes the supersession idempotent.
 */
public enum ConsolidationPostingRole {
  PRIMARY,
  REVERSAL
}
