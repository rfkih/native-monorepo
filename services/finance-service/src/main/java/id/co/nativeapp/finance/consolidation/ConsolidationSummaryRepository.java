package id.co.nativeapp.finance.consolidation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data port for the {@link ConsolidationSummary} roll-up (P3d SEAM 3a).
 *
 * <p>Tenant AND group scoping are ENFORCED by the auto-applied two-GUC conjunction RLS (rule 5).
 * The {@code group_id} predicate on each query is defense-in-depth (belt-and-braces): RLS remains
 * the enforcing layer, but an explicit {@code group_id =} narrows the scan to the one group so a
 * future non-group-bound caller cannot silently cross groups.
 */
public interface ConsolidationSummaryRepository extends JpaRepository<ConsolidationSummary, UUID> {

  /**
   * The summary for a specific close run, if it exists (idempotent re-run / no-op lookup). Carries
   * an explicit {@code group_id} predicate (defense-in-depth atop the two-GUC RLS).
   */
  @Query(
      """
      SELECT s FROM ConsolidationSummary s
       WHERE s.groupId = :groupId
         AND s.period = :period
         AND s.closeRunSeq = :closeRunSeq
      """)
  Optional<ConsolidationSummary> findByGroupIdAndPeriodAndCloseRunSeq(
      @Param("groupId") UUID groupId,
      @Param("period") String period,
      @Param("closeRunSeq") int closeRunSeq);

  /**
   * The prior ACTIVE (non-SUPERSEDED) summaries for a {@code (group, period)} that a close of
   * {@code closeRunSeq} supersedes — every row with a strictly lower {@code close_run_seq} not
   * already SUPERSEDED. These are flipped to {@link ConsolidationState#SUPERSEDED} and their
   * PRIMARY ledger entries reversed append-only. The explicit {@code group_id} predicate is
   * defense-in-depth atop the two-GUC RLS.
   */
  @Query(
      """
      SELECT s FROM ConsolidationSummary s
       WHERE s.groupId = :groupId
         AND s.period = :period
         AND s.closeRunSeq < :closeRunSeq
         AND s.state <> id.co.nativeapp.finance.consolidation.ConsolidationState.SUPERSEDED
      """)
  List<ConsolidationSummary> findActivePriorSummaries(
      @Param("groupId") UUID groupId,
      @Param("period") String period,
      @Param("closeRunSeq") int closeRunSeq);

  /**
   * Whether an ACTIVE (non-SUPERSEDED) summary with a {@code close_run_seq} greater than OR EQUAL
   * to {@code closeRunSeq} already exists for the {@code (group, period)}. Guards a re-close at a
   * seq that is not strictly higher than the current head (a stale/duplicate close attempt): the
   * close refuses to run rather than reverse a NEWER close. (At the exact same seq the idempotent
   * re-run path handles the no-op; this catches a lower-or-equal seq racing in after a higher close
   * already committed.) The explicit {@code group_id} predicate is defense-in-depth atop the
   * two-GUC RLS.
   */
  @Query(
      """
      SELECT count(s) > 0 FROM ConsolidationSummary s
       WHERE s.groupId = :groupId
         AND s.period = :period
         AND s.closeRunSeq >= :closeRunSeq
         AND s.state <> id.co.nativeapp.finance.consolidation.ConsolidationState.SUPERSEDED
      """)
  boolean existsActiveAtOrAboveSeq(
      @Param("groupId") UUID groupId,
      @Param("period") String period,
      @Param("closeRunSeq") int closeRunSeq);
}
