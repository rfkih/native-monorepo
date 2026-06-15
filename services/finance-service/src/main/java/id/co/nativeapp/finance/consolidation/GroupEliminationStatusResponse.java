package id.co.nativeapp.finance.consolidation;

/**
 * The group eliminations / intercompany-match status for a (group, period) close run (P3d SEAM 4b)
 * — a COUNT-only summary so a UI can badge "all intercompany reconciled" or "N references blocked",
 * WITHOUT exposing any member's standalone leg.
 *
 * <p>Assembled solely from {@code intercompany_match} aggregate counts under the bound group scope.
 * It carries NO member company id, NO member leg amount, NO member trial balance — only the group's
 * own reconciliation tallies. {@code allReconciled} is {@code true} when no reference is in a
 * blocking state ({@code UNMATCHED} / {@code AMOUNT_MISMATCH} / {@code MALFORMED}).
 *
 * @param totalReferences the number of intercompany references reconciled in the close run
 * @param unreconciledReferences the number in a blocking (held) state
 * @param allReconciled {@code true} iff {@code unreconciledReferences == 0}
 */
public record GroupEliminationStatusResponse(
    long totalReferences, long unreconciledReferences, boolean allReconciled) {

  /** Builds the status from the close-run match tallies. */
  public static GroupEliminationStatusResponse of(
      long totalReferences, long unreconciledReferences) {
    return new GroupEliminationStatusResponse(
        totalReferences, unreconciledReferences, unreconciledReferences == 0);
  }
}
