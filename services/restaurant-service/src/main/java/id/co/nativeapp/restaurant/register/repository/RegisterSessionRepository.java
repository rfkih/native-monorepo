package id.co.nativeapp.restaurant.register.repository;

import id.co.nativeapp.restaurant.register.domain.RegisterSession;
import id.co.nativeapp.restaurant.register.projection.RegisterSessionView;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link RegisterSession} (ADR 0036).
 *
 * <p>No manual {@code WHERE company_id} — the RLS policy applies the tenant scope (rule 5). Read
 * paths return the narrow {@link RegisterSessionView} projection via native queries; the write path
 * loads the full aggregate (with a pessimistic lock at close — the double-close race is decided at
 * the row, the one-shot status transition + the close-key unique are the backstops).
 */
public interface RegisterSessionRepository extends JpaRepository<RegisterSession, UUID> {

  /** Loads the aggregate FOR UPDATE — the close computation runs under this row lock. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<RegisterSession> findWithLockById(UUID id);

  // Interface fields are implicitly public static final — the shared projection column list.
  String VIEW_COLUMNS =
      """
      SELECT s.id                  AS id,
             s.business_id         AS business_id,
             s.status              AS status,
             s.business_date       AS business_date,
             s.opened_at           AS opened_at,
             s.opening_float_minor AS opening_float_minor,
             s.currency            AS currency,
             s.closed_at           AS closed_at,
             s.cash_sales_minor    AS cash_sales_minor,
             s.cash_refunds_minor  AS cash_refunds_minor,
             s.expected_cash_minor AS expected_cash_minor,
             s.counted_cash_minor  AS counted_cash_minor,
             s.over_short_minor    AS over_short_minor
        FROM cash_register_session s
      """;

  /** The outlet's OPEN session, if any (at most one — partial unique). */
  @Query(
      value = VIEW_COLUMNS + " WHERE s.business_id = :businessId AND s.status = 'OPEN'",
      nativeQuery = true)
  Optional<RegisterSessionView> findOpenViewByBusinessId(@Param("businessId") UUID businessId);

  /** Open-replay probe: the session previously opened under this idempotency key. */
  @Query(value = VIEW_COLUMNS + " WHERE s.open_idempotency_key = :key", nativeQuery = true)
  Optional<RegisterSessionView> findViewByOpenIdempotencyKey(@Param("key") String key);

  /** Close-replay probe: the session previously CLOSED under this idempotency key. */
  @Query(value = VIEW_COLUMNS + " WHERE s.close_idempotency_key = :key", nativeQuery = true)
  Optional<RegisterSessionView> findViewByCloseIdempotencyKey(@Param("key") String key);

  /** An outlet's session history, most recent first (idx_crs_outlet_history). */
  @Query(
      value =
          VIEW_COLUMNS + " WHERE s.business_id = :businessId ORDER BY s.opened_at DESC LIMIT 50",
      nativeQuery = true)
  List<RegisterSessionView> findHistoryViewsByBusinessId(@Param("businessId") UUID businessId);

  /**
   * Σ CASH physically collected for the outlet in the session window {@code [from, to)} — the
   * expected-cash term. Review C1: {@code cash_collected_minor} (grand total − gift-card portion,
   * V22) is the drawer figure; pre-V22 rows fall back to {@code amount_minor} via COALESCE.
   * NULL-tender legacy rows are excluded by the predicate (documented undercount, ADR 0036).
   */
  @Query(
      value =
          """
          SELECT COALESCE(SUM(COALESCE(s.cash_collected_minor, s.amount_minor)), 0)
            FROM sale s
           WHERE s.business_id = :businessId
             AND s.tender_type = 'CASH'
             AND s.occurred_at >= :from
             AND s.occurred_at < :to
          """,
      nativeQuery = true)
  long sumCashSales(
      @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * Σ CASH refund DELTAS paid out of the drawer in the window — exact per-refund attribution via
   * the append-only {@code payment_refund} ledger (V22, review C3: summing the payment's
   * CUMULATIVE refunded_minor double-counted partial refunds spanning sessions).
   */
  @Query(
      value =
          """
          SELECT COALESCE(SUM(r.amount_minor), 0)
            FROM payment_refund r
           WHERE r.business_id = :businessId
             AND r.tender_type = 'CASH'
             AND r.refunded_at >= :from
             AND r.refunded_at < :to
          """,
      nativeQuery = true)
  long sumCashRefunds(
      @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);
}
