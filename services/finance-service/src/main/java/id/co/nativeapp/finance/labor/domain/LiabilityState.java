package id.co.nativeapp.finance.labor.domain;

/**
 * The lifecycle of a {@link PayrollRunLedger} row's LIABILITY dimension (ADR 0032, Track P phase
 * P4) — tracked independently from {@link PayrollRunState} (which stays the pre-existing
 * LaborCostPostingWriter/PayrollReconciliationWriter reconciliation lifecycle). {@code NULL}
 * (represented as this field being absent on the row, not a member of this enum) means no {@code
 * PayrollLiabilitiesPosted} has landed for the run yet.
 *
 * <p>Two independent lifecycles avoid a cross-writer coordination gap: if the pre-existing {@code
 * state} column were shared, a prior run flipped to {@code SUPERSEDED} by {@code
 * LaborCostPostingWriter} (because ITS bucket for the higher-seq run arrived first) would make the
 * liability writer's own supersession scan blind to that prior run's liability entry, which would
 * then never be reversed.
 */
public enum LiabilityState {
  /** A liability entry was booked for this run and is the ACTIVE one for its (period, run_type). */
  POSTED,
  /**
   * A higher {@code run_seq} of the SAME {@code run_type} superseded this run's liability entry.
   */
  SUPERSEDED
}
