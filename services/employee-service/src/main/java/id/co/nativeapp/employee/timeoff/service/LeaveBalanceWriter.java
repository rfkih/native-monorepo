package id.co.nativeapp.employee.timeoff.service;

import id.co.nativeapp.employee.employee.domain.EmployeeNotFoundException;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.timeoff.domain.LeaveBalance;
import id.co.nativeapp.employee.timeoff.repository.LeaveBalanceRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} unit of work for a manager's {@code adjustment_days} correction
 * on an employee's {@link LeaveBalance} (ADR 0033 §4) — grants nothing physically beyond the
 * adjustment column; used days stay fully derived ({@code LeaveBalanceReader}). A distinct bean so
 * {@code @Transactional} + {@link RlsAutoApplyAspect} engage (rule 5).
 */
@Component
public class LeaveBalanceWriter {

  private final LeaveBalanceRepository balanceRepository;
  private final EmployeeRepository employeeRepository;

  public LeaveBalanceWriter(
      LeaveBalanceRepository balanceRepository, EmployeeRepository employeeRepository) {
    this.balanceRepository = balanceRepository;
    this.employeeRepository = employeeRepository;
  }

  /**
   * Replaces the {@code adjustment_days} for {@code (employeeId, year)}, creating the row (with the
   * default grant) if it does not exist yet.
   *
   * <p><strong>Accepted race (P7 review S3).</strong> This method takes NO advisory lock, unlike
   * {@code LeaveRequestWriter#approve}'s balance-sufficiency check (ADR 0033 §4). A manager
   * concurrently approving an ANNUAL request while another manager adjusts this same employee's
   * balance could interleave: the approve's "is there enough balance" read and this write are not
   * serialized against each other. This is an ACCEPTED residual (ADR 0033) — both actions are rare,
   * manager-only, and human-paced; a wrongly-approved request in that narrow window is corrected
   * the same way any other bad value is (a manual balance adjustment), not automatically.
   *
   * @throws EmployeeNotFoundException if {@code employeeId} is not visible in the bound tenant (→
   *     404)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public LeaveBalance adjust(UUID employeeId, int year, int adjustmentDays) {
    String tenant = TenantContext.require().companyId();
    if (employeeRepository.findById(employeeId).isEmpty()) {
      throw new EmployeeNotFoundException(employeeId);
    }
    LeaveBalance balance =
        balanceRepository
            .findByEmployeeIdAndYear(employeeId, year)
            .orElseGet(
                () -> {
                  LeaveBalance created = new LeaveBalance(employeeId, year);
                  created.setCompanyId(tenant);
                  return created;
                });
    balance.setAdjustmentDays(adjustmentDays);
    return balanceRepository.save(balance);
  }
}
