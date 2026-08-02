package id.co.nativeapp.employee.timeoff.service;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.me.domain.EmployeeNotLinkedException;
import id.co.nativeapp.employee.timeoff.domain.InsufficientLeaveBalanceException;
import id.co.nativeapp.employee.timeoff.domain.LeaveBalance;
import id.co.nativeapp.employee.timeoff.domain.LeaveOverlapException;
import id.co.nativeapp.employee.timeoff.domain.LeaveRequest;
import id.co.nativeapp.employee.timeoff.domain.LeaveRequestNotFoundException;
import id.co.nativeapp.employee.timeoff.domain.LeaveType;
import id.co.nativeapp.employee.timeoff.domain.RequestKind;
import id.co.nativeapp.employee.timeoff.domain.TimeoffRequestEvent;
import id.co.nativeapp.employee.timeoff.domain.TimeoffStatus;
import id.co.nativeapp.employee.timeoff.repository.LeaveBalanceRepository;
import id.co.nativeapp.employee.timeoff.repository.LeaveRequestRepository;
import id.co.nativeapp.employee.timeoff.repository.TimeoffRequestEventRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work for the {@link LeaveRequest} aggregate (ADR 0033).
 * A distinct bean (the {@code *Writer} pattern) so each method is invoked through the Spring proxy
 * and {@link RlsAutoApplyAspect} sets the tenant GUC (rule 5); {@code REQUIRES_NEW} on every method
 * keeps a concurrency-collision recovery re-read (in {@code LeaveRequestService}) in a transaction
 * independent of the aborted one.
 *
 * <p><strong>Serialization (ADR 0033 §4).</strong> Both guarded invariants — the OVERLAP guard (no
 * two SUBMITTED/APPROVED requests for the same employee may cover overlapping dates) and the
 * ANNUAL-leave BALANCE guard (approving must not push used days past {@code granted + adjustment})
 * — are check-then-act races if left unserialized: two concurrent creates could each observe "no
 * overlap yet" and both insert, or two concurrent approvals could each observe "enough balance" and
 * both approve. {@link #create} and {@link #approve} therefore both take a PER-EMPLOYEE
 * transaction-scoped advisory lock ({@link LeaveRequestRepository#lockEmployeeTimeoff}) BEFORE
 * probing — the {@code ExpenseClaimWriter} currency-establishment idiom, keyed by employee instead
 * of tenant here since two DIFFERENT employees' requests never contend with each other.
 */
@Component
public class LeaveRequestWriter {

  /** The {@code timeoff_request_event.action} value for {@link #create}. */
  public static final String ACTION_SUBMIT = "SUBMIT";

  /** The {@code timeoff_request_event.action} value for {@link #cancel}. */
  public static final String ACTION_CANCEL = "CANCEL";

  /** The {@code timeoff_request_event.action} value for {@link #approve}. */
  public static final String ACTION_APPROVE = "APPROVE";

  /** The {@code timeoff_request_event.action} value for {@link #reject}. */
  public static final String ACTION_REJECT = "REJECT";

  /** The advisory-lock namespace prefix — distinct from any other employee-keyed lock. */
  private static final String LOCK_NAMESPACE = "leave_request_employee";

  private final LeaveRequestRepository requestRepository;
  private final LeaveBalanceRepository balanceRepository;
  private final TimeoffRequestEventRepository eventRepository;
  private final EmployeeRepository employeeRepository;
  private final Clock clock;

  public LeaveRequestWriter(
      LeaveRequestRepository requestRepository,
      LeaveBalanceRepository balanceRepository,
      TimeoffRequestEventRepository eventRepository,
      EmployeeRepository employeeRepository,
      Clock clock) {
    this.requestRepository = requestRepository;
    this.balanceRepository = balanceRepository;
    this.eventRepository = eventRepository;
    this.employeeRepository = employeeRepository;
    this.clock = clock;
  }

  /**
   * Creates (== submits) a leave request for the CALLER (resolved from {@link TenantContext} —
   * never an id parameter, rule 5 / the {@code /me} idiom). Idempotent per {@code (company,
   * idempotencyKey)}: a retry replays the original request, checking/locking/inserting nothing a
   * second time.
   *
   * @throws EmployeeNotLinkedException if the caller has no employee link (→ 404)
   * @throws IllegalArgumentException if the date range/day count is invalid (→ 400)
   * @throws LeaveOverlapException if the range overlaps an existing SUBMITTED/APPROVED request (→
   *     409)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public LeaveRequest create(
      LeaveType leaveType,
      LocalDate startDate,
      LocalDate endDate,
      int days,
      String idempotencyKey) {
    String tenant = TenantContext.require().companyId();
    String actor = TenantContext.require().actor();
    Employee me = resolveMe();

    Optional<LeaveRequest> replay = requestRepository.findByIdempotencyKey(idempotencyKey);
    if (replay.isPresent()) {
      return replay.get();
    }

    requestRepository.lockEmployeeTimeoff(LOCK_NAMESPACE + ":" + me.getId());
    if (requestRepository.existsOverlapping(me.getId(), startDate, endDate)) {
      throw new LeaveOverlapException(me.getId());
    }

    LeaveRequest request =
        new LeaveRequest(me.getId(), leaveType, startDate, endDate, days, idempotencyKey);
    request.setCompanyId(tenant);
    requestRepository.save(request);
    appendEvent(
        request.getId(),
        ACTION_SUBMIT,
        TimeoffStatus.SUBMITTED,
        TimeoffStatus.SUBMITTED,
        actor,
        null,
        idempotencyKey,
        tenant);
    return request;
  }

  /**
   * Cancels the CALLER's own SUBMITTED request.
   *
   * @throws LeaveRequestNotFoundException if unknown, or not the caller's own (→ 404)
   * @throws id.co.nativeapp.employee.timeoff.domain.TimeoffStateException if not SUBMITTED (→ 409)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public LeaveRequest cancel(UUID requestId, String idempotencyKey) {
    String tenant = TenantContext.require().companyId();
    String actor = TenantContext.require().actor();
    Employee me = resolveMe();

    Optional<UUID> replay =
        eventRepository.findIdByRequestAndIdempotencyKey(
            RequestKind.LEAVE.name(), requestId, idempotencyKey, ACTION_CANCEL);
    if (replay.isPresent()) {
      return requireOwnRequest(requestId, me.getId());
    }

    LeaveRequest request = requireOwnRequest(requestId, me.getId());
    TimeoffStatus from = request.getStatus();
    request.cancel();
    requestRepository.save(request);
    appendEvent(
        requestId, ACTION_CANCEL, from, request.getStatus(), actor, null, idempotencyKey, tenant);
    return request;
  }

  /**
   * Approves a SUBMITTED request. For {@link LeaveType#ANNUAL}, the balance-sufficiency check runs
   * UNDER the per-employee advisory lock — see the class Javadoc.
   *
   * @throws LeaveRequestNotFoundException if unknown in this tenant (→ 404)
   * @throws id.co.nativeapp.employee.timeoff.domain.TimeoffStateException if not SUBMITTED (→ 409)
   * @throws InsufficientLeaveBalanceException if approving an ANNUAL request would exceed the
   *     employee's balance for the year (→ 409)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public LeaveRequest approve(UUID requestId, String note, String idempotencyKey) {
    String tenant = TenantContext.require().companyId();
    String actor = TenantContext.require().actor();

    Optional<UUID> replay =
        eventRepository.findIdByRequestAndIdempotencyKey(
            RequestKind.LEAVE.name(), requestId, idempotencyKey, ACTION_APPROVE);
    if (replay.isPresent()) {
      return requestRepository
          .findById(requestId)
          .orElseThrow(() -> new LeaveRequestNotFoundException(requestId));
    }

    LeaveRequest request =
        requestRepository
            .findById(requestId)
            .orElseThrow(() -> new LeaveRequestNotFoundException(requestId));

    requestRepository.lockEmployeeTimeoff(LOCK_NAMESPACE + ":" + request.getEmployeeId());
    if (request.getLeaveType() == LeaveType.ANNUAL) {
      int year = request.getStartDate().getYear();
      int used = requestRepository.sumApprovedAnnualDaysForYear(request.getEmployeeId(), year);
      Optional<LeaveBalance> balance =
          balanceRepository.findByEmployeeIdAndYear(request.getEmployeeId(), year);
      int granted =
          balance.map(LeaveBalance::getGrantedDays).orElse(LeaveBalance.DEFAULT_GRANTED_DAYS);
      int adjustment = balance.map(LeaveBalance::getAdjustmentDays).orElse(0);
      int available = granted + adjustment - used;
      if (request.getDays() > available) {
        throw new InsufficientLeaveBalanceException(
            request.getEmployeeId(), year, request.getDays(), available);
      }
    }

    TimeoffStatus from = request.getStatus();
    Instant now = clock.instant();
    request.approve(actor, note, now);
    requestRepository.save(request);
    appendEvent(
        requestId, ACTION_APPROVE, from, request.getStatus(), actor, note, idempotencyKey, tenant);
    return request;
  }

  /**
   * Rejects a SUBMITTED request (note required by the aggregate).
   *
   * @throws LeaveRequestNotFoundException if unknown in this tenant (→ 404)
   * @throws id.co.nativeapp.employee.timeoff.domain.TimeoffStateException if not SUBMITTED (→ 409)
   * @throws id.co.nativeapp.employee.timeoff.domain.DecisionCommentRequiredException if {@code
   *     note} is blank (→ 422, defense-in-depth — unreachable over HTTP)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public LeaveRequest reject(UUID requestId, String note, String idempotencyKey) {
    String tenant = TenantContext.require().companyId();
    String actor = TenantContext.require().actor();

    Optional<UUID> replay =
        eventRepository.findIdByRequestAndIdempotencyKey(
            RequestKind.LEAVE.name(), requestId, idempotencyKey, ACTION_REJECT);
    if (replay.isPresent()) {
      return requestRepository
          .findById(requestId)
          .orElseThrow(() -> new LeaveRequestNotFoundException(requestId));
    }

    LeaveRequest request =
        requestRepository
            .findById(requestId)
            .orElseThrow(() -> new LeaveRequestNotFoundException(requestId));
    TimeoffStatus from = request.getStatus();
    Instant now = clock.instant();
    request.reject(actor, note, now);
    requestRepository.save(request);
    appendEvent(
        requestId, ACTION_REJECT, from, request.getStatus(), actor, note, idempotencyKey, tenant);
    return request;
  }

  /**
   * Re-reads a request by id in a FRESH transaction — used by {@code LeaveRequestService} to
   * recover the loser of a concurrent idempotency-key insert race after its own transaction
   * aborted.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<LeaveRequest> findByIdForReplay(UUID requestId) {
    return requestRepository.findById(requestId);
  }

  /**
   * Re-reads by create-time idempotency key in a FRESH transaction — {@code LeaveRequestService}'s
   * recovery for a concurrent {@link #create} race: {@code uq_leave_request_idem} is the ONLY
   * unique constraint {@link #create} can trip (the {@code timeoff_request_event} row it also
   * appends carries a freshly-generated {@code request_id}, so it can never collide), so any {@link
   * org.springframework.dao.DataIntegrityViolationException} from {@link #create} is guaranteed to
   * be this exact race — no action-scoped re-check is needed here (unlike {@link #approve}/{@link
   * #reject}/{@link #cancel}, which share the event table's less specific constraint).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<LeaveRequest> findByIdempotencyKeyForReplay(String idempotencyKey) {
    return requestRepository.findByIdempotencyKey(idempotencyKey);
  }

  /** Whether an audit row for this EXACT (request, idempotency-key, action) triple exists. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public boolean isReplayedEvent(UUID requestId, String idempotencyKey, String action) {
    return eventRepository
        .findIdByRequestAndIdempotencyKey(
            RequestKind.LEAVE.name(), requestId, idempotencyKey, action)
        .isPresent();
  }

  private void appendEvent(
      UUID requestId,
      String action,
      TimeoffStatus from,
      TimeoffStatus to,
      String actor,
      String comment,
      String idempotencyKey,
      String tenant) {
    TimeoffRequestEvent event =
        new TimeoffRequestEvent(
            RequestKind.LEAVE, requestId, action, from, to, actor, comment, idempotencyKey);
    event.setCompanyId(tenant);
    eventRepository.saveAndFlush(event);
  }

  private LeaveRequest requireOwnRequest(UUID requestId, UUID employeeId) {
    return requestRepository
        .findById(requestId)
        .filter(r -> r.getEmployeeId().equals(employeeId))
        .orElseThrow(() -> new LeaveRequestNotFoundException(requestId));
  }

  private Employee resolveMe() {
    String actor = TenantContext.require().actor();
    return employeeRepository.findByUserId(actor).orElseThrow(EmployeeNotLinkedException::new);
  }
}
