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
