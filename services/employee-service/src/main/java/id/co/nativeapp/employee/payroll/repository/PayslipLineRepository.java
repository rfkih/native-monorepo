package id.co.nativeapp.employee.payroll.repository;

import id.co.nativeapp.employee.me.projection.MyPayslipHeaderView;
import id.co.nativeapp.employee.payroll.domain.PayslipLine;
import id.co.nativeapp.employee.payroll.projection.PayslipIndexView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data port for {@link PayslipLine}. RLS-scoped (rule 5). Amounts are encrypted ciphertext
 * at rest — NOT queryable/sortable by value (rule 6, intentional).
 */
public interface PayslipLineRepository extends JpaRepository<PayslipLine, UUID> {

  /**
   * The ACTIVE-run predicate shared by every December true-up query below (Track P phase P3):
   * {@code status = 'POSTED'} AND the run carries the MAX {@code run_seq} among POSTED runs for its
   * own {@code (period, run_type)} — Track P Phase P8 (ADR 0035) SCOPED BY {@code run_type} (the
   * fix for the landmine this constant used to carry, see below). This is a correlated subselect,
   * not a join against a separate "superseded" flag — {@code payroll_run} carries no such column
   * (unlike finance's {@code payroll_run_ledger.state}) — so "active" is defined structurally: a
   * higher {@code run_seq} of the SAME {@code run_type} that reached POSTED supersedes every lower
   * {@code run_seq} of that SAME {@code run_type} for the same period; a run that never reached
   * POSTED (still CALCULATED, or FAILED) never supersedes anything and is itself excluded by the
   * outer {@code status = 'POSTED'}. This MUST exactly mirror finance's supersession semantics
   * ({@code PayrollRunLedgerRepository#findActivePriorRuns}/{@code existsActiveHigherRun}, which
   * scope on {@code (period, run_type)} too, ADR 0032 Track P phase P4) so the December true-up
   * sums the SAME set of runs finance's books already reflect. A single {@code interface}-constant
   * (a compile-time constant expression) so the queries below can never drift apart from each
   * other.
   *
   * <p><strong>The P8 landmine this fixes (ADR 0035).</strong> Before this fix, {@code payroll_run}
   * was keyed {@code (company_id, period, run_seq)} only, and this predicate's {@code
   * MAX(pr2.run_seq) WHERE pr2.period = pr.period} carried NO {@code run_type} — so once a THR run
   * and a REGULAR run for the SAME period each got their OWN {@code run_seq} series (P8), a THR
   * run's {@code run_seq} could supersede a REGULAR run's (or vice versa) purely by numeric
   * coincidence (e.g. REGULAR reaches run_seq=2 via an unrelated correction while THR is still at
   * run_seq=1 — the old predicate would treat REGULAR's run_seq=2 as the sole "active" run for the
   * period, WRONGLY dropping the THR run's lines out of the December Art-17 annual base, or vice
   * versa). Scoping the correlated subselect AND the outer join predicate on {@code pr.run_type =
   * pr2.run_type} makes BOTH a run's own-type-only supersession hold AND lets a REGULAR run and a
   * THR run for the same period coexist as two SEPARATE active rows — both correctly contribute to
   * the annual base, since together they represent that month's ACTUAL combined income (the
   * combined-gross TER interplay already reconciles their withholding at compute time, {@code
   * GrossToNetCalculator}'s {@code SiblingPeriodContext} branch).
   */
  String ACTIVE_RUN_PREDICATE =
      """
      pr.status = 'POSTED'
        AND pr.run_seq = (
              SELECT MAX(pr2.run_seq) FROM payroll_run pr2
               WHERE pr2.period = pr.period AND pr2.run_type = pr.run_type AND pr2.status = 'POSTED'
            )
      """;

  /** Every payslip line for a run (within the bound tenant). */
  List<PayslipLine> findByPayrollRunId(UUID payrollRunId);

