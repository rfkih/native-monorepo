package id.co.nativeapp.employee.assignment.service;

import id.co.nativeapp.employee.assignment.domain.Assignment;
import id.co.nativeapp.employee.assignment.dto.AddAssignmentCommand;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Adds assignments for the bound company, enforcing the same-legal-employer invariant. It
 * orchestrates the transactional unit of work in {@link AssignmentWriter}; the transaction
 * boundary, RLS GUC, the invariant check, and the outbox write all live on the writer so the Spring
 * proxy + the auto-RLS aspect engage (the {@code *Writer} pattern).
 *
 * <p>The endpoint is tenant-scoped (the tenant is bound at the request edge), so this service only
 * asserts a tenant is present and delegates; {@code company_id} is stamped by the writer from
 * {@link TenantContext}, never from the request (rule 5).
 */
@Service
public class AssignmentService {

  private final AssignmentWriter writer;

  public AssignmentService(AssignmentWriter writer) {
    this.writer = writer;
  }

  /**
   * Adds an assignment under the bound company, enforcing the same-legal-employer invariant, and
   * emits {@code AssignmentChanged}.
   *
   * @throws ConflictingLegalEmployerException if a concurrent assignment resolves to a different
   *     legal employer (→ 409)
   */
  public Assignment add(AddAssignmentCommand command) {
    TenantContext.require();
    return writer.create(command);
  }

  /**
   * Ends an open assignment (effective-dated close) and emits {@code AssignmentChanged}.
   *
   * @throws id.co.nativeapp.employee.assignment.domain.AssignmentNotFoundException if the
   *     assignment is not visible for that employee in the bound tenant (→ 404)
   * @throws id.co.nativeapp.employee.assignment.domain.AssignmentAlreadyEndedException if it is not
   *     open (→ 409)
   */
  public Assignment end(UUID employeeId, UUID assignmentId, LocalDate endOn) {
    TenantContext.require();
    return writer.end(employeeId, assignmentId, endOn);
  }
}
