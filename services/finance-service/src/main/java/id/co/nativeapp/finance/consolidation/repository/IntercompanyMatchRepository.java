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
      """
      SELECT count(m) FROM IntercompanyMatch m
       WHERE m.groupId = :groupId
         AND m.period = :period
         AND m.closeRunSeq = :closeRunSeq
      """)
  long countByCloseRun(
      @Param("groupId") UUID groupId,
      @Param("period") String period,
      @Param("closeRunSeq") int closeRunSeq);

  /**
   * The number of UNRECONCILED intercompany references in a close run — those whose {@link
   * IntercompanyMatch#getState()} is a BLOCKING state ({@code UNMATCHED} / {@code AMOUNT_MISMATCH}
   * / {@code MALFORMED}). The group read surface surfaces this count (and the boolean "all
   * reconciled?") so a UI can flag a held close, NEVER a member's standalone figure. Scoped by the
   * two-GUC RLS (rule 5) plus the explicit defense-in-depth predicates.
   */
  @Query(
      """
      SELECT count(m) FROM IntercompanyMatch m
       WHERE m.groupId = :groupId
         AND m.period = :period
         AND m.closeRunSeq = :closeRunSeq
         AND m.state IN (
           id.co.nativeapp.finance.consolidation.domain.IntercompanyMatchState.UNMATCHED,
           id.co.nativeapp.finance.consolidation.domain.IntercompanyMatchState.AMOUNT_MISMATCH,
           id.co.nativeapp.finance.consolidation.domain.IntercompanyMatchState.MALFORMED)
      """)
  long countUnreconciledByCloseRun(
      @Param("groupId") UUID groupId,
      @Param("period") String period,
      @Param("closeRunSeq") int closeRunSeq);
}