  /**
   * The DISTINCT employee ids that have a {@code componentKey} payslip line on run {@code
   * payrollRunId} (P7 review W4) — {@code ExpenseClaimPayrollLinker#markReimbursedAndEmit} uses
   * this to settle ONLY the claims whose employee actually received an {@code
   * EXPENSE_REIMBURSEMENT} line this run; a claim whose employee was SKIPPED (e.g. a
   * currency-mismatched linked total, {@code PayrollRunWriter#reimbursementInfoByEmployee}) must
   * stay {@code APPROVED}+linked rather than be settled for money that employee never actually
   * received via this run. A scalar-column query (a single {@code UUID} column needs no projection
   * interface).
   */
  @Query(
      value =
          "SELECT DISTINCT employee_id FROM payslip_line WHERE payroll_run_id = :payrollRunId"
              + " AND component_key = :componentKey",
      nativeQuery = true)
  List<UUID> findDistinctEmployeeIdsByPayrollRunIdAndComponentKey(
      @Param("payrollRunId") UUID payrollRunId, @Param("componentKey") String componentKey);

  /** Every payslip line for a run + employee (within the bound tenant). */
  List<PayslipLine> findByPayrollRunIdAndEmployeeId(UUID payrollRunId, UUID employeeId);

  /**
   * Every ACTIVE payslip line for one employee across a fiscal year, EXCLUDING {@code
   * excludePeriod} (the run passes its own period so it never sums against lines it is itself about
   * to produce) — the December/final-month Art-17 true-up's history read (Track P phase P3). {@code
   * yearPrefix} is the 4-digit year ({@code "2026"}); {@code period LIKE yearPrefix || '-%'}
   * matches every {@code "YYYY-MM"} period in that year.
   *
   * <p><strong>Deliberately a FULL-ENTITY query, not a projection (CODE-STRUCTURE.md §3.3
   * exception, documented).</strong> §3.3's rule — "a read path selects only the columns it needs
   * into a projection interface, never a full entity" — targets a path that serves a caller outside
   * this aggregate (an API response, a display list). This is neither: {@code amount_enc} / {@code
   * calc_basis_enc} are PII-ciphertext (rule 6, {@link
   * id.co.nativeapp.employee.payroll.domain.MoneyPiiConverter}), so no SQL {@code SUM}/aggregate
   * over the encrypted column is possible — decryption happens ONLY through the managed entity's
   * {@code @Convert} converter. This is therefore an internal COMPUTE path exactly like the
   * existing write-path exception §3.3 already carves out for {@code findById}/{@code save} (“the
   * whole aggregate is needed to mutate/derive from it”): no controller ever calls this method:
   * only {@code PayrollRunWriter}, which decrypts every line via {@link PayslipLine#getAmount()}
   * and Money-sums them in memory into an {@code AnnualContext} — the amounts never reach a
   * projection, a DTO, a log, or an event (rule 6 preserved end-to-end).
   */
  @Query(
      value =
          """
          SELECT pl.*
            FROM payslip_line pl
            JOIN payroll_run pr ON pr.id = pl.payroll_run_id
           WHERE pl.employee_id = :employeeId
             AND pr.period LIKE (:yearPrefix || '-%')
             AND pr.period <> :excludePeriod
             AND \s"""
              + ACTIVE_RUN_PREDICATE
              + """
           ORDER BY pr.period ASC, pl.component_key ASC
          """,
      nativeQuery = true)
  List<PayslipLine> findActiveLinesForEmployeeYear(
      @Param("employeeId") UUID employeeId,
      @Param("yearPrefix") String yearPrefix,
      @Param("excludePeriod") String excludePeriod);

  /**
   * The DISTINCT ACTIVE periods (see {@link #ACTIVE_RUN_PREDICATE}) one employee has payslip lines
   * in for a fiscal year, excluding {@code excludePeriod} — feeds {@code
   * AnnualContext.monthsInYear} ({@code size() + 1}, Track P phase P3). A scalar-column query (not
   * a projection interface — a single {@code String} column needs none, mirroring {@code
   * StatutoryRuleRepository#findDistinctProvenances}).
   */
  @Query(
      value =
          """
          SELECT DISTINCT pr.period
            FROM payslip_line pl
            JOIN payroll_run pr ON pr.id = pl.payroll_run_id
           WHERE pl.employee_id = :employeeId
             AND pr.period LIKE (:yearPrefix || '-%')
             AND pr.period <> :excludePeriod
             AND \s"""
              + ACTIVE_RUN_PREDICATE,
      nativeQuery = true)
  List<String> findActivePriorPeriodsForEmployeeYear(
      @Param("employeeId") UUID employeeId,
      @Param("yearPrefix") String yearPrefix,
      @Param("excludePeriod") String excludePeriod);

