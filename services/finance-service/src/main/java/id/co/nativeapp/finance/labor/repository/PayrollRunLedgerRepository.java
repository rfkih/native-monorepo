package id.co.nativeapp.finance.labor.repository;

import id.co.nativeapp.finance.labor.domain.PayrollRunLedger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data port for the {@link PayrollRunLedger} control table (#23). Derived/JPQL queries only,
 * no business logic, no manual {@code WHERE company_id} — tenant scoping comes solely from the
 * auto-applied RLS GUC on every {@code @Transactional} method (rule 5).
 */
public interface PayrollRunLedgerRepository extends JpaRepository<PayrollRunLedger, UUID> {

  /** The control row for a specific run, if it exists (idempotent upsert lookup). */
  Optional<PayrollRunLedger> findByPayrollRunIdAndRunSeq(UUID payrollRunId, int runSeq);

  /**
   * Takes a DETERMINISTIC per-{@code (company, period)} transaction-scoped advisory lock that
   * exists REGARDLESS of whether any {@code payroll_run_ledger} row exists yet — the fix for the
   * first-two-runs concurrency double-count (#23). The old {@code SELECT ... FOR UPDATE} on the
   * highest existing run row acquired NO lock when no run row existed yet, so two genuinely NEW
   * runs ({@code run_seq=1} and {@code run_seq=2}) in parallel READ_COMMITTED transactions could
   * not see each other: neither reversed the other, both posted a PRIMARY, and the period
   * double-counted.
   *
   * <p>{@code pg_advisory_xact_lock} keys on the {@code (company_id, period)} pair itself, so the
   * lock is held even before the first row is inserted; the later run therefore serializes behind
   * the earlier run's row creation and correctly sees-and-reverses (or self-supersedes) it. The
   * lock is transaction-scoped: PostgreSQL auto-releases it at commit/rollback (no manual unlock),
   * and it works under the non-superuser {@code app_user} role. Called at the TOP of BOTH writer
   * paths, BEFORE any run-row read/insert. Returns {@code true} (the void {@code
   * pg_advisory_xact_lock} is mapped to a boolean projection) purely so Spring Data can bind it as
   * a scalar result.
   *
   * <p>{@code hashtext(:key)} maps the {@code company:period} string into a 32-bit space, so two
   * distinct keys could collide onto the same lock id. A collision only OVER-serializes (two
   * unrelated {@code (company, period)} pairs briefly take turns on one lock) — it is never a money
   * error: correctness rests on supersession + the exact-sum invariant, not on the lock's
   * granularity, which only affects concurrency throughput.
   */
  @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key)::bigint) IS NULL", nativeQuery = true)
  boolean lockPeriod(@Param("key") String key);

  /**
   * The prior runs for a {@code (period, runType)} that a run of {@code runSeq} supersedes: every
   * row with a strictly lower {@code run_seq} of the SAME {@code run_type} that is still ACTIVE
   * (not already SUPERSEDED). These are reversed append-only and flipped to {@link
   * PayrollRunState#SUPERSEDED}. Keyed on {@code run_type} too (ADR 0032, Track P phase P4) so a
   * future THR run never supersedes — or is superseded by — a REGULAR run of the same period.
   */
  @Query(
      value =
          """
          SELECT r.* FROM payroll_run_ledger r
           WHERE r.period = :period
             AND r.run_type = :runType
             AND r.run_seq < :runSeq
             AND r.state <> 'SUPERSEDED'
          """,
      nativeQuery = true)
  List<PayrollRunLedger> findActivePriorRuns(
      @Param("period") String period,
      @Param("runType") String runType,
      @Param("runSeq") int runSeq);

  /**
   * Whether an ACTIVE (non-SUPERSEDED) run with a strictly HIGHER {@code run_seq} of the SAME
   * {@code run_type} already exists for the {@code (period, runType)} (within the bound tenant).
   * Out-of-order guard (#23): if a higher-seq run is already active when a LOWER-seq run's bucket
   * arrives (parallel consumers / different partitions), the incoming lower-seq run is ALREADY
   * superseded — it must not be allowed to leave a net residual (it would never be reversed by the
   * higher run, which already ran its reversal scan against the rows present then), so the
   * lower-seq bucket is posted-and-immediately-reversed and its run row flipped to {@link
   * PayrollRunState#SUPERSEDED} on the spot, never double-counting the period.
   */
  @Query(
      value =
          """
          SELECT count(r.*) > 0 FROM payroll_run_ledger r
           WHERE r.period = :period
             AND r.run_type = :runType
             AND r.run_seq > :runSeq
             AND r.state <> 'SUPERSEDED'
          """,
      nativeQuery = true)
  boolean existsActiveHigherRun(
      @Param("period") String period,
      @Param("runType") String runType,
      @Param("runSeq") int runSeq);

  /**
   * The prior runs for a {@code (period, runType)} whose LIABILITY dimension {@code
   * PayrollLiabilityWriter} of a run of {@code runSeq} must reverse: every row with a strictly
   * lower {@code run_seq} of the SAME {@code run_type} that carries an ACTIVE (non-SUPERSEDED)
   * liability entry. Tracked on the {@code liability_state} column, INDEPENDENTLY of {@link
   * #findActivePriorRuns}'s {@code state} column (ADR 0032 — see {@link PayrollRunLedger}'s class
   * javadoc for why the two lifecycles must stay separate) — a row whose {@code liability_entry_id}
   * is still NULL has nothing to reverse yet and is correctly excluded.
   */
  @Query(
      value =
          """
          SELECT r.* FROM payroll_run_ledger r
           WHERE r.period = :period
             AND r.run_type = :runType
             AND r.run_seq < :runSeq
             AND r.liability_entry_id IS NOT NULL
             AND (r.liability_state IS NULL OR r.liability_state <> 'SUPERSEDED')
          """,
      nativeQuery = true)
  List<PayrollRunLedger> findActiveLiabilityPriorRuns(
      @Param("period") String period,
      @Param("runType") String runType,
      @Param("runSeq") int runSeq);

  /**
   * Whether an ACTIVE (non-SUPERSEDED) LIABILITY entry with a strictly HIGHER {@code run_seq} of
   * the SAME {@code run_type} already exists for the {@code (period, runType)} — the liability
   * dimension's own out-of-order guard (ADR 0032), mirroring {@link #existsActiveHigherRun} exactly
   * but scoped to {@code liability_state} instead of the shared {@code state} column.
   */
  @Query(
      value =
          """
          SELECT count(r.*) > 0 FROM payroll_run_ledger r
           WHERE r.period = :period
             AND r.run_type = :runType
             AND r.run_seq > :runSeq
             AND r.liability_entry_id IS NOT NULL
             AND (r.liability_state IS NULL OR r.liability_state <> 'SUPERSEDED')
          """,
      nativeQuery = true)
  boolean existsActiveHigherLiabilityRun(
      @Param("period") String period,
      @Param("runType") String runType,
      @Param("runSeq") int runSeq);
}
