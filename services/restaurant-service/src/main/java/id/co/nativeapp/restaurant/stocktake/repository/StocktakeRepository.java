package id.co.nativeapp.restaurant.stocktake.repository;

import id.co.nativeapp.restaurant.stocktake.domain.Stocktake;
import id.co.nativeapp.restaurant.stocktake.projection.StocktakeView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Stocktake} (ADR 0038 phase 3).
 *
 * <p>No manual {@code WHERE company_id} — {@link RlsAutoApplyAspect} sets the tenant GUC and the
 * RLS policy applies the tenant scope (rule 5). Read paths return the narrow {@link StocktakeView}
 * projection via native queries; the write path uses the inherited {@code save}/{@code
 * saveAndFlush} (needs the whole aggregate).
 */
public interface StocktakeRepository extends JpaRepository<Stocktake, UUID> {

  // Interface fields are implicitly public static final — the shared projection column list.
  String VIEW_COLUMNS =
      """
      SELECT s.id              AS id,
             s.business_id     AS business_id,
             s.counted_at      AS counted_at,
             s.currency        AS currency,
             s.shrinkage_minor AS shrinkage_minor
        FROM stocktake s
      """;

  /** Idempotency replay probe: the stocktake previously submitted under this key. */
  @Query(value = VIEW_COLUMNS + " WHERE s.idempotency_key = :key", nativeQuery = true)
  Optional<StocktakeView> findViewByIdempotencyKey(@Param("key") String key);

  /** A stocktake by id (projection, read path). */
  @Query(value = VIEW_COLUMNS + " WHERE s.id = :id", nativeQuery = true)
  Optional<StocktakeView> findViewById(@Param("id") UUID id);

  /** An outlet's stocktake history, most recent first, capped at 50. */
  @Query(
      value =
          VIEW_COLUMNS + " WHERE s.business_id = :businessId ORDER BY s.counted_at DESC LIMIT 50",
      nativeQuery = true)
  List<StocktakeView> findHistoryViewsByBusinessId(@Param("businessId") UUID businessId);
}
