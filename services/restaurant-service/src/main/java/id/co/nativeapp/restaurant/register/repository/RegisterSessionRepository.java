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
   * Σ CASH-tender sale amounts (the customer-pays amount) for the outlet in the session window
   * {@code [from, to)} — the expected-cash term. Uses the V21 partial index; NULL-tender legacy
   * rows are excluded by the predicate (documented undercount for pre-V21 data, ADR 0036).
   */
  @Query(
      value =
          """
          SELECT COALESCE(SUM(s.amount_minor), 0)
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
   * Σ CASH refunds paid out of the drawer in the window — v1 attribution by {@code
   * payment.last_refund_at} (a payment's CUMULATIVE refunded_minor is attributed to the window
   * containing its LAST refund; multiple partials across sessions over-attribute to the last one —
   * the documented ADR 0036 approximation).
   */
  @Query(
      value =
          """
          SELECT COALESCE(SUM(p.refunded_minor), 0)
            FROM payment p
           WHERE p.business_id = :businessId
             AND p.tender_type = 'CASH'
             AND p.last_refund_at IS NOT NULL
             AND p.last_refund_at >= :from
             AND p.last_refund_at < :to
          """,
      nativeQuery = true)
  long sumCashRefunds(
      @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);
}
