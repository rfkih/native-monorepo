package id.co.nativeapp.employee.timeoff.service;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.me.domain.EmployeeNotLinkedException;
import id.co.nativeapp.employee.timeoff.domain.LeaveRequestNotFoundException;
import id.co.nativeapp.employee.timeoff.dto.LeaveRequestResponse;
import id.co.nativeapp.employee.timeoff.dto.LeaveRequestSummaryResponse;
import id.co.nativeapp.employee.timeoff.dto.MyLeaveRequestResponse;
import id.co.nativeapp.employee.timeoff.dto.PageResponse;
import id.co.nativeapp.employee.timeoff.projection.LeaveRequestSummaryView;
import id.co.nativeapp.employee.timeoff.projection.MyLeaveRequestView;
import id.co.nativeapp.employee.timeoff.repository.LeaveRequestRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side for leave requests: the caller's own list/detail (resolved strictly from {@link
 * TenantContext} — the {@code /me} idiom, rule 5) and the manager-facing tenant-wide list/detail.
 * Projection-to-DTO mapping happens here in the service layer (CODE-STRUCTURE §3.3). Pagination
 * mirrors {@code ExpenseClaimReader}: a {@link PageResponse} envelope, {@code size} bounded to
 * {@code [1, MAX_PAGE_SIZE]}, {@code page} floored at {@code 0}.
 */
@Service
public class LeaveRequestReader {

  /** The default page size when the caller omits {@code size}. */
  public static final int DEFAULT_PAGE_SIZE = 25;

  /** The maximum page size a caller may request. */
  public static final int MAX_PAGE_SIZE = 100;

  private final LeaveRequestRepository requestRepository;
  private final EmployeeRepository employeeRepository;

  public LeaveRequestReader(
      LeaveRequestRepository requestRepository, EmployeeRepository employeeRepository) {
    this.requestRepository = requestRepository;
    this.employeeRepository = employeeRepository;
  }

  /** One page of the caller's own requests, newest-updated first. */
  @Transactional(readOnly = true)
  public PageResponse<MyLeaveRequestResponse> myRequests(Integer page, Integer size) {
    Employee me = resolveMe();
    int boundedPage = boundPage(page);
    int boundedSize = boundSize(size);
    long offset = (long) boundedPage * boundedSize;

    long totalElements = requestRepository.countMyRequests(me.getId());
    List<MyLeaveRequestResponse> content =
        requestRepository.findMyRequests(me.getId(), boundedSize, offset).stream()
            .map(LeaveRequestReader::toMyResponse)
            .toList();
    return PageResponse.of(content, boundedPage, boundedSize, totalElements);
  }

  /**
   * One of the caller's own requests.
   *
   * @throws LeaveRequestNotFoundException if unknown, or not the caller's own (→ 404)
   */
  @Transactional(readOnly = true)
  public LeaveRequestResponse myRequest(UUID requestId) {
    Employee me = resolveMe();
    return requestRepository
        .findById(requestId)
        .filter(r -> r.getEmployeeId().equals(me.getId()))
        .map(LeaveRequestResponse::from)
        .orElseThrow(() -> new LeaveRequestNotFoundException(requestId));
  }

  /** One page of the manager-facing tenant-wide list — SUBMITTED-first, then newest-updated. */
  @Transactional(readOnly = true)
  public PageResponse<LeaveRequestSummaryResponse> forManager(
      String status, Integer page, Integer size) {
    String normalizedStatus = normalizeStatus(status);
    int boundedPage = boundPage(page);
    int boundedSize = boundSize(size);
    long offset = (long) boundedPage * boundedSize;

    long totalElements = requestRepository.countForManager(normalizedStatus);
    List<LeaveRequestSummaryResponse> content =
        requestRepository.findForManager(normalizedStatus, boundedSize, offset).stream()
            .map(LeaveRequestReader::toSummaryResponse)
            .toList();
    return PageResponse.of(content, boundedPage, boundedSize, totalElements);
  }

  /**
   * One request (manager-facing — any request visible in the bound tenant).
   *
   * @throws LeaveRequestNotFoundException if unknown in the bound tenant (→ 404)
   */
  @Transactional(readOnly = true)
  public LeaveRequestResponse one(UUID requestId) {
    return requestRepository
        .findById(requestId)
        .map(LeaveRequestResponse::from)
        .orElseThrow(() -> new LeaveRequestNotFoundException(requestId));
  }

  /** Normalizes/validates an optional status filter; blank means "no filter". */
  private static String normalizeStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    String normalized = status.strip().toUpperCase(Locale.ROOT);
    try {
      id.co.nativeapp.employee.timeoff.domain.TimeoffStatus.valueOf(normalized);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown leave request status: " + status, e);
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

  private static MyLeaveRequestResponse toMyResponse(MyLeaveRequestView v) {
    return new MyLeaveRequestResponse(
        v.getId(),
        v.getLeaveType(),
        v.getStartDate(),
        v.getEndDate(),
        v.getDays(),
        v.getStatus(),
        v.getDecidedBy(),
        v.getDecidedAt(),
        v.getDecisionNote());
  }

  private static LeaveRequestSummaryResponse toSummaryResponse(LeaveRequestSummaryView v) {
    return new LeaveRequestSummaryResponse(
        v.getId(),
        v.getEmployeeId(),
        v.getEmployeeName(),
        v.getLeaveType(),
        v.getStartDate(),
        v.getEndDate(),
        v.getDays(),
        v.getStatus(),
        v.getDecidedBy(),
        v.getDecidedAt(),
        v.getDecisionNote());
  }
}