  /**
   * The ACTIVE (see {@link #ACTIVE_RUN_PREDICATE}) payslip lines for one employee, ONE {@code
   * period}, from the run of the given {@code runType} ONLY — Track P Phase P8 (ADR 0035), the
   * THR-month TER interplay's "sibling" lookup: {@code PayrollRunWriter#siblingPeriodContext} calls
   * this with the OTHER {@link id.co.nativeapp.employee.payroll.domain.RunType} from the run
   * currently calculating, to fold an already-POSTED sibling run's gross bruto / withheld PPh21 for
   * the SAME period into the combined-gross TER computation ({@code GrossToNetCalculator}'s {@code
   * SiblingPeriodContext} branch). Empty when no sibling run is active for this period — the
   * overwhelming common case, and every run before a company ever activates THR.
   *
   * <p>Deliberately a FULL-ENTITY query, not a projection — see {@link
   * #findActiveLinesForEmployeeYear}'s javadoc for the identical rationale (PII ciphertext columns,
   * an internal compute path only {@code PayrollRunWriter} calls, never a controller).
   */
  @Query(
      value =
          """
          SELECT pl.*
            FROM payslip_line pl
            JOIN payroll_run pr ON pr.id = pl.payroll_run_id
           WHERE pl.employee_id = :employeeId
             AND pr.period = :period
             AND pr.run_type = :runType
             AND \s"""
              + ACTIVE_RUN_PREDICATE,
      nativeQuery = true)
  List<PayslipLine> findActiveLinesForEmployeePeriodAndRunType(
      @Param("employeeId") UUID employeeId,
      @Param("period") String period,
      @Param("runType") String runType);

  /**
   * Every ACTIVE (see {@link #ACTIVE_RUN_PREDICATE}) payslip line for one employee across a WHOLE
   * fiscal year — {@code yearPrefix} matches every {@code "YYYY-MM"} period in that year, nothing
   * excluded. A convenience default method over {@link #findActiveLinesForEmployeeYear(UUID,
   * String, String)} passing {@code excludePeriod = ""}: no real {@code payroll_run.period} is ever
   * the empty string (it is always {@code "YYYY-MM"}), so the {@code pr.period <> :excludePeriod}
   * predicate excludes nothing — reusing the SAME native query rather than a second hand-copied
   * one. Track P phase P9's {@code PayrollReportReader} (the 1721-A1 annual export) is the first
   * caller that wants the full year, not "every month before this one".
   */
  default List<PayslipLine> findActiveLinesForEmployeeYear(UUID employeeId, String yearPrefix) {
    return findActiveLinesForEmployeeYear(employeeId, yearPrefix, "");
  }

  /**
   * The DISTINCT ACTIVE periods (see {@link #ACTIVE_RUN_PREDICATE}) one employee has payslip lines
   * in for a WHOLE fiscal year — the {@code findActiveLinesForEmployeeYear(UUID, String)}
   * companion, same {@code excludePeriod = ""} reuse trick. Track P phase P9's 1721-A1 export uses
   * {@code .size()} directly as {@code monthsInYear} (no "+1": nothing was excluded, unlike {@link
   * id.co.nativeapp.employee.payroll.service.PayrollRunWriter#buildAnnualContext}, which excludes
   * the run's OWN about-to-be-produced period).
   */
  default List<String> findActivePeriodsForEmployeeYear(UUID employeeId, String yearPrefix) {
    return findActivePriorPeriodsForEmployeeYear(employeeId, yearPrefix, "");
  }

  /**
   * Every ACTIVE (see {@link #ACTIVE_RUN_PREDICATE}) payslip line for the WHOLE company for one
   * {@code period}, across every employee and every {@link
   * id.co.nativeapp.employee.payroll.domain.RunType} (a REGULAR and a THR run for the same period
   * are BOTH active — see the predicate's own javadoc) — Track P phase P9's company-wide statutory
   * reports ({@code pph21-monthly}, {@code bpjs-summary}). Deliberately a FULL-ENTITY query, not a
   * projection — identical rationale to {@link #findActiveLinesForEmployeeYear(UUID, String,
   * String)}'s javadoc (PII-ciphertext columns, decrypted only through the managed entity's
   * {@code @Convert}; the only callers are {@code PayrollRunWriter} and {@code
   * PayrollReportReader}, never a controller).
   */
  @Query(
      value =
          """
          SELECT pl.*
            FROM payslip_line pl
            JOIN payroll_run pr ON pr.id = pl.payroll_run_id
           WHERE pr.period = :period
             AND \s"""
              + ACTIVE_RUN_PREDICATE
              + """
           ORDER BY pl.employee_id ASC, pl.component_key ASC
          """,
      nativeQuery = true)
  List<PayslipLine> findActiveLinesForPeriod(@Param("period") String period);

