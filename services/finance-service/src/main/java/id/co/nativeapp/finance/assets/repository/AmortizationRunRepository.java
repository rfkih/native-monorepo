package id.co.nativeapp.finance.assets.repository;

import id.co.nativeapp.finance.assets.domain.AmortizationRun;
import id.co.nativeapp.finance.assets.projection.RunView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the {@link AmortizationRun} seal (Phase 6, ADR 0020). RLS scopes all
 * access (rule 5). {@link #findByPeriod} is the write-path idempotency probe; {@link #lockPeriod} is
 * the deterministic advisory lock taken BEFORE it (copied verbatim from the tax-filing /
 * within-close repositories: serializes two concurrent runs of the same {@code (company, period)} so
 * the loser blocks, then finds the row and no-ops instead of dying on the {@code
 * uq_amortization_run} UNIQUE — which remains the durable backstop).
 */
public interface AmortizationRunRepository extends JpaRepository<AmortizationRun, UUID> {

  /** The run for a period within the bound company, if it has already run — the idempotency probe. */
  Optional<AmortizationRun> findByPeriod(String period);

  /** One run by id, projected — the post-run detail read (RLS-scoped). */
  @Query(
      value =
          """
          SELECT ar.id          AS id,
                 ar.period      AS period,
                 ar.line_count  AS line_count,
                 ar.total_minor AS total_minor,
                 ar.run_at      AS run_at
            FROM amortization_run ar
           WHERE ar.id = :id
          """,
      nativeQuery = true)
  Optional<RunView> findViewById(@Param("id") UUID id);

  /** The run history, projected, most-recent period first (RLS-scoped; bounded). */
  @Query(
      value =
          """
          SELECT ar.id          AS id,
                 ar.period      AS period,
                 ar.line_count  AS line_count,
                 ar.total_minor AS total_minor,
                 ar.run_at      AS run_at
            FROM amortization_run ar
           ORDER BY ar.period DESC
           LIMIT 500
          """,
      nativeQuery = true)
  List<RunView> findAllViews();

  /**
   * Transaction-scoped advisory lock keyed on {@code (company, period)} — held even before the seal
   * row exists; auto-released at commit/rollback. See {@code TaxFilingRepository#lockPeriod} for the
   * full rationale. Returns {@code true} so Spring Data can bind the void function as a scalar.
   */
  @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key)::bigint) IS NULL", nativeQuery = true)
  boolean lockPeriod(@Param("key") String key);
}
