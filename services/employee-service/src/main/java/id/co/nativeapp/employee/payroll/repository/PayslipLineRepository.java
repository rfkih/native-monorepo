package id.co.nativeapp.employee.payroll.repository;

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

  /** Every payslip line for a run (within the bound tenant). */
  List<PayslipLine> findByPayrollRunId(UUID payrollRunId);

  /** Every payslip line for a run + employee (within the bound tenant). */
  List<PayslipLine> findByPayrollRunIdAndEmployeeId(UUID payrollRunId, UUID employeeId);

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
}
