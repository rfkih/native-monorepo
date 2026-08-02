package id.co.nativeapp.employee.timeoff.service;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.me.domain.EmployeeNotLinkedException;
import id.co.nativeapp.employee.timeoff.domain.OvertimeEntryNotFoundException;
import id.co.nativeapp.employee.timeoff.domain.TimeoffStatus;
import id.co.nativeapp.employee.timeoff.dto.MyOvertimeEntryResponse;
import id.co.nativeapp.employee.timeoff.dto.OvertimeEntryResponse;
import id.co.nativeapp.employee.timeoff.dto.OvertimeEntrySummaryResponse;
import id.co.nativeapp.employee.timeoff.dto.PageResponse;
import id.co.nativeapp.employee.timeoff.projection.MyOvertimeEntryView;
import id.co.nativeapp.employee.timeoff.projection.OvertimeEntrySummaryView;
import id.co.nativeapp.employee.timeoff.repository.OvertimeEntryRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The read side for overtime entries — the {@link LeaveRequestReader} idiom, applied here. */
@Service
public class OvertimeEntryReader {

  /** The default page size when the caller omits {@code size}. */
  public static final int DEFAULT_PAGE_SIZE = 25;

  /** The maximum page size a caller may request. */
  public static final int MAX_PAGE_SIZE = 100;

  private final OvertimeEntryRepository entryRepository;
  private final EmployeeRepository employeeRepository;

  public OvertimeEntryReader(
      OvertimeEntryRepository entryRepository, EmployeeRepository employeeRepository) {
    this.entryRepository = entryRepository;
    this.employeeRepository = employeeRepository;
  }

  /** One page of the caller's own entries, newest-updated first. */
  @Transactional(readOnly = true)
  public PageResponse<MyOvertimeEntryResponse> myEntries(Integer page, Integer size) {
    Employee me = resolveMe();
    int boundedPage = boundPage(page);
    int boundedSize = boundSize(size);
    long offset = (long) boundedPage * boundedSize;

    long totalElements = entryRepository.countMyEntries(me.getId());
    List<MyOvertimeEntryResponse> content =
        entryRepository.findMyEntries(me.getId(), boundedSize, offset).stream()
            .map(OvertimeEntryReader::toMyResponse)
            .toList();
    return PageResponse.of(content, boundedPage, boundedSize, totalElements);
  }

  /**
   * One of the caller's own entries.
   *
   * @throws OvertimeEntryNotFoundException if unknown, or not the caller's own (→ 404)
   */
  @Transactional(readOnly = true)
  public OvertimeEntryResponse myEntry(UUID entryId) {
    Employee me = resolveMe();
    return entryRepository
        .findById(entryId)
        .filter(e -> e.getEmployeeId().equals(me.getId()))
        .map(OvertimeEntryResponse::from)
        .orElseThrow(() -> new OvertimeEntryNotFoundException(entryId));
  }

  /** One page of the manager-facing tenant-wide list — SUBMITTED-first, then newest-updated. */
  @Transactional(readOnly = true)
  public PageResponse<OvertimeEntrySummaryResponse> forManager(
      String status, Integer page, Integer size) {
    String normalizedStatus = normalizeStatus(status);
    int boundedPage = boundPage(page);
    int boundedSize = boundSize(size);
    long offset = (long) boundedPage * boundedSize;

    long totalElements = entryRepository.countForManager(normalizedStatus);
    List<OvertimeEntrySummaryResponse> content =
        entryRepository.findForManager(normalizedStatus, boundedSize, offset).stream()
            .map(OvertimeEntryReader::toSummaryResponse)
            .toList();
    return PageResponse.of(content, boundedPage, boundedSize, totalElements);
  }

  /**
   * One entry (manager-facing).
   *
   * @throws OvertimeEntryNotFoundException if unknown in the bound tenant (→ 404)
   */
  @Transactional(readOnly = true)
  public OvertimeEntryResponse one(UUID entryId) {
    return entryRepository
        .findById(entryId)
        .map(OvertimeEntryResponse::from)
        .orElseThrow(() -> new OvertimeEntryNotFoundException(entryId));
  }

  private static String normalizeStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    String normalized = status.strip().toUpperCase(Locale.ROOT);
    try {
      TimeoffStatus.valueOf(normalized);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown overtime entry status: " + status, e);
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

  private static MyOvertimeEntryResponse toMyResponse(MyOvertimeEntryView v) {
    return new MyOvertimeEntryResponse(
        v.getId(),
        v.getWorkDate(),
        v.getMinutes(),
        v.getDayKind(),
        v.getStatus(),
        v.getDecidedBy(),
        v.getDecidedAt(),
        v.getDecisionNote());
  }

  private static OvertimeEntrySummaryResponse toSummaryResponse(OvertimeEntrySummaryView v) {
    return new OvertimeEntrySummaryResponse(
        v.getId(),
        v.getEmployeeId(),
        v.getEmployeeName(),
        v.getWorkDate(),
        v.getMinutes(),
        v.getDayKind(),
        v.getStatus(),
        v.getDecidedBy(),
        v.getDecidedAt(),
        v.getDecisionNote());
  }
}
