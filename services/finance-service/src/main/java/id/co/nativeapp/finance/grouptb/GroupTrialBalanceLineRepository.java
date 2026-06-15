package id.co.nativeapp.finance.grouptb;

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
}
