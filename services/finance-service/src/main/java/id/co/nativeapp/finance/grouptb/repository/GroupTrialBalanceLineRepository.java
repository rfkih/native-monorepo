package id.co.nativeapp.finance.grouptb.repository;

import id.co.nativeapp.finance.grouptb.domain.GroupTrialBalanceLine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@link GroupTrialBalanceLine} group ingest target (P3d SEAM 2).
 *
 * <p>A thin data port: no manual {@code WHERE company_id} / {@code WHERE group_id} — tenant AND
 * group scoping come solely from the auto-applied two-GUC conjunction RLS on every
 * {@code @Transactional} method (rule 5). The consumer binds {@code (tenant = lead, group =
 * group_id)} before any call, so {@code app.current_tenant} + {@code app.current_group} are both
 * set and the policy restricts results to that group's lead-owned lines. {@link
 * #findBySourceEventIdAndGlAccountCodeAndPostingType} keys on the {@code UNIQUE} idempotency
 * backstop columns.
 */
public interface GroupTrialBalanceLineRepository
    extends JpaRepository<GroupTrialBalanceLine, UUID> {

  /** Looks up an already-ingested line by its UNIQUE idempotency key (event + account + kind). */
  Optional<GroupTrialBalanceLine> findBySourceEventIdAndGlAccountCodeAndPostingType(
      UUID sourceEventId, String glAccountCode, String postingType);

  /**
   * All ingested trial-balance lines for a {@code (group, period)} — the SEAM-3a close scans these
   * to roll up revenue/expense and to pair intercompany legs. Scoped to the bound group + lead
   * tenant by the two-GUC conjunction RLS (rule 5); {@code group_id} narrows to the one group.
   */
  List<GroupTrialBalanceLine> findByGroupIdAndPeriod(UUID groupId, String period);

  /** The distinct member companies that have ingested lines for a {@code (group, period)}. */
  @org.springframework.data.jpa.repository.Query(
      """
      SELECT DISTINCT l.memberCompanyId FROM GroupTrialBalanceLine l
       WHERE l.groupId = :groupId AND l.period = :period
      """)
  List<UUID> findMembersWithLines(
      @org.springframework.data.repository.query.Param("groupId") UUID groupId,
      @org.springframework.data.repository.query.Param("period") String period);
}
