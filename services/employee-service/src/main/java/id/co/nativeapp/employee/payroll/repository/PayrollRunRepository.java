package id.co.nativeapp.employee.payroll.repository;

import id.co.nativeapp.employee.payroll.domain.PayrollRun;
import id.co.nativeapp.employee.payroll.projection.PayrollRunView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data port for {@link PayrollRun}. RLS-scoped (rule 5). A re-run of a period is a new
 * {@code run_seq} (max+1), guarded by the UNIQUE (company_id, period, run_seq).
 */
public interface PayrollRunRepository extends JpaRepository<PayrollRun, UUID> {

  /**
   * All runs for a period (within the bound tenant), newest run_seq first — to compute the next
   * run_seq for a correction re-run.
   */
  List<PayrollRun> findByPeriodOrderByRunSeqDesc(String period);

  /** A specific (period, run_seq) within the bound tenant. */
  Optional<PayrollRun> findByPeriodAndRunSeq(String period, int runSeq);

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
                 pr.status                            AS status,
                 pr.base_currency                     AS base_currency,
                 pr.gross_total_minor                 AS gross_total_minor,
                 pr.employee_deduction_total_minor    AS employee_deduction_total_minor,
                 pr.employer_contribution_total_minor AS employer_contribution_total_minor,
                 pr.net_total_minor                   AS net_total_minor,
                 pr.uses_illustrative_rules           AS uses_illustrative_rules,
                 pr.posted_at                         AS posted_at
            FROM payroll_run pr
           WHERE pr.period = :period
           ORDER BY pr.run_seq DESC
          """,
      nativeQuery = true)
  List<PayrollRunView> findRunViewsByPeriod(@Param("period") String period);
}
