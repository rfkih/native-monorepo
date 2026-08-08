package id.co.nativeapp.restaurant.sale.repository;

import id.co.nativeapp.restaurant.sale.domain.Sale;
import id.co.nativeapp.restaurant.sale.projection.SaleHistoryView;
import id.co.nativeapp.restaurant.sale.projection.SaleView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Sale}.
 *
 * <p>Deliberately carries <em>no</em> manual {@code WHERE company_id = ...} and no call to apply
 * the tenant GUC: every Spring Data method is transactional, so {@link RlsAutoApplyAspect} sets
 * {@code app.current_tenant} on the connection automatically and the PostgreSQL RLS policy
 * restricts results to the bound company (correctness by default — rule 5). The {@code
 * idempotency_key} lookup is therefore implicitly tenant-scoped: it can only ever find a row
 * belonging to the session tenant, which matches the {@code (company_id, idempotency_key)} unique
 * constraint exactly. Native queries run on that same RLS-scoped connection, so projecting columns
 * by hand does NOT weaken tenant isolation.
 *
 * <p><strong>Read paths use a native query + projection (CLAUDE.md convention — "native-query
 * aliases snake_case; map via projection interfaces").</strong> Both read methods fetch only the
 * six business columns the response needs ({@link SaleView}) rather than {@code SELECT *} of the
 * full {@code Auditable} row — narrower I/O, and the entity is loaded only on the write path (the
 * inherited {@code save}/{@code saveAndFlush}, which legitimately needs the whole aggregate).
 */
public interface SaleRepository extends JpaRepository<Sale, UUID> {

  /**
   * Finds an existing sale by its client idempotency key within the bound tenant (RLS-scoped),
   * projected to {@link SaleView}. Used to make record-sale idempotent on retry — the result is
   * only ever read into the response, never mutated, so a projection (not the entity) is fetched.
   */
  @Query(
      value =
          """
          SELECT s.id              AS id,
                 s.business_id     AS business_id,
                 s.amount_minor    AS amount_minor,
                 s.currency        AS currency,
                 s.occurred_at     AS occurred_at,
                 s.idempotency_key AS idempotency_key
            FROM sale s
           WHERE s.idempotency_key = :idempotencyKey
          """,
      nativeQuery = true)
  Optional<SaleView> findViewByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

  /**
   * Every sale visible to the bound tenant, projected to {@link SaleView} and ordered
   * deterministically. No {@code WHERE company_id} — the result set is constrained solely by the
   * auto-applied RLS policy (rule 5).
   */
  @Query(
      value =
          """
          SELECT s.id              AS id,
                 s.business_id     AS business_id,
                 s.amount_minor    AS amount_minor,
                 s.currency        AS currency,
                 s.occurred_at     AS occurred_at,
                 s.idempotency_key AS idempotency_key
            FROM sale s
           ORDER BY s.occurred_at, s.id
          """,
      nativeQuery = true)
  List<SaleView> findAllViews();

  /**
   * The cashier's "today's transactions" list ({@code GET /api/v1/sales}) — a business unit's sales
   * with {@code occurred_at} in {@code [from, to)}, newest first, hard-capped at 200 rows so a busy
   * outlet's full-day history never returns an unbounded result. The caller (the outlet client)
   * computes the local-day bounds; no timezone math happens server-side.
   *
   * <p>LEFT JOINs {@code restaurant_order} for the reprint link ({@code order_id} is {@code null}
   * when no order backs the sale, e.g. a bar-tab/carwash/direct sale) — {@code
   * restaurant_order.sale_id} is set by the writer at most once per order, so a sale has at most
   * one matching order row in practice. RLS-scoped automatically (rule 5) on both joined tables —
   * no manual {@code company_id} predicate, matching this repository's other native queries.
   */
  @Query(
      value =
          """
          SELECT s.id           AS id,
                 o.id           AS order_id,
                 s.occurred_at  AS occurred_at,
                 s.amount_minor AS amount_minor,
                 s.currency     AS currency,
                 s.tender_type  AS tender_type,
                 s.channel_code AS channel_code
            FROM sale s
            LEFT JOIN restaurant_order o ON o.sale_id = s.id
           WHERE s.business_id = :businessId
             AND s.occurred_at >= :from
             AND s.occurred_at < :to
           ORDER BY s.occurred_at DESC
           LIMIT 200
          """,
      nativeQuery = true)
  List<SaleHistoryView> findHistory(
      @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);
}
