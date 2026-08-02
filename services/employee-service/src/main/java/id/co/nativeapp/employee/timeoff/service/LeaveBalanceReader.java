package id.co.nativeapp.employee.timeoff.service;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.domain.EmployeeNotFoundException;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.me.domain.EmployeeNotLinkedException;
import id.co.nativeapp.employee.timeoff.domain.LeaveBalance;
import id.co.nativeapp.employee.timeoff.dto.LeaveBalanceResponse;
import id.co.nativeapp.employee.timeoff.dto.LeaveBalanceRowResponse;
import id.co.nativeapp.employee.timeoff.dto.PageResponse;
import id.co.nativeapp.employee.timeoff.projection.LeaveBalanceRowView;
import id.co.nativeapp.employee.timeoff.repository.LeaveBalanceRepository;
import id.co.nativeapp.employee.timeoff.repository.LeaveRequestRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The derived leave-balance read side (ADR 0033 §4): {@code granted_days}/{@code adjustment_days}
 * come from the tenant's {@code leave_balance} row (defaulted when absent — {@link
 * LeaveBalance#DEFAULT_GRANTED_DAYS}/0); {@code used_days} is ALWAYS derived by summing {@code
 * days} across the employee's APPROVED {@code ANNUAL} requests for the year — this class never
 * reads or writes a mutable "days used" counter (the deliberate ADR 0033 decision: immutable
 * approved requests are the single source of truth for usage, so a manual counter can never drift
 * from them).
 */
@Service
public class LeaveBalanceReader {

  /** The default page size for the manager-facing balances list. */
  public static final int DEFAULT_PAGE_SIZE = 50;

  /** The maximum page size a caller may request. */
  public static final int MAX_PAGE_SIZE = 200;

  private final LeaveBalanceRepository balanceRepository;
  private final LeaveRequestRepository requestRepository;
  private final EmployeeRepository employeeRepository;
  private final Clock clock;

  public LeaveBalanceReader(
      LeaveBalanceRepository balanceRepository,
      LeaveRequestRepository requestRepository,
      EmployeeRepository employeeRepository,
      Clock clock) {
    this.balanceRepository = balanceRepository;
    this.requestRepository = requestRepository;
    this.employeeRepository = employeeRepository;
    this.clock = clock;
  }

  /** The caller's own derived balance for {@code year} (defaults to the current year). */
  @Transactional(readOnly = true)
  public LeaveBalanceResponse myBalance(Integer year) {
    Employee me = resolveMe();
    int resolvedYear = resolveYear(year);
    return balanceFor(me.getId(), resolvedYear);
  }

  /** One page of the manager-facing per-employee derived balance list for {@code year}. */
  @Transactional(readOnly = true)
  public PageResponse<LeaveBalanceRowResponse> forManager(
      Integer year, Integer page, Integer size) {
    int resolvedYear = resolveYear(year);
    int boundedPage = boundPage(page);
    int boundedSize = boundSize(size);
    long offset = (long) boundedPage * boundedSize;

    long totalElements = balanceRepository.countEmployees();
    List<LeaveBalanceRowResponse> content =
        balanceRepository.findRowsForYear(resolvedYear, boundedSize, offset).stream()
            .map(v -> toRowResponse(v, resolvedYear))
            .toList();
    return PageResponse.of(content, boundedPage, boundedSize, totalElements);
  }

  /**
   * One employee's derived balance row — the manager-facing single-employee read {@code
   * LeaveBalanceController#adjust} returns after a write, sourced from the SAME derivation as every
   * other endpoint (never re-computed ad hoc).
   *
   * @throws EmployeeNotFoundException if {@code employeeId} is not visible in the bound tenant (→
   *     404)
   */
  @Transactional(readOnly = true)
  public LeaveBalanceRowResponse forEmployee(UUID employeeId, Integer year) {
    Employee employee =
        employeeRepository
            .findById(employeeId)
            .orElseThrow(() -> new EmployeeNotFoundException(employeeId));
    int resolvedYear = resolveYear(year);
    LeaveBalanceResponse balance = balanceFor(employeeId, resolvedYear);
    return LeaveBalanceRowResponse.of(
        employeeId,
        employee.getFullName(),
        resolvedYear,
        balance.grantedDays(),
        balance.adjustmentDays(),
        balance.usedDays());
  }

  private LeaveBalanceResponse balanceFor(java.util.UUID employeeId, int year) {
    int granted =
        balanceRepository
            .findByEmployeeIdAndYear(employeeId, year)
            .map(LeaveBalance::getGrantedDays)
            .orElse(LeaveBalance.DEFAULT_GRANTED_DAYS);
    int adjustment =
        balanceRepository
            .findByEmployeeIdAndYear(employeeId, year)
            .map(LeaveBalance::getAdjustmentDays)
            .orElse(0);
    int used = requestRepository.sumApprovedAnnualDaysForYear(employeeId, year);
    return LeaveBalanceResponse.of(year, granted, adjustment, used);
  }

  private int resolveYear(Integer year) {
    return year == null ? LocalDate.now(clock).getYear() : year;
  }

  private static int boundPage(Integer page) {
    return page == null || page < 0 ? 0 : page;
  }

  private static int boundSize(Integer size) {
    if (size == null || size < 1) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private Employee resolveMe() {
    String actor = TenantContext.require().actor();
    return employeeRepository.findByUserId(actor).orElseThrow(EmployeeNotLinkedException::new);
  }

  private static LeaveBalanceRowResponse toRowResponse(LeaveBalanceRowView v, int year) {
    return LeaveBalanceRowResponse.of(
        v.getEmployeeId(),
        v.getEmployeeName(),
        year,
        v.getGrantedDays(),
        v.getAdjustmentDays(),
        v.getUsedDays());
  }
}
