package id.co.nativeapp.employee.payroll.repository;

import id.co.nativeapp.employee.payroll.domain.PayslipLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data port for {@link PayslipLine}. Derived queries only; RLS-scoped (rule 5). Amounts are
 * encrypted ciphertext at rest — NOT queryable/sortable by value (rule 6, intentional).
 */
public interface PayslipLineRepository extends JpaRepository<PayslipLine, UUID> {

  /** Every payslip line for a run (within the bound tenant). */
  List<PayslipLine> findByPayrollRunId(UUID payrollRunId);

  /** Every payslip line for a run + employee (within the bound tenant). */
  List<PayslipLine> findByPayrollRunIdAndEmployeeId(UUID payrollRunId, UUID employeeId);
}
