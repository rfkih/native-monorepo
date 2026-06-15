package id.co.nativeapp.employee.assignment.service;

import id.co.nativeapp.employee.assignment.domain.Assignment;
import id.co.nativeapp.employee.assignment.domain.ConflictingLegalEmployerException;
import id.co.nativeapp.employee.assignment.dto.AddAssignmentCommand;
import id.co.nativeapp.employee.assignment.messaging.AssignmentChangedSchema;
import id.co.nativeapp.employee.assignment.repository.AssignmentRepository;
import id.co.nativeapp.employee.employee.domain.EmployeeNotFoundException;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.org.domain.OrgUnitProjection;
import id.co.nativeapp.employee.org.repository.OrgUnitProjectionRepository;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} unit of work for creating an assignment, enforcing the
 * <strong>same-legal-employer invariant</strong> (ARCHITECTURE.md §2) and emitting exactly one
 * {@code AssignmentChanged} — atomically with the insert (rule 3). It is a distinct bean (the
 * {@code *Writer} pattern) so it is invoked through the Spring proxy and the {@link
 * RlsAutoApplyAspect} sets the tenant GUC (rule 5).
 *
 * <p><strong>The invariant.</strong> An employee may hold multiple <em>concurrent</em> assignments,
 * but they must all resolve to the SAME {@code legal_employer}. employee-service learns each
 * org-unit's legal employer by CONSUMING {@code OrgUnitCreated}/{@code OrgUnitChanged} into the
 * local org read model ({@link OrgUnitProjection}); this writer:
 *
 * <ol>
 *   <li>resolves the TARGET org-unit's legal employer from the read model (an unknown org unit → a
 *       {@code 400}, since the projection has not seen it — the org event must arrive first);
 *   <li>for each of the employee's EXISTING assignments whose effective period OVERLAPS the new one
 *       (i.e. is concurrent), resolves that assignment's org-unit legal employer from the read
 *       model;
 *   <li>if any concurrent assignment resolves to a DIFFERENT legal employer, REJECTS with {@link
 *       ConflictingLegalEmployerException} (→ {@code 409}). Otherwise the assignment is created.
 * </ol>
 *
 * <p>(One legal_employer per company today, so the projected value is authoritative for the check.)
 */
@Component
public class AssignmentWriter {

  private final AssignmentRepository assignmentRepository;
  private final EmployeeRepository employeeRepository;
  private final OrgUnitProjectionRepository orgUnitProjectionRepository;
  private final OutboxWriter outboxWriter;
  private final Clock clock;

  public AssignmentWriter(
      AssignmentRepository assignmentRepository,
      EmployeeRepository employeeRepository,
      OrgUnitProjectionRepository orgUnitProjectionRepository,
      OutboxWriter outboxWriter,
      Clock clock) {
    this.assignmentRepository = assignmentRepository;
    this.employeeRepository = employeeRepository;
    this.orgUnitProjectionRepository = orgUnitProjectionRepository;
    this.outboxWriter = outboxWriter;
    this.clock = clock;
  }

