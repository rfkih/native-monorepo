package id.co.nativeapp.employee.payroll.repository;

import id.co.nativeapp.employee.payroll.domain.PayrollRun;
import id.co.nativeapp.employee.payroll.domain.RunStatus;
import id.co.nativeapp.employee.payroll.domain.RunType;
import id.co.nativeapp.employee.payroll.projection.PayrollRunView;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data port for {@link PayrollRun}. RLS-scoped (rule 5). A re-run of a period is a new
 * {@code run_seq} (max+1) — Track P Phase P8 (ADR 0035) keys this PER {@code (period, run_type)},
 * guarded by the UNIQUE {@code (company_id, period, run_type, run_seq)} — so a THR run and a
 * REGULAR run for the same period get their OWN independent run_seq counters.
 */
public interface PayrollRunRepository extends JpaRepository<PayrollRun, UUID> {

  /**
   * All runs for a period (within the bound tenant), newest run_seq first — to compute the next
   * run_seq for a correction re-run. Legacy (pre-P8) — {@link
   * #findByPeriodAndRunTypeOrderByRunSeqDesc} is the run-type-aware successor {@code
   * PayrollRunWriter#nextRunSeq} actually uses now.
   */
  List<PayrollRun> findByPeriodOrderByRunSeqDesc(String period);

  /**
   * All runs for a {@code (period, runType)} (within the bound tenant), newest run_seq first —
   * Track P Phase P8: the run-type-scoped {@code nextRunSeq} source (a THR run and a REGULAR run
   * for the same period must never share a run_seq counter).
   */
  List<PayrollRun> findByPeriodAndRunTypeOrderByRunSeqDesc(String period, RunType runType);

  /**
   * Whether a run of {@code runType} reached {@code status} for {@code period} (within the bound
   * tenant) — Track P Phase P8's December-true-up residual WARN uses this to detect an ACTIVE THR
   * sibling the annual context does not yet incorporate (see {@code PayrollRunWriter}'s class
   * javadoc).
   */
  boolean existsByPeriodAndRunTypeAndStatus(String period, RunType runType, RunStatus status);

  /**
   * The console's per-period run list — company totals only (plaintext non-PII columns), a
   * projection read (CODE-STRUCTURE §3.3), newest run_seq first.
   */
  @Query(
      value =
          """
          SELECT pr.id                                AS id,
                 pr.period                            AS period,
                 pr.run_seq                           AS run_seq,
                 pr.run_type                          AS run_type,
                 pr.status                            AS status,
                 pr.base_currency                     AS base_currency,
                 pr.gross_total_minor                 AS gross_total_minor,
                 pr.employee_deduction_total_minor    AS employee_deduction_total_minor,
                 pr.employer_contribution_total_minor AS employer_contribution_total_minor,
                 pr.net_total_minor                   AS net_total_minor,
                 pr.uses_illustrative_rules           AS uses_illustrative_rules,
                 pr.posted_at                         AS posted_at,
                 pr.work_inputs_json::text            AS work_inputs_json
            FROM payroll_run pr
           WHERE pr.period = :period
           ORDER BY pr.run_seq DESC
          """,
      nativeQuery = true)
  List<PayrollRunView> findRunViewsByPeriod(@Param("period") String period);
}
