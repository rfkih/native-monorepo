package id.co.nativeapp.employee.timeoff.repository;

import id.co.nativeapp.employee.timeoff.domain.TimeoffRequestEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the shared append-only {@link TimeoffRequestEvent} audit/idempotency
 * log (ADR 0033 §5). No manual {@code WHERE company_id} — RLS scopes it (rule 5).
 */
public interface TimeoffRequestEventRepository extends JpaRepository<TimeoffRequestEvent, UUID> {

  /**
   * The event id for a (kind, request, idempotency-key, action) quadruple, if THIS EXACT transition
   * already ran — the replay-detection probe the writers run before mutating. {@code requestKind}
   * is passed as its {@code name()} (a plain string, like every other status/enum column native
   * query elsewhere in this codebase — no native-query enum parameter binding precedent exists, so
   * this stays consistent with e.g. {@code ExpenseClaimEventRepository}'s {@code action} param).
   * Scoped by {@code action} (the expense-claim S1/S2 idiom): the DB {@code UNIQUE (company_id,
   * request_kind, request_id, idempotency_key)} deliberately does NOT include {@code action}, so a
   * same-key reuse across a DIFFERENT action on the same request falls through to the INSERT,
   * collides, and is recovered (or rethrown) by the owning {@code *Service}, never silently treated
   * as a replay of the wrong action.
   */
  @Query(
      value =
          "SELECT id FROM timeoff_request_event WHERE request_kind = :requestKind"
              + " AND request_id = :requestId AND idempotency_key = :idempotencyKey"
              + " AND action = :action",
      nativeQuery = true)
  Optional<UUID> findIdByRequestAndIdempotencyKey(
      @Param("requestKind") String requestKind,
      @Param("requestId") UUID requestId,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("action") String action);
}
