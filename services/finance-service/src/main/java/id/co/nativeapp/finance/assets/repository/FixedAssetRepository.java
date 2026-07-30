package id.co.nativeapp.finance.assets.repository;

import id.co.nativeapp.finance.assets.domain.FixedAsset;
import id.co.nativeapp.finance.assets.projection.AssetView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link FixedAsset} (Phase 6, ADR 0020). A thin data port: no manual
 * {@code WHERE company_id} — RLS scopes every read/write to the bound company (rule 5). The
 * amortization-run WRITE path loads the full aggregates ({@link #findByStatus}); the register READ
 * path is native + projection with the accumulated-depreciation roll-up.
 */
public interface FixedAssetRepository extends JpaRepository<FixedAsset, UUID> {

  /** The aggregates in a status — the run writer's write-path load (RLS-scoped). */
  List<FixedAsset> findByStatus(FixedAsset.Status status);

  /**
   * The asset created by a prior attempt with this Idempotency-Key (RLS scopes to the bound
   * company) — the write-path replay probe: a retried acquire returns it instead of double-posting.
   */
  Optional<FixedAsset> findByIdempotencyKey(String idempotencyKey);

  /**
   * The asset disposed by a prior attempt with this Idempotency-Key (RLS-scoped) — the dispose
   * replay probe (ADR 0022): a retried dispose returns it instead of double-posting.
   */
  Optional<FixedAsset> findByDisposalIdempotencyKey(String disposalIdempotencyKey);

  /**
   * The asset register: each asset with its accumulated depreciation (Σ run-line amounts from the
   * sub-ledger) — book value = cost − accumulated, computed by the reader. RLS constrains both
   * tables. Bounded {@code LIMIT} (mirrors the other list readers).
   */
  @Query(
      value =
          """
          SELECT fa.id                              AS id,
                 fa.name                            AS name,
                 fa.acquisition_date                AS acquisition_date,
                 fa.start_period                    AS start_period,
                 fa.cost_minor                      AS cost_minor,
                 fa.salvage_minor                   AS salvage_minor,
                 fa.useful_life_months              AS useful_life_months,
                 fa.currency                        AS currency,
                 fa.status                          AS status,
                 fa.disposal_date                   AS disposal_date,
                 fa.proceeds_minor                  AS proceeds_minor,
                 COALESCE(SUM(arl.amount_minor), 0) AS accumulated_minor
            FROM fixed_asset fa
            LEFT JOIN amortization_run_line arl
                   ON arl.item_type = 'ASSET' AND arl.item_id = fa.id
           GROUP BY fa.id, fa.name, fa.acquisition_date, fa.start_period, fa.cost_minor,
                    fa.salvage_minor, fa.useful_life_months, fa.currency, fa.status,
                    fa.disposal_date, fa.proceeds_minor
           ORDER BY fa.acquisition_date DESC, fa.name
           LIMIT 500
          """,
      nativeQuery = true)
  List<AssetView> findRegister();

  /** One register row by id (the same roll-up), for the asset detail. */
  @Query(
      value =
          """
          SELECT fa.id                              AS id,
                 fa.name                            AS name,
                 fa.acquisition_date                AS acquisition_date,
                 fa.start_period                    AS start_period,
                 fa.cost_minor                      AS cost_minor,
                 fa.salvage_minor                   AS salvage_minor,
                 fa.useful_life_months              AS useful_life_months,
                 fa.currency                        AS currency,
                 fa.status                          AS status,
                 fa.disposal_date                   AS disposal_date,
                 fa.proceeds_minor                  AS proceeds_minor,
                 COALESCE(SUM(arl.amount_minor), 0) AS accumulated_minor
            FROM fixed_asset fa
            LEFT JOIN amortization_run_line arl
                   ON arl.item_type = 'ASSET' AND arl.item_id = fa.id
           WHERE fa.id = :id
           GROUP BY fa.id, fa.name, fa.acquisition_date, fa.start_period, fa.cost_minor,
                    fa.salvage_minor, fa.useful_life_months, fa.currency, fa.status,
                    fa.disposal_date, fa.proceeds_minor
          """,
      nativeQuery = true)
  Optional<AssetView> findRegisterRow(@Param("id") UUID id);
}
