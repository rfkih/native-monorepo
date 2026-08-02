package id.co.nativeapp.employee.timeoff.service;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.me.domain.EmployeeNotLinkedException;
import id.co.nativeapp.employee.timeoff.domain.DayKind;
import id.co.nativeapp.employee.timeoff.domain.OvertimeEntry;
import id.co.nativeapp.employee.timeoff.domain.OvertimeEntryNotFoundException;
import id.co.nativeapp.employee.timeoff.domain.RequestKind;
import id.co.nativeapp.employee.timeoff.domain.TimeoffRequestEvent;
import id.co.nativeapp.employee.timeoff.domain.TimeoffStatus;
import id.co.nativeapp.employee.timeoff.repository.OvertimeEntryRepository;
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
 * Owns the {@code @Transactional} units of work for the {@link OvertimeEntry} aggregate (ADR 0033).
 * Same shape as {@link LeaveRequestWriter} minus the overlap/balance guard — overtime entries carry
 * no such invariant (multiple entries on the same {@code work_date} are legal, e.g. a split shift),
 * so no advisory lock is needed here. A distinct bean (the {@code *Writer} pattern) so each method
 * is invoked through the Spring proxy and {@link RlsAutoApplyAspect} sets the tenant GUC (rule 5).
 */
@Component
public class OvertimeEntryWriter {

  /** The {@code timeoff_request_event.action} value for {@link #create}. */
  public static final String ACTION_SUBMIT = "SUBMIT";

  /** The {@code timeoff_request_event.action} value for {@link #cancel}. */
  public static final String ACTION_CANCEL = "CANCEL";

  /** The {@code timeoff_request_event.action} value for {@link #approve}. */
  public static final String ACTION_APPROVE = "APPROVE";

  /** The {@code timeoff_request_event.action} value for {@link #reject}. */
  public static final String ACTION_REJECT = "REJECT";

  private final OvertimeEntryRepository entryRepository;
  private final TimeoffRequestEventRepository eventRepository;
  private final EmployeeRepository employeeRepository;
  private final Clock clock;

  public OvertimeEntryWriter(
      OvertimeEntryRepository entryRepository,
      TimeoffRequestEventRepository eventRepository,
      EmployeeRepository employeeRepository,
      Clock clock) {
    this.entryRepository = entryRepository;
    this.eventRepository = eventRepository;
    this.employeeRepository = employeeRepository;
    this.clock = clock;
  }