  /**
   * The DISTINCT employee ids with at least one ACTIVE (see {@link #ACTIVE_RUN_PREDICATE}) payslip
   * line anywhere in a fiscal year — Track P phase P9's 1721-A1 export's employee roster for the
   * year (every employee who was actually paid at least once, across every run type).
   */
  @Query(
      value =
          """
          SELECT DISTINCT pl.employee_id
            FROM payslip_line pl
            JOIN payroll_run pr ON pr.id = pl.payroll_run_id
           WHERE pr.period LIKE (:yearPrefix || '-%')
             AND \s"""
              + ACTIVE_RUN_PREDICATE,
      nativeQuery = true)
  List<UUID> findDistinctEmployeeIdsForYear(@Param("yearPrefix") String yearPrefix);

  /**
   * A run's payslip index — one row per employee (joined for the display name), counts only. The
   * amount ciphertext columns are never selected on this path (rule 6). RLS applies to both tables.
   */
  @Query(
      value =
          """
          SELECT pl.employee_id             AS employee_id,
                 e.full_name                AS full_name,
                 COUNT(*)                   AS line_count,
                 BOOL_OR(pl.is_illustrative) AS illustrative
            FROM payslip_line pl
            JOIN employee e ON e.id = pl.employee_id
           WHERE pl.payroll_run_id = :runId
           GROUP BY pl.employee_id, e.full_name
           ORDER BY e.full_name
          """,
      nativeQuery = true)
  List<PayslipIndexView> findPayslipIndex(@Param("runId") UUID runId);

  /**
   * The caller's OWN payslip index — run headers for every ACTIVE (see {@link
   * #ACTIVE_RUN_PREDICATE}) run carrying the employee's lines, newest first. NO amount columns are
   * selected (the encrypted amounts are only decrypted on the own-row detail read). RLS applies to
   * both tables (rule 5).
   *
   * <p><strong>P10 review C1 fix.</strong> Before this fix the query had NEITHER {@code status =
   * 'POSTED'} NOR the {@code run_seq}-supersession filter — every run that ever produced a line for
   * this employee was listed, so (1) a superseded correction re-run's stale {@code run_seq} 1 lines
   * summed ALONGSIDE the correct {@code run_seq} 2 lines (double-counted gross/PPh21 downstream —
   * Me's payslip list AND the payslip YTD card, which sums every listed header's detail), and (2) a
   * {@code CALCULATED}-but-not-{@code POSTED} (or {@code FAILED}-after-calculate) run's already-
   * persisted lines appeared as if final. {@link #ACTIVE_RUN_PREDICATE} is scoped per {@code
   * (period, run_type)} (Track P Phase P8), so a REGULAR and a THR run for the same period both
   * still legitimately appear — only same-{@code run_type} supersession and non-{@code POSTED}
   * status are excluded.
   */
  @Query(
      value =
          """
          SELECT pl.payroll_run_id           AS run_id,
                 pr.period                   AS period,
                 pr.run_seq                  AS run_seq,
                 pr.posted_at                AS posted_at,
                 COUNT(*)                    AS line_count,
                 BOOL_OR(pl.is_illustrative) AS illustrative
            FROM payslip_line pl
            JOIN payroll_run pr ON pr.id = pl.payroll_run_id
           WHERE pl.employee_id = :employeeId
             AND (CAST(:period AS text) IS NULL OR pr.period = CAST(:period AS text))
             AND \s"""
              + ACTIVE_RUN_PREDICATE
              + """
           GROUP BY pl.payroll_run_id, pr.period, pr.run_seq, pr.posted_at
           ORDER BY pr.period DESC, pr.run_seq DESC
          """,
      nativeQuery = true)
  List<MyPayslipHeaderView> findMyPayslipHeaders(
      @Param("employeeId") UUID employeeId, @Param("period") String period);
}
