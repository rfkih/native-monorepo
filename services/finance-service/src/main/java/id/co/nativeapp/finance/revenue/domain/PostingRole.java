package id.co.nativeapp.finance.revenue.domain;

/**
 * The role of a {@link LedgerPosting} within a payroll run (#23). Distinguishes an original posting
 * from a supersession contra entry so a run is reversible and the reversal is auditable.
 *
 * <p>Every REVENUE / {@code ExpenseRecorded} posting is {@link #PRIMARY}. A labor posting is {@link
 * #PRIMARY} when the run's cost first lands; when a higher-{@code run_seq} run supersedes a prior
 * run, finance posts one {@link #REVERSAL} per prior PRIMARY posting (same outlet/gl/period, amount
 * negated) so the prior run's P&amp;L contribution is unwound APPEND-ONLY — the contra row, not a
 * destructive update, undoes the figure. Persisted as its name via {@code EnumType.STRING}.
 */
public enum PostingRole {
  /** An original posting (revenue, expense, or a labor run's first landing). */
  PRIMARY,
  /** A supersession contra posting that negates a superseded prior run's PRIMARY posting. */
  REVERSAL
}
