package id.co.nativeapp.employee.payroll;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data port for {@link PayrollRun}. Derived queries only; RLS-scoped (rule 5). A re-run of a
 * period is a new {@code run_seq} (max+1), guarded by the UNIQUE (company_id, period, run_seq).
 */
public interface PayrollRunRepository extends JpaRepository<PayrollRun, UUID> {

  /**
   * All runs for a period (within the bound tenant), newest run_seq first — to compute the next
   * run_seq for a correction re-run.
   */
  List<PayrollRun> findByPeriodOrderByRunSeqDesc(String period);

  /** A specific (period, run_seq) within the bound tenant. */
  Optional<PayrollRun> findByPeriodAndRunSeq(String period, int runSeq);
}
