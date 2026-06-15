package id.co.nativeapp.finance.consolidation;

import java.util.UUID;

/**
 * The outcome of one group-close attempt (P3d SEAM 3a + 3b) — the {@link ConsolidationState} the
 * close landed in, the summary row id it wrote (or superseded into), and the close run sequence.
 *
 * <p>A {@link ConsolidationState#CLOSED} result means the consolidation finalised; any other state
 * is a GATED outcome (the close held back loudly — pending members, a member trial balance that
 * does not reconcile in its functional currency, unreconciled intercompany, or a warming-up member
 * projection). A multi-currency group is SUPPORTED in 3b via the simplified, flagged translation
 * path (no longer a hold-back); a member's unbalanced native book is. {@code firstRun} is {@code
 * false} when an idempotent re-run at the same {@code close_run_seq} was a clean no-op.
 *
 * @param state the lifecycle state the close landed in
 * @param summaryId the {@code consolidation_summary} row id this close wrote, or {@code null} when
 *     an idempotent re-run made no change
 * @param closeRunSeq the close run sequence this attempt used
 * @param firstRun {@code true} on the first close at this seq, {@code false} on an idempotent
 *     re-run
 */
public record GroupCloseResult(
    ConsolidationState state, UUID summaryId, int closeRunSeq, boolean firstRun) {

  /** Whether the close finalised (the consolidation is CLOSED, not a gated hold-back). */
  public boolean isClosed() {
    return state == ConsolidationState.CLOSED;
  }
}