  /**
   * Creates (== submits) an overtime entry for the CALLER. Idempotent per {@code (company,
   * idempotencyKey)} — see {@link LeaveRequestWriter#create}'s Javadoc for the identical idiom.
   *
   * @throws EmployeeNotLinkedException if the caller has no employee link (→ 404)
   * @throws IllegalArgumentException if {@code minutes} is out of range (→ 400)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public OvertimeEntry create(
      LocalDate workDate, int minutes, DayKind dayKind, String idempotencyKey) {
    String tenant = TenantContext.require().companyId();
    String actor = TenantContext.require().actor();
    Employee me = resolveMe();

    Optional<OvertimeEntry> replay = entryRepository.findByIdempotencyKey(idempotencyKey);
    if (replay.isPresent()) {
      return replay.get();
    }

    OvertimeEntry entry = new OvertimeEntry(me.getId(), workDate, minutes, dayKind, idempotencyKey);
    entry.setCompanyId(tenant);
    entryRepository.save(entry);
    appendEvent(
        entry.getId(),
        ACTION_SUBMIT,
        TimeoffStatus.SUBMITTED,
        TimeoffStatus.SUBMITTED,
        actor,
        null,
        idempotencyKey,
        tenant);
    return entry;
  }

  /**
   * Cancels the CALLER's own SUBMITTED entry.
   *
   * @throws OvertimeEntryNotFoundException if unknown, or not the caller's own (→ 404)
   * @throws id.co.nativeapp.employee.timeoff.domain.TimeoffStateException if not SUBMITTED (→ 409)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public OvertimeEntry cancel(UUID entryId, String idempotencyKey) {
    String tenant = TenantContext.require().companyId();
    String actor = TenantContext.require().actor();
    Employee me = resolveMe();

    Optional<UUID> replay =
        eventRepository.findIdByRequestAndIdempotencyKey(
            RequestKind.OVERTIME.name(), entryId, idempotencyKey, ACTION_CANCEL);
    if (replay.isPresent()) {
      return requireOwnEntry(entryId, me.getId());
    }

    OvertimeEntry entry = requireOwnEntry(entryId, me.getId());
    TimeoffStatus from = entry.getStatus();
    entry.cancel();
    entryRepository.save(entry);
    appendEvent(
        entryId, ACTION_CANCEL, from, entry.getStatus(), actor, null, idempotencyKey, tenant);
    return entry;
  }

  /**
   * Approves a SUBMITTED entry.
   *
   * @throws OvertimeEntryNotFoundException if unknown in this tenant (→ 404)
   * @throws id.co.nativeapp.employee.timeoff.domain.TimeoffStateException if not SUBMITTED (→ 409)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public OvertimeEntry approve(UUID entryId, String note, String idempotencyKey) {
    String tenant = TenantContext.require().companyId();
    String actor = TenantContext.require().actor();

    Optional<UUID> replay =
        eventRepository.findIdByRequestAndIdempotencyKey(
            RequestKind.OVERTIME.name(), entryId, idempotencyKey, ACTION_APPROVE);
    if (replay.isPresent()) {
      return entryRepository
          .findById(entryId)
          .orElseThrow(() -> new OvertimeEntryNotFoundException(entryId));
    }

    OvertimeEntry entry =
        entryRepository
            .findById(entryId)
            .orElseThrow(() -> new OvertimeEntryNotFoundException(entryId));
    TimeoffStatus from = entry.getStatus();
    Instant now = clock.instant();
    entry.approve(actor, note, now);
    entryRepository.save(entry);
    appendEvent(
        entryId, ACTION_APPROVE, from, entry.getStatus(), actor, note, idempotencyKey, tenant);
    return entry;
  }

  /**
   * Rejects a SUBMITTED entry (note required by the aggregate).
   *
   * @throws OvertimeEntryNotFoundException if unknown in this tenant (→ 404)
   * @throws id.co.nativeapp.employee.timeoff.domain.TimeoffStateException if not SUBMITTED (→ 409)
   * @throws id.co.nativeapp.employee.timeoff.domain.DecisionCommentRequiredException if {@code
   *     note} is blank (→ 422, defense-in-depth)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public OvertimeEntry reject(UUID entryId, String note, String idempotencyKey) {
    String tenant = TenantContext.require().companyId();
    String actor = TenantContext.require().actor();

    Optional<UUID> replay =
        eventRepository.findIdByRequestAndIdempotencyKey(
            RequestKind.OVERTIME.name(), entryId, idempotencyKey, ACTION_REJECT);
    if (replay.isPresent()) {
      return entryRepository
          .findById(entryId)
          .orElseThrow(() -> new OvertimeEntryNotFoundException(entryId));
    }

    OvertimeEntry entry =
        entryRepository
            .findById(entryId)
            .orElseThrow(() -> new OvertimeEntryNotFoundException(entryId));
    TimeoffStatus from = entry.getStatus();
    Instant now = clock.instant();
    entry.reject(actor, note, now);
    entryRepository.save(entry);
    appendEvent(
        entryId, ACTION_REJECT, from, entry.getStatus(), actor, note, idempotencyKey, tenant);
    return entry;
  }

  /** Re-reads an entry by id in a FRESH transaction — the conflict-recovery re-read. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<OvertimeEntry> findByIdForReplay(UUID entryId) {
    return entryRepository.findById(entryId);
  }

  /**
   * Re-reads by create-time idempotency key — see {@code LeaveRequestWriter}'s identical method.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<OvertimeEntry> findByIdempotencyKeyForReplay(String idempotencyKey) {
    return entryRepository.findByIdempotencyKey(idempotencyKey);
  }

  /** Whether an audit row for this EXACT (entry, idempotency-key, action) triple exists. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public boolean isReplayedEvent(UUID entryId, String idempotencyKey, String action) {
    return eventRepository
        .findIdByRequestAndIdempotencyKey(
            RequestKind.OVERTIME.name(), entryId, idempotencyKey, action)
        .isPresent();
  }

  private void appendEvent(
      UUID entryId,
      String action,
      TimeoffStatus from,
      TimeoffStatus to,
      String actor,
      String comment,
      String idempotencyKey,
      String tenant) {
    TimeoffRequestEvent event =
        new TimeoffRequestEvent(
            RequestKind.OVERTIME, entryId, action, from, to, actor, comment, idempotencyKey);
    event.setCompanyId(tenant);
    eventRepository.saveAndFlush(event);
  }

  private OvertimeEntry requireOwnEntry(UUID entryId, UUID employeeId) {
    return entryRepository
        .findById(entryId)
        .filter(e -> e.getEmployeeId().equals(employeeId))
        .orElseThrow(() -> new OvertimeEntryNotFoundException(entryId));
  }

  private Employee resolveMe() {
    String actor = TenantContext.require().actor();
    return employeeRepository.findByUserId(actor).orElseThrow(EmployeeNotLinkedException::new);
  }
}
