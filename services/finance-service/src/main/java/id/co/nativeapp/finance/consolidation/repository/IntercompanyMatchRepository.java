package id.co.nativeapp.finance.consolidation.repository;

import id.co.nativeapp.finance.consolidation.domain.IntercompanyMatch;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data port for the {@link IntercompanyMatch} reconciliation rows (P3d SEAM 3a).
 *
 * <p>Tenant AND group scoping come solely from the auto-applied two-GUC conjunction RLS (rule 5).
 */
public interface IntercompanyMatchRepository extends JpaRepository<IntercompanyMatch, UUID> {

  /**
   * The number of intercompany references reconciled in a close run for a {@code (group, period,
   * closeRunSeq)} — the group eliminations / intercompany-match status a group read surface badges
   * (P3d SEAM 4b). Tenant + group scoping come solely from the two-GUC conjunction RLS (rule 5);
   * the explicit predicates narrow the count to the one group/period/run (defense-in-depth).
   */
  @Query(
      value =
          """
          SELECT count(m.*) FROM intercompany_match m
           WHERE m.group_id = :groupId
             AND m.period = :period
             AND m.close_run_seq = :closeRunSeq
          """,
      nativeQuery = true)
  long countByCloseRun(
      @Param("groupId") UUID groupId,
      @Param("period") String period,
      @Param("closeRunSeq") int closeRunSeq);

  /**
   * The number of UNRECONCILED (close-BLOCKING) intercompany references in a close run — those
   * whose {@link IntercompanyMatch#getState()} is a state that holds the close back. The group read
   * surface surfaces this count (and the boolean "all reconciled?") so a UI can flag a held close,
   * NEVER a member's standalone figure. Scoped by the two-GUC RLS (rule 5) plus the explicit
   * defense-in-depth predicates.
   *
   * <p><strong>Defined by the COMPLEMENT of the NON-blocking set</strong> ({@code MATCHED}, {@code
   * OUT_OF_SCOPE_BALANCE_SHEET}, {@code MATCHED_CROSS_CURRENCY}) rather than by an explicit
   * blocking set ({@code UNMATCHED} / {@code AMOUNT_MISMATCH} / {@code MALFORMED}). This tracks
   * {@link IntercompanyMatch#blocksClose()} BY CONSTRUCTION: a FUTURE blocking state added to
   * {@link IntercompanyMatchState} counts as unreconciled here automatically, so the READ badge can
   * never report "all reconciled" on a close the WRITER held back on a state this query forgot to
   * list. The non-blocking set is the small, stable allow-list — it changes only on a deliberate
   * decision that a new state is NON-blocking, which is exactly when {@code blocksClose()} must
   * also change.
   */
  @Query(
      value =
          """
          SELECT count(m.*) FROM intercompany_match m
           WHERE m.group_id = :groupId
             AND m.period = :period
             AND m.close_run_seq = :closeRunSeq
             AND m.state NOT IN ('MATCHED', 'OUT_OF_SCOPE_BALANCE_SHEET', 'MATCHED_CROSS_CURRENCY')
          """,
      nativeQuery = true)
  long countUnreconciledByCloseRun(
      @Param("groupId") UUID groupId,
      @Param("period") String period,
      @Param("closeRunSeq") int closeRunSeq);
}
