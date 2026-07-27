package id.co.nativeapp.finance.withinclose.repository;

import id.co.nativeapp.finance.withinclose.domain.WithinCompanyClose;
import id.co.nativeapp.finance.withinclose.projection.WithinCompanyCloseView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the {@link WithinCompanyClose} aggregate.
 *
 * <p>A thin data port: no manual {@code WHERE company_id} — RLS scopes every read/write to the
 * bound company (rule 5). {@link #findByPeriod(String)} is the idempotency probe: a close that
 * already has a row for the period is a clean no-op (it re-emits nothing).
 */
public interface WithinCompanyCloseRepository extends JpaRepository<WithinCompanyClose, UUID> {

  /** The close row for a period within the bound company, if it has already closed. */
  Optional<WithinCompanyClose> findByPeriod(String period);

  /**
   * All close records for the bound company, projected to {@link WithinCompanyCloseView}, ordered
   * most-recent period first. No {@code WHERE company_id} — the result set is constrained solely by
   * the auto-applied RLS policy (rule 5). Used on pure read paths where the entity is only
   * inspected, never mutated or saved.
   *
   * @return the close history for the bound company
   */
  @Query(
      value =
          """
          SELECT wcc.id                      AS id,
                 wcc.period                  AS period,
                 wcc.base_currency           AS base_currency,
                 wcc.reconciled              AS reconciled,
                 wcc.uses_illustrative_rules AS uses_illustrative_rules
            FROM within_company_close wcc
           ORDER BY wcc.period DESC, wcc.id
          """,
      nativeQuery = true)
  List<WithinCompanyCloseView> findAllViews();

  /**
   * Takes a DETERMINISTIC per-{@code (company, period)} transaction-scoped advisory lock that
   * exists REGARDLESS of whether a {@code within_company_close} row exists yet — the same {@code
   * pg_advisory_xact_lock} primitive the labor path uses (its {@code
   * PayrollRunLedgerRepository#lockPeriod}, keyed on {@code company:period}); the group-close path
   * uses the analogous {@code ConsolidationLedgerRepository#lockGroupPeriod}, keyed on {@code
   * group:period}. Taken at the TOP of the close, BEFORE the {@code findByPeriod} idempotency
   * probe, it serializes two concurrent close attempts for the SAME {@code (company, period)}:
   * without it, both could pass the "no row yet" probe, both gather the trial balance, and then one
   * wins the {@code uq_within_company_close} UNIQUE insert while the other dies with a
   * unique-violation — a spurious {@code 500} on what is meant to be an idempotent re-close. With
   * the lock, the second attempt blocks until the first commits, THEN sees the row and returns the
   * clean idempotent no-op (re-emitting nothing). The UNIQUE constraint remains the durable
   * backstop; the lock makes concurrency GRACEFUL.
   *
   * <p>{@code pg_advisory_xact_lock} keys on the {@code (company, period)} pair itself (via {@code
   * hashtext}), so the lock is held even before the first row is inserted; PostgreSQL auto-releases
   * it at commit/rollback (no manual unlock), and it works under the non-superuser {@code app_user}
   * role. {@code hashtext(:key)} maps {@code company:period} into a 32-bit space, so two distinct
   * keys could collide onto one lock id — a collision only OVER-serializes (two unrelated pairs
   * briefly take turns), never a correctness error: correctness rests on the UNIQUE constraint, not
   * the lock's granularity. Returns {@code true} purely so Spring Data can bind the void {@code
   * pg_advisory_xact_lock} as a scalar result.
   */
  @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key)::bigint) IS NULL", nativeQuery = true)
  boolean lockPeriod(@Param("key") String key);
}
