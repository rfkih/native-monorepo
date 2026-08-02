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
   * own period. This is a correlated subselect, not a join against a separate "superseded" flag —
   * {@code payroll_run} carries no such column (unlike finance's {@code payroll_run_ledger.state})
   * — so "active" is defined structurally: a higher {@code run_seq} that reached POSTED supersedes
   * every lower {@code run_seq} for the same period; a run that never reached POSTED (still
   * CALCULATED, or FAILED) never supersedes anything and is itself excluded by the outer {@code
   * status = 'POSTED'}. This MUST exactly mirror finance's supersession semantics ({@code
   * PayrollRunLedgerRepository#findActivePriorRuns}/{@code existsActiveHigherRun}: a higher run_seq
   * supersedes lower ones; a superseded run's postings are DEAD) so the December true-up sums the
   * SAME set of runs finance's books already reflect. A single {@code interface}-constant (a
   * compile-time constant expression) so the two queries below can never drift apart from each
   * other.
   *
   * <p><strong>⚠ P8 LANDMINE — MUST add {@code run_type} scoping here before THR ships (ADR
   * 0034).</strong> {@code payroll_run} is currently keyed {@code (company_id, period, run_seq)}
   * only; Track P phase P8 lands per-{@code (period, run_type)} sequences (a THR off-cycle run and
   * the REGULAR run for the same period each get their OWN {@code run_seq} series). The moment that
   * lands, this predicate's {@code MAX(pr2.run_seq) WHERE pr2.period = pr.period} — with NO {@code
   * run_type} in the WHERE — would let a THR run's {@code run_seq} supersede a REGULAR run's (or
   * vice versa) for the SAME period, and would leak THR payslip lines (a different, non-monthly
   * income shape) into the December Art-17 annual base. P8 MUST add {@code AND pr.run_type =
   * pr2.run_type} (or equivalent) to both the outer join predicate and the correlated subselect the
   * moment {@code run_type} exists on this table — tracked on the P8 task, not just here.
   */
  String ACTIVE_RUN_PREDICATE =
      """
      pr.status = 'POSTED'
        AND pr.run_seq = (
              SELECT MAX(pr2.run_seq) FROM payroll_run pr2
               WHERE pr2.period = pr.period AND pr2.status = 'POSTED'
            )
      """;

  /** Every payslip line for a run (within the bound tenant). */
  List<PayslipLine> findByPayrollRunId(UUID payrollRunId);

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
   * The caller's OWN payslip index — run headers for every run carrying the employee's lines,
   * newest first. NO amount columns are selected (the encrypted amounts are only decrypted on the
   * own-row detail read). RLS applies to both tables (rule 5).
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
           GROUP BY pl.payroll_run_id, pr.period, pr.run_seq, pr.posted_at
           ORDER BY pr.period DESC, pr.run_seq DESC
          """,
      nativeQuery = true)
  List<MyPayslipHeaderView> findMyPayslipHeaders(
      @Param("employeeId") UUID employeeId, @Param("period") String period);
}