  /**
   * Creates an assignment for the employee under the bound company, enforcing the
   * same-legal-employer invariant, and emits one {@code AssignmentChanged} — atomically.
   *
   * <p>Both employee references are validated against the RLS-scoped {@link EmployeeRepository}
   * BEFORE anything is written, so a phantom or cross-tenant employee can never be stamped under
   * the caller's {@code company_id} and emitted on the event (rule 5): {@code company_id} is set
   * from the caller's tenant, so without these guards any {@code employeeId} — including another
   * tenant's — would save cleanly and emit {@code AssignmentChanged} for a non-existent employee.
   *
   * @throws EmployeeNotFoundException if the assignee employee is not visible in the bound tenant
   *     (unknown id, or cross-tenant → invisible under RLS) (→ 404)
   * @throws IllegalArgumentException if the target org unit is unknown to the local read model, or
   *     {@code reporting_to} (the manager) is non-null but not a visible in-tenant employee (→ 400)
   * @throws ConflictingLegalEmployerException if a concurrent assignment resolves to a different
   *     legal employer (→ 409)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Assignment create(AddAssignmentCommand command) {
    String tenant = TenantContext.require().companyId();
    UUID companyId = UUID.fromString(tenant);

    // The assignment must reference a real, in-tenant employee. RLS does NOT catch a phantom/
    // cross-tenant id here (company_id is stamped from the caller's tenant on the WITH CHECK), so
    // guard it explicitly via an RLS-scoped lookup before creating the assignment.
    requireEmployeeExists(command.employeeId(), true);

    // reporting_to (the manager) is nullable; when present it must resolve to an employee visible
    // in
    // the bound tenant — a non-existent or cross-tenant manager is rejected (→ 400), never emitted.
    if (command.reportingTo() != null) {
      requireEmployeeExists(command.reportingTo(), false);
    }

    Assignment assignment =
        new Assignment(
            command.employeeId(),
            command.orgUnitId(),
            command.reportingTo(),
            command.role(),
            command.effectiveFrom(),
            command.effectiveTo());

    UUID targetLegalEmployerId = resolveLegalEmployer(command.orgUnitId());
    enforceSameLegalEmployer(assignment, targetLegalEmployerId);

    assignment.setCompanyId(tenant);
    Assignment saved = assignmentRepository.save(assignment);
    assignmentRepository.flush();

    GenericRecord event = AssignmentChangedSchema.toRecord(saved);
    outboxWriter.write(
        AssignmentChangedSchema.AGGREGATE_TYPE,
        saved.getId().toString(),
        AssignmentChangedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        companyId,
        clock.instant());
    return saved;
  }

  /**
   * Confirms an employee id resolves to an employee visible in the bound tenant via the RLS-scoped
   * {@link EmployeeRepository} (a cross-tenant or unknown id returns empty). The assignee ({@code
   * assignee == true}) is the resource the request targets, so a miss is a {@code 404} ({@link
   * EmployeeNotFoundException}); the {@code reporting_to} manager is a request value, so a miss is
   * a {@code 400} ({@link IllegalArgumentException}).
   */
  private void requireEmployeeExists(UUID employeeId, boolean assignee) {
    if (employeeRepository.findById(employeeId).isEmpty()) {
      if (assignee) {
        throw new EmployeeNotFoundException(employeeId);
      }
      throw new IllegalArgumentException(
          "reporting_to "
              + employeeId
              + " is not an employee in this tenant; the manager must be a known in-tenant"
              + " employee");
    }
  }

  /**
   * Rejects the new assignment if any of the employee's EXISTING concurrent assignments resolves to
   * a different legal employer than the target's.
   */
  private void enforceSameLegalEmployer(Assignment candidate, UUID targetLegalEmployerId) {
    List<Assignment> existing = assignmentRepository.findByEmployeeId(candidate.getEmployeeId());
    for (Assignment other : existing) {
      boolean concurrent = other.overlaps(candidate.getEffectiveFrom(), candidate.getEffectiveTo());
      if (!concurrent) {
        continue;
      }
      UUID otherLegalEmployerId = resolveLegalEmployer(other.getOrgUnitId());
      if (!otherLegalEmployerId.equals(targetLegalEmployerId)) {
        throw new ConflictingLegalEmployerException(
            candidate.getEmployeeId(), targetLegalEmployerId, otherLegalEmployerId);
      }
    }
  }

  /**
   * Resolves an org-unit's legal employer from the local org read model. RLS scopes the lookup to
   * the bound tenant, so a cross-tenant org unit is invisible (empty). An org unit the projection
   * has not seen (the org event has not arrived) is an illegal argument → {@code 400}.
   */
  private UUID resolveLegalEmployer(UUID orgUnitId) {
    return orgUnitProjectionRepository
        .findById(orgUnitId)
        .map(OrgUnitProjection::getLegalEmployerId)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Unknown org unit "
                        + orgUnitId
                        + " (not yet in the local org read model); cannot resolve its legal"
                        + " employer"));
  }
}
