package id.co.nativeapp.employee.expense.repository;

import id.co.nativeapp.employee.expense.domain.ExpenseClaimEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the append-only {@link ExpenseClaimEvent} audit/idempotency log.
 *
 * <p>A thin data port: no business logic, no manual {@code WHERE company_id} — tenant scoping comes
 * solely from the auto-applied RLS GUC (rule 5).
 */
public interface ExpenseClaimEventRepository extends JpaRepository<ExpenseClaimEvent, UUID> {

  /**
   * The event id for a (claim, idempotency-key) pair, if a transition already used this exact key —
   * the replay-detection probe {@code ExpenseClaimWriter} runs before mutating the aggregate.
   * Returns only the id (never the full row) since the caller only needs to know whether a replay
   * is in play.
   */
  @Query(
      value =
          "SELECT id FROM expense_claim_event WHERE claim_id = :claimId"
              + " AND idempotency_key = :idempotencyKey",
      nativeQuery = true)
  Optional<UUID> findIdByClaimIdAndIdempotencyKey(
      @Param("claimId") UUID claimId, @Param("idempotencyKey") String idempotencyKey);
}
