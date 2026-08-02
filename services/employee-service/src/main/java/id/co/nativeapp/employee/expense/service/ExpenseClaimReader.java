package id.co.nativeapp.employee.expense.service;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.expense.domain.ClaimNotFoundException;
import id.co.nativeapp.employee.expense.domain.ClaimStatus;
import id.co.nativeapp.employee.expense.dto.ExpenseClaimResponse;
import id.co.nativeapp.employee.expense.dto.ExpenseClaimSummaryResponse;
import id.co.nativeapp.employee.expense.dto.MyExpenseClaimResponse;
import id.co.nativeapp.employee.expense.dto.OrgUnitExpenseSummaryResponse;
import id.co.nativeapp.employee.expense.dto.PageResponse;
import id.co.nativeapp.employee.expense.projection.ExpenseClaimSummaryView;
import id.co.nativeapp.employee.expense.projection.MyExpenseClaimView;
import id.co.nativeapp.employee.expense.repository.ExpenseClaimRepository;
import id.co.nativeapp.employee.me.domain.EmployeeNotLinkedException;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side for expense claims: the caller's own list/detail (resolved strictly from {@link
 * TenantContext} — the {@code /me} idiom, rule 5) and the manager-facing tenant-wide list/detail.
 * Projection-to-DTO mapping happens here in the service layer (CODE-STRUCTURE §3.3).
 *
 * <p><strong>Pagination (ENGINEERING-STANDARDS §1.3, W2 code review).</strong> Both list reads
 * return a {@link PageResponse} envelope, never a bare array. {@code size} is bounded to {@code [1,
 * MAX_PAGE_SIZE]} (default {@link #DEFAULT_PAGE_SIZE}) and {@code page} floored at {@code 0} here —
 * silently clamped, not rejected, so a slightly-out-of-range client request degrades gracefully
 * instead of 400ing.
 */
@Service
public class ExpenseClaimReader {

  /** The default page size when the caller omits {@code size}. */
  public static final int DEFAULT_PAGE_SIZE = 25;

  /** The maximum page size a caller may request (ENGINEERING-STANDARDS §1.3 cap). */
  public static final int MAX_PAGE_SIZE = 100;

  /**
   * The sentinel single-element scope list when no org-unit filter is supplied (never a real empty
   * IN-list).
   */
  private static final List<UUID> NO_SCOPE = List.of(new UUID(0L, 0L));

  /**
   * The {@code YYYY-MM} shape {@link #summary} validates an optional {@code period} against
   * (mirrors {@code UnitPnlController}'s stricter month-range pattern).
   */
  private static final Pattern PERIOD_PATTERN = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

  private final ExpenseClaimRepository claimRepository;
  private final EmployeeRepository employeeRepository;

  public ExpenseClaimReader(
      ExpenseClaimRepository claimRepository, EmployeeRepository employeeRepository) {
    this.claimRepository = claimRepository;
    this.employeeRepository = employeeRepository;
  }

  /** One page of the caller's own claims, newest-updated first. */
  @Transactional(readOnly = true)
  public PageResponse<MyExpenseClaimResponse> myClaims(Integer page, Integer size) {
    Employee me = resolveMe();
    int boundedPage = boundPage(page);
    int boundedSize = boundSize(size);
    long offset = (long) boundedPage * boundedSize;

    long totalElements = claimRepository.countMyClaims(me.getId());
    List<MyExpenseClaimResponse> content =
        claimRepository.findMyClaims(me.getId(), boundedSize, offset).stream()
            .map(ExpenseClaimReader::toMyResponse)
            .toList();
    return PageResponse.of(content, boundedPage, boundedSize, totalElements);
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
   * One page of the manager-facing tenant-wide claim list — SUBMITTED-first, then newest-updated
   * (pending work can never fall off the end of a page).
   *
   * @param status an optional exact status filter (case-insensitive)
   * @param orgUnitIds an optional org-unit scope; null/empty = the whole tenant
   * @throws IllegalArgumentException if {@code status} is not a known {@link ClaimStatus} (→ 400,
   *     S4 code review — an unrecognised filter must not silently resolve to an empty page)
   */
  @Transactional(readOnly = true)
  public PageResponse<ExpenseClaimSummaryResponse> forManager(
      String status, List<UUID> orgUnitIds, Integer page, Integer size) {
    boolean hasUnits = orgUnitIds != null && !orgUnitIds.isEmpty();
    String normalizedStatus = normalizeStatus(status);
    int boundedPage = boundPage(page);
    int boundedSize = boundSize(size);
    long offset = (long) boundedPage * boundedSize;
    List<UUID> scope = hasUnits ? orgUnitIds : NO_SCOPE;

    long totalElements = claimRepository.countForManager(normalizedStatus, hasUnits, scope);
    List<ExpenseClaimSummaryResponse> content =
        claimRepository
            .findForManager(normalizedStatus, hasUnits, scope, boundedSize, offset)
            .stream()
            .map(ExpenseClaimReader::toSummaryResponse)
            .toList();
    return PageResponse.of(content, boundedPage, boundedSize, totalElements);
  }

  /**
   * The org-unit hub's Expenses tab rollup (Phase E8): per-category totals (APPROVED/REIMBURSED
   * only — the recognised subset, ADR 0030 §2), claim counts by every status, and the
   * approved+reimbursed grand total (the category rows' sum). {@code orgUnitIds} follows the same
   * sentinel-list idiom as {@link #forManager}; {@code period} is an optional {@code YYYY-MM}
   * filter on {@code expense_date}.
   *
   * @throws IllegalArgumentException if {@code period} is present but not {@code YYYY-MM} (→ 400)
   * @throws IllegalStateException if the category rows mix currencies — never expected while v1's
   *     single-currency-per-tenant invariant holds (see {@code ExpenseClaimRepository} Javadoc);
   *     failing loud beats silently mis-summing money (rule 8, mirrors {@code UnitPnlReader})
   */
  @Transactional(readOnly = true)
  public OrgUnitExpenseSummaryResponse summary(List<UUID> orgUnitIds, String period) {
    String normalizedPeriod = normalizePeriod(period);
    boolean hasUnits = orgUnitIds != null && !orgUnitIds.isEmpty();
    List<UUID> scope = hasUnits ? orgUnitIds : NO_SCOPE;

    List<OrgUnitExpenseSummaryResponse.CategoryTotal> byCategory =
        claimRepository.summarizeByCategory(hasUnits, scope, normalizedPeriod).stream()
            .map(
                v ->
                    new OrgUnitExpenseSummaryResponse.CategoryTotal(
                        v.getCategoryName(), v.getTotalMinor(), v.getCurrency().strip()))
            .toList();
    List<OrgUnitExpenseSummaryResponse.StatusCount> byStatus =
        claimRepository.summarizeByStatus(hasUnits, scope, normalizedPeriod).stream()
            .map(v -> new OrgUnitExpenseSummaryResponse.StatusCount(v.getStatus(), v.getCount()))
            .toList();

    long grandTotal = 0L;
    String currency = null;
    for (OrgUnitExpenseSummaryResponse.CategoryTotal row : byCategory) {
      grandTotal = Math.addExact(grandTotal, row.totalMinor());
      if (currency != null && !currency.equals(row.currency())) {
        throw new IllegalStateException(
            "Mixed expense-claim currencies in one org-unit summary — single-currency-per-tenant"
                + " invariant violated");
      }
      currency = row.currency();
    }
    return new OrgUnitExpenseSummaryResponse(byCategory, byStatus, grandTotal, currency);
  }

  /**
   * Normalizes and validates an optional {@code period} filter — {@code null}/blank means "no
   * filter"; anything else must be {@code YYYY-MM} (a real month, {@code 01}-{@code 12}).
   */
  private static String normalizePeriod(String period) {
    if (period == null || period.isBlank()) {
      return null;
    }
    String trimmed = period.strip();
    if (!PERIOD_PATTERN.matcher(trimmed).matches()) {
      throw new IllegalArgumentException("period must be YYYY-MM: " + period);
    }
    return trimmed;
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

  /**
   * Normalizes and validates an optional status filter against {@link ClaimStatus} — {@code null}/
   * blank means "no filter"; an unrecognised value is rejected rather than silently matching
   * nothing (S4 code review).
   */
  private static String normalizeStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    String normalized = status.strip().toUpperCase(Locale.ROOT);
    try {
      ClaimStatus.valueOf(normalized);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown expense claim status: " + status, e);
    }
    return normalized;
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
