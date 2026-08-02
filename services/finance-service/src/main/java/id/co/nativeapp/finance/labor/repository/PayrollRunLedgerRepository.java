package id.co.nativeapp.finance.labor.repository;

import id.co.nativeapp.finance.labor.domain.PayrollRunLedger;
import id.co.nativeapp.finance.labor.projection.PayrollLiabilityRunView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data port for the {@link PayrollRunLedger} control table (#23). Derived-query methods plus
 * NATIVE {@code @Query} methods only (CLAUDE.md — no JPQL anywhere in this codebase), no business
 * logic, no manual {@code WHERE company_id} — tenant scoping comes solely from the auto-applied RLS
 * GUC on every {@code @Transactional} method (rule 5).
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
   * lower {@code run_seq} of the SAME {@code run_type} whose {@code liability_state = 'POSTED'}
   * (the ONLY ACTIVE liability value — {@code NULL} means never touched, {@code SUPERSEDED} means
   * already inactive, so {@code = 'POSTED'} is the precise ACTIVE predicate). Tracked on the {@code
   * liability_state} column, INDEPENDENTLY of {@link #findActivePriorRuns}'s {@code state} column
   * (ADR 0032 — see {@link PayrollRunLedger}'s class javadoc for why the two lifecycles must stay
   * separate).
   *
   * <p><strong>Deliberately NOT filtered on {@code liability_entry_id IS NOT NULL}</strong> (P5
   * review W1): an all-zero run (employer cost + every bucket zero) is stamped {@code
   * liability_state = 'POSTED'} with a {@code NULL liability_entry_id} — {@code
   * PayrollLiabilityWriter} never builds a {@code JournalEntry} for it (see {@code
   * PayrollRunLedger#markLiabilityPostedEmpty}). Such a row MUST still be found here so a later
   * corrective run correctly flips it to {@code SUPERSEDED}; reversing it is a documented no-op
   * ({@code PayrollLiabilityWriter#reversePriorRunLiability} returns immediately when {@code
   * priorEntryId} is {@code null} — nothing was ever posted for it, so there is nothing to
   * reverse).
   */
  @Query(
      value =
          """
          SELECT r.* FROM payroll_run_ledger r
           WHERE r.period = :period
             AND r.run_type = :runType
             AND r.run_seq < :runSeq
             AND r.liability_state = 'POSTED'
          """,
      nativeQuery = true)
  List<PayrollRunLedger> findActiveLiabilityPriorRuns(
      @Param("period") String period,
      @Param("runType") String runType,
      @Param("runSeq") int runSeq);

  /**
   * Whether an ACTIVE ({@code liability_state = 'POSTED'}) LIABILITY row with a strictly HIGHER
   * {@code run_seq} of the SAME {@code run_type} already exists for the {@code (period, runType)} —
   * the liability dimension's own out-of-order guard (ADR 0032), mirroring {@link
   * #existsActiveHigherRun} exactly but scoped to {@code liability_state} instead of the shared
   * {@code state} column. See {@link #findActiveLiabilityPriorRuns}'s javadoc for why this is NOT
   * additionally filtered on {@code liability_entry_id IS NOT NULL} (P5 review W1, the all-zero-run
   * case).
   */
  @Query(
      value =
          """
          SELECT count(r.*) > 0 FROM payroll_run_ledger r
           WHERE r.period = :period
             AND r.run_type = :runType
             AND r.run_seq > :runSeq
             AND r.liability_state = 'POSTED'
          """,
      nativeQuery = true)
  boolean existsActiveHigherLiabilityRun(
      @Param("period") String period,
      @Param("runType") String runType,
      @Param("runSeq") int runSeq);

  /**
   * The shared SELECT/FROM/JOIN core for the two liability-bucket reads below (ADR 0032, Track P
   * phase P5) — a single {@code interface}-constant (compile-time constant expression, mirroring
   * {@code PayslipLineRepository.ACTIVE_RUN_PREDICATE}) so the period-scoped and single-run-scoped
   * queries can never drift apart. See {@link PayrollLiabilityRunView}'s javadoc for the full
   * bucket-amount read design (the {@code LATERAL} role-resolution join, keyed on the liability
   * entry's OWN {@code occurred_at} so a later {@code role_account_map} edit can never misattribute
   * a historical line).
   */
  String LIABILITY_BUCKET_SELECT =
      """
      SELECT prl.id                            AS run_ledger_id,
             prl.payroll_run_id                 AS payroll_run_id,
             prl.period                         AS period,
             prl.run_seq                        AS run_seq,
             prl.run_type                       AS run_type,
             prl.liability_state                AS liability_state,
             prl.currency                       AS currency,
             COALESCE(SUM(CASE WHEN ram.account_role = 'NET_WAGES_PAYABLE'
                                THEN jl.credit_minor - jl.debit_minor END), 0) AS net_wages_minor,
             COALESCE(SUM(CASE WHEN ram.account_role = 'PPH21_PAYABLE'
                                THEN jl.credit_minor - jl.debit_minor END), 0) AS pph21_minor,
             COALESCE(SUM(CASE WHEN ram.account_role = 'BPJS_KES_PAYABLE'
                                THEN jl.credit_minor - jl.debit_minor END), 0) AS bpjs_kes_minor,
             COALESCE(SUM(CASE WHEN ram.account_role = 'BPJS_TK_PAYABLE'
                                THEN jl.credit_minor - jl.debit_minor END), 0) AS bpjs_tk_minor,
             COALESCE(SUM(CASE WHEN ram.account_role = 'OTHER_DEDUCTIONS_PAYABLE'
                                THEN jl.credit_minor - jl.debit_minor END), 0) AS other_minor
        FROM payroll_run_ledger prl
        JOIN journal_entry je ON je.id = prl.liability_entry_id
        JOIN journal_line jl ON jl.entry_id = je.id
        LEFT JOIN LATERAL (
                 SELECT ram.account_role
                   FROM role_account_map ram
                  WHERE ram.gl_account_code = jl.account_code
                    AND je.occurred_at::date BETWEEN ram.effective_from AND ram.effective_to
                  ORDER BY ram.version DESC, ram.effective_from DESC
                  LIMIT 1
             ) ram ON TRUE
      """;

  /** The shared {@code GROUP BY} clause paired with {@link #LIABILITY_BUCKET_SELECT}. */
  String LIABILITY_BUCKET_GROUP_BY =
      """
      GROUP BY prl.id, prl.payroll_run_id, prl.period, prl.run_seq, prl.run_type,
               prl.liability_state, prl.currency
      """;

  /**
   * The period's ACTIVE ({@code liability_state = 'POSTED'}) runs with their five payroll-liability
   * bucket totals (ADR 0032, Track P phase P5). A run whose {@code liability_state} is {@code NULL}
   * (no {@code PayrollLiabilitiesPosted} has landed yet) or {@code SUPERSEDED} is excluded — those
   * are not currently-owed liabilities.
   */
  @Query(
      value =
          LIABILITY_BUCKET_SELECT
              + " WHERE prl.period = :period AND prl.liability_state = 'POSTED' "
              + LIABILITY_BUCKET_GROUP_BY
              + " ORDER BY prl.run_seq DESC",
      nativeQuery = true)
  List<PayrollLiabilityRunView> findLiabilityRunsForPeriod(@Param("period") String period);

  /**
   * A single run's liability bucket totals by its {@code payroll_run_ledger} id, regardless of
   * {@code liability_state} (unlike {@link #findLiabilityRunsForPeriod}) — used to build the
   * settlement response/{@code GET} detail for one run, including a SUPERSEDED run's last-known
   * historical totals for audit purposes (settling one is still blocked by {@code
   * PayrollSettlementWriter}'s own state guard). Empty when {@code liability_entry_id} is NULL (no
   * liability recognised yet) — the join naturally returns no rows.
   */
  @Query(
      value = LIABILITY_BUCKET_SELECT + " WHERE prl.id = :runLedgerId " + LIABILITY_BUCKET_GROUP_BY,
      nativeQuery = true)
  Optional<PayrollLiabilityRunView> findLiabilityRunByRunLedgerId(
      @Param("runLedgerId") UUID runLedgerId);
}
