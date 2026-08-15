package id.co.nativeapp.restaurant.register.repository;

import id.co.nativeapp.restaurant.register.domain.RegisterSession;
import id.co.nativeapp.restaurant.register.projection.ClosedSessionSalesView;
import id.co.nativeapp.restaurant.register.projection.RegisterSessionView;
import id.co.nativeapp.restaurant.register.projection.SaleSummaryView;
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

  /** A session by id (projection, read path) — backs the expected-breakdown preview (ADR 0038). */
  @Query(value = VIEW_COLUMNS + " WHERE s.id = :id", nativeQuery = true)
  Optional<RegisterSessionView> findViewById(@Param("id") UUID id);

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
   * The outlet's CLOSED sessions, most recent first, each carrying the day's headline sales figures
   * — backs the manager/owner past-day history browse. For each session the two lateral subqueries
   * aggregate over the session's own {@code [opened_at, closed_at)} window, MIRRORING {@code
   * RegisterSessionWriter#summarize} exactly so a row's {@code net_sales_minor} equals that
   * session's Z-report net: sales total = Σ tendered {@code sale.amount_minor}; refunds = Σ {@code
   * payment_refund.amount_minor} for the four settleable tenders; net = total − refunds. Closed
   * sessions never overlap per outlet (one OPEN at a time), so each sale/refund falls in at most
   * one window. RLS auto-scopes all three tables to the tenant (rule 5 — no manual {@code
   * company_id}); the outer scan uses {@code idx_crs_outlet_history}, the sale lateral {@code
   * idx_sale_business_window}. Every SUM is COALESCE'd to 0 (a no-sales day nets 0, never NULL).
   */
  @Query(
      value =
          """
          SELECT s.id                                            AS id,
                 s.business_date                                 AS business_date,
                 s.opened_at                                     AS opened_at,
                 s.closed_at                                     AS closed_at,
                 s.currency                                      AS currency,
                 (sales.total_minor - refunds.refunds_minor)::bigint AS net_sales_minor,
                 sales.txn_count                                 AS transaction_count
            FROM cash_register_session s
            LEFT JOIN LATERAL (
                SELECT COALESCE(SUM(x.amount_minor), 0)::bigint AS total_minor,
                       COUNT(*)                                 AS txn_count
                  FROM sale x
                 WHERE x.business_id = s.business_id
                   AND x.tender_type IS NOT NULL
                   AND x.occurred_at >= s.opened_at
                   AND x.occurred_at <  s.closed_at
            ) sales ON TRUE
            LEFT JOIN LATERAL (
                SELECT COALESCE(SUM(r.amount_minor), 0)::bigint AS refunds_minor
                  FROM payment_refund r
                 WHERE r.business_id = s.business_id
                   AND r.tender_type IN ('CASH', 'CARD', 'QRIS', 'ONLINE')
                   AND r.refunded_at >= s.opened_at
                   AND r.refunded_at <  s.closed_at
            ) refunds ON TRUE
           WHERE s.business_id = :businessId
             AND s.status = 'CLOSED'
           ORDER BY s.opened_at DESC
           LIMIT :limit
          """,
      nativeQuery = true)
  List<ClosedSessionSalesView> findClosedHistoryWithSalesByBusinessId(
      @Param("businessId") UUID businessId, @Param("limit") int limit);

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
   * the append-only {@code payment_refund} ledger (V22, review C3: summing the payment's CUMULATIVE
   * refunded_minor double-counted partial refunds spanning sessions).
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

  /**
   * Σ CASH taken for GIFT CARD SALES in the window — the third drawer inflow. A gift card sold for
   * cash is a liability, not revenue, so it lives in {@code gift_card_sale}, not {@code sale} — but
   * its cash is physically in the drawer and MUST count toward expected cash (found live: a 30k
   * cash gift-card sale otherwise surfaces as a phantom 30k OVER at close). ADR 0036.
   */
  @Query(
      value =
          """
          SELECT COALESCE(SUM(g.amount_minor), 0)
            FROM gift_card_sale g
           WHERE g.business_id = :businessId
             AND g.tender_type = 'CASH'
             AND g.occurred_at >= :from
             AND g.occurred_at < :to
          """,
      nativeQuery = true)
  long sumCashGiftCardSales(
      @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * Σ the CHARGED amount for a NON-cash tender (CARD/QRIS/ONLINE) in the window — {@code
   * amount_minor − gift_card_redeemed_minor}, i.e. EXACTLY the net-tender leg that accrued in that
   * tender's clearing account (finance debits the clearing with {@code amount − giftCardRedeemed},
   * and cash nets the same via {@code cash_collected_minor}). Summing the gross grand total would
   * overstate a card/QRIS sale that carried a gift-card split and post a phantom short at close
   * (ADR 0038 phase 2, code-review C1). Per-tender expected = this − {@link #sumRefundsByTender}.
   */
  @Query(
      value =
          """
          SELECT COALESCE(SUM(s.amount_minor - s.gift_card_redeemed_minor), 0)
            FROM sale s
           WHERE s.business_id = :businessId
             AND s.tender_type = :tender
             AND s.occurred_at >= :from
             AND s.occurred_at < :to
          """,
      nativeQuery = true)
  long sumSalesByTender(
      @Param("businessId") UUID businessId,
      @Param("tender") String tender,
      @Param("from") Instant from,
      @Param("to") Instant to);

  /** Σ refund DELTAS for a NON-cash tender in the window (append-only {@code payment_refund}). */
  @Query(
      value =
          """
          SELECT COALESCE(SUM(r.amount_minor), 0)
            FROM payment_refund r
           WHERE r.business_id = :businessId
             AND r.tender_type = :tender
             AND r.refunded_at >= :from
             AND r.refunded_at < :to
          """,
      nativeQuery = true)
  long sumRefundsByTender(
      @Param("businessId") UUID businessId,
      @Param("tender") String tender,
      @Param("from") Instant from,
      @Param("to") Instant to);

  /**
   * The aggregate sales figures for the POS daily summary (Z-report) over a session window {@code
   * [from, to)}. Sums the per-sale price-breakdown SNAPSHOT (V39) so the report respects each
   * sale's own effective tax rule with no re-derivation and no second rounding; legacy/no-breakdown
   * rows fall back to {@code subtotal == amount_minor} via COALESCE (mirrors finance's fallback),
   * keeping the reconciliation identity {@code gross − discount − loyalty + service + tax == total}
   * true. Scoped to TENDERED sales ({@code tender_type IS NOT NULL}) — the same universe as the
   * per-tender sums, so the summary's tender lines + refunds reconcile to the total. Every
   * component is COALESCE'd to 0 (an all-NULL window / no rows returns zeros, never NULL). RLS
   * auto-applies (no manual {@code company_id} — rule 5).
   */
  @Query(
      value =
          """
          SELECT COUNT(*)                                                             AS txn_count,
                 COALESCE(SUM(COALESCE(s.subtotal_minor, s.amount_minor)), 0)::bigint AS gross_sales_minor,
                 COALESCE(SUM(s.discount_minor), 0)::bigint                           AS discount_minor,
                 COALESCE(SUM(s.service_charge_minor), 0)::bigint                     AS service_charge_minor,
                 COALESCE(SUM(s.tax_minor), 0)::bigint                                AS tax_minor,
                 COALESCE(SUM(s.loyalty_redeemed_minor), 0)::bigint                   AS loyalty_redeemed_minor,
                 COALESCE(SUM(s.amount_minor), 0)::bigint                             AS total_minor,
                 COALESCE(BOOL_OR(s.uses_illustrative_rules), FALSE)                  AS uses_illustrative_rules
            FROM sale s
           WHERE s.business_id = :businessId
             AND s.tender_type IS NOT NULL
             AND s.occurred_at >= :from
             AND s.occurred_at < :to
          """,
      nativeQuery = true)
  SaleSummaryView summarizeSales(
      @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * Σ the gift-card-redeemed portion tendered across sales in the window ({@code sale.
   * gift_card_redeemed_minor}) — the settlement value paid via gift card. On the Z-report it is a
   * 5th settlement line alongside the CASH/CARD/QRIS/ONLINE GROSS sums; together they foot to Σ
   * {@code amount_minor} (the day's total), because each per-tender GROSS sum already excludes the
   * gift-card split ({@code cash_collected_minor} / {@code amount_minor −
   * gift_card_redeemed_minor}). Scoped to tendered sales, matching the summary universe. RLS
   * auto-applies (rule 5).
   */
  @Query(
      value =
          """
          SELECT COALESCE(SUM(s.gift_card_redeemed_minor), 0)
            FROM sale s
           WHERE s.business_id = :businessId
             AND s.tender_type IS NOT NULL
             AND s.occurred_at >= :from
             AND s.occurred_at < :to
          """,
      nativeQuery = true)
  long sumGiftCardRedeemed(
      @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);
}
