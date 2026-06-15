package id.co.nativeapp.finance.consolidation;

import java.util.UUID;

/**
 * The group-close command result DTO (P3d SEAM 4b) — the outcome of a triggered close: the {@link
 * ConsolidationState} it landed in (CLOSED, or a gated hold-back such as {@code PENDING_MEMBERS} /
 * {@code PENDING_MEMBERS_WARMING_UP} / {@code INTERCOMPANY_UNRECONCILED} / {@code
 * MEMBER_TRIAL_BALANCE_UNBALANCED}), the close run sequence, and whether this run finalised the
 * consolidation.
 *
 * <p>Group-level result ONLY — it never exposes which member held the close back beyond the COARSE
 * state (a held state names the GATE, never a member's standalone figures). {@code firstRun} is
 * {@code false} for an idempotent re-run at the same {@code closeRunSeq} that made no change.
 *
 * @param groupId the consolidation group
 * @param period the accounting period {@code YYYY-MM}
 * @param state the lifecycle state the close landed in
 * @param closeRunSeq the close run sequence this attempt used
 * @param closed {@code true} iff the close finalised (CLOSED), not a gated hold-back
 * @param firstRun {@code true} on the first close at this seq, {@code false} on an idempotent
 *     re-run
 */
public record GroupCloseResponse(
    UUID groupId,
    String period,
    ConsolidationState state,
    int closeRunSeq,
    boolean closed,
    boolean firstRun) {

  /** Projects a {@link GroupCloseResult} (plus the group/period context) into the command DTO. */
  public static GroupCloseResponse from(UUID groupId, String period, GroupCloseResult result) {
    return new GroupCloseResponse(
        groupId,
        period,
        result.state(),
        result.closeRunSeq(),
        result.isClosed(),
        result.firstRun());
  }
}
