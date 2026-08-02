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
   * The event id for a (claim, idempotency-key, action) triple, if THIS EXACT transition already
   * ran — the replay-detection probe {@code ExpenseClaimWriter} runs before mutating the aggregate.
   * Returns only the id (never the full row) since the caller only needs to know whether a replay
   * is in play.
   *
   * <p><strong>Scoped by {@code action} (S1/S2, code review).</strong> The DB {@code UNIQUE
   * (company_id, claim_id, idempotency_key)} (V9) deliberately does NOT include {@code action} — a
   * client that reuses the SAME idempotency key for a DIFFERENT action on the SAME claim (e.g.
   * {@code submit} then, by mistake, {@code cancel} with the same key) must NOT be silently treated
   * as a replay of the original action; it should instead trip the unique index and surface as a
   * conflict. Probing this method WITH the action means a genuine same-action replay still
   * short-circuits cleanly, while a cross-action key reuse falls through to the INSERT, collides,
   * and is recovered (or rethrown) by {@code ExpenseClaimService}'s conflict handling.
   */
  @Query(
      value =
          "SELECT id FROM expense_claim_event WHERE claim_id = :claimId"
              + " AND idempotency_key = :idempotencyKey AND action = :action",
      nativeQuery = true)
  Optional<UUID> findIdByClaimIdAndIdempotencyKey(
      @Param("claimId") UUID claimId,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("action") String action);
}
