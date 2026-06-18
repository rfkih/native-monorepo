package id.co.nativeapp.carwash.wash.repository;

import id.co.nativeapp.carwash.wash.domain.Wash;
import id.co.nativeapp.carwash.wash.projection.WashView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Wash}.
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
 * business columns the response needs ({@link WashView}) rather than {@code SELECT *} of the full
 * {@code Auditable} row — narrower I/O, and the entity is loaded only on the write path (the
 * inherited {@code save}/{@code saveAndFlush}, which legitimately needs the whole aggregate to
 * persist it).
 */
public interface WashRepository extends JpaRepository<Wash, UUID> {

  /**
   * Finds an existing wash by its client idempotency key within the bound tenant (RLS-scoped),
   * projected to {@link WashView}. Used for the idempotency fast-path short-circuit in {@code
   * WashWriter.create()} and for the concurrent-collision recovery re-read in {@code
   * WashWriter.findExistingByKey()} — the result is only ever read into the response, never
   * mutated, so a projection (not the entity) is fetched.
   */
  @Query(
      value =
          """
          SELECT w.id                   AS id,
                 w.business_id          AS business_id,
                 w.bay                  AS bay,
                 w.amount_minor         AS amount_minor,
                 w.currency             AS currency,
                 w.upsell_name          AS upsell_name,
                 w.upsell_amount_minor  AS upsell_amount_minor,
                 w.occurred_at          AS occurred_at,
                 w.idempotency_key      AS idempotency_key
            FROM wash w
           WHERE w.idempotency_key = :idempotencyKey
          """,
      nativeQuery = true)
  Optional<WashView> findViewByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

  /**
   * Every wash visible to the bound tenant, projected to {@link WashView} and ordered
   * deterministically. No {@code WHERE company_id} — the result set is constrained solely by the
   * auto-applied RLS policy (rule 5).
   */
  @Query(
      value =
          """
          SELECT w.id                   AS id,
                 w.business_id          AS business_id,
                 w.bay                  AS bay,
                 w.amount_minor         AS amount_minor,
                 w.currency             AS currency,
                 w.upsell_name          AS upsell_name,
                 w.upsell_amount_minor  AS upsell_amount_minor,
                 w.occurred_at          AS occurred_at,
                 w.idempotency_key      AS idempotency_key
            FROM wash w
           ORDER BY w.occurred_at, w.id
          """,
      nativeQuery = true)
  List<WashView> findAllViews();
}
