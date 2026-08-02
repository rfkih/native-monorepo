package id.co.nativeapp.employee.expense.service;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.expense.domain.ClaimNotFoundException;
import id.co.nativeapp.employee.expense.dto.ExpenseClaimResponse;
import id.co.nativeapp.employee.expense.dto.ExpenseClaimSummaryResponse;
import id.co.nativeapp.employee.expense.dto.MyExpenseClaimResponse;
import id.co.nativeapp.employee.expense.projection.ExpenseClaimSummaryView;
import id.co.nativeapp.employee.expense.projection.MyExpenseClaimView;
import id.co.nativeapp.employee.expense.repository.ExpenseClaimRepository;
import id.co.nativeapp.employee.me.domain.EmployeeNotLinkedException;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side for expense claims: the caller's own list/detail (resolved strictly from {@link
 * TenantContext} — the {@code /me} idiom, rule 5) and the manager-facing tenant-wide list/detail.
 * Projection-to-DTO mapping happens here in the service layer (CODE-STRUCTURE §3.3).
 */
@Service
public class ExpenseClaimReader {

  /**
   * The sentinel single-element scope list when no org-unit filter is supplied (never a real empty
   * IN-list).
   */
  private static final List<UUID> NO_SCOPE = List.of(new UUID(0L, 0L));

  private final ExpenseClaimRepository claimRepository;
  private final EmployeeRepository employeeRepository;

  public ExpenseClaimReader(
      ExpenseClaimRepository claimRepository, EmployeeRepository employeeRepository) {
    this.claimRepository = claimRepository;
    this.employeeRepository = employeeRepository;
  }

  /** The caller's own claims, newest-updated first. */
  @Transactional(readOnly = true)
  public List<MyExpenseClaimResponse> myClaims() {
    Employee me = resolveMe();
    return claimRepository.findMyClaims(me.getId()).stream()
        .map(ExpenseClaimReader::toMyResponse)
        .toList();
  }

  /**
   * One of the caller's own claims.
   *
   * @throws ClaimNotFoundException if unknown, or not the caller's own (→ 404, anti-enumeration)
   */
  @Transactional(readOnly = true)
  public ExpenseClaimResponse myClaim(UUID claimId) {
    Employee me = resolveMe();
    return claimRepository
        .findById(claimId)
        .filter(c -> c.getEmployeeId().equals(me.getId()))
        .map(ExpenseClaimResponse::from)
        .orElseThrow(() -> new ClaimNotFoundException(claimId));
  }

  /**
   * The manager-facing tenant-wide claim list, newest-updated first.
   *
   * @param status an optional exact status filter (case-insensitive)
   * @param orgUnitIds an optional org-unit scope; null/empty = the whole tenant
   */
  @Transactional(readOnly = true)
  public List<ExpenseClaimSummaryResponse> forManager(String status, List<UUID> orgUnitIds) {
    boolean hasUnits = orgUnitIds != null && !orgUnitIds.isEmpty();
    String normalizedStatus =
        (status == null || status.isBlank()) ? null : status.strip().toUpperCase(Locale.ROOT);
    return claimRepository
        .findForManager(normalizedStatus, hasUnits, hasUnits ? orgUnitIds : NO_SCOPE)
        .stream()
        .map(ExpenseClaimReader::toSummaryResponse)
        .toList();
  }

  /**
   * One claim (manager-facing — any claim visible in the bound tenant).
   *
   * @throws ClaimNotFoundException if unknown in the bound tenant (→ 404)
   */
  @Transactional(readOnly = true)
  public ExpenseClaimResponse one(UUID claimId) {
    return claimRepository
        .findById(claimId)
        .map(ExpenseClaimResponse::from)
        .orElseThrow(() -> new ClaimNotFoundException(claimId));
  }

  private Employee resolveMe() {
    String actor = TenantContext.require().actor();
    return employeeRepository.findByUserId(actor).orElseThrow(EmployeeNotLinkedException::new);
  }

  private static MyExpenseClaimResponse toMyResponse(MyExpenseClaimView v) {
    return new MyExpenseClaimResponse(
        v.getId(),
        v.getStatus(),
        v.getAmountMinor(),
        v.getAmountCurrency().strip(),
        v.getExpenseDate(),
        v.getMerchant(),
        v.getCategoryName(),
        v.getReimbursementMethod(),
        v.getDecidedBy(),
        v.getDecidedAt(),
        v.getDecisionComment());
  }

  private static ExpenseClaimSummaryResponse toSummaryResponse(ExpenseClaimSummaryView v) {
    return new ExpenseClaimSummaryResponse(
        v.getId(),
        v.getEmployeeId(),
        v.getEmployeeName(),
        v.getStatus(),
        v.getAmountMinor(),
        v.getAmountCurrency().strip(),
        v.getExpenseDate(),
        v.getMerchant(),
        v.getCategoryName(),
        v.getOrgUnitId(),
        v.getReimbursementMethod(),
        v.getDecidedBy(),
        v.getDecidedAt(),
        v.getDecisionComment());
  }
}
