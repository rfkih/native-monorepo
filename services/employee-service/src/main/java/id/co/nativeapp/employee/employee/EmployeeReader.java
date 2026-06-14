package id.co.nativeapp.employee.employee;

import id.co.nativeapp.employee.assignment.AssignmentRepository;
import id.co.nativeapp.employee.assignment.AssignmentResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads an employee together with its assignments — the query side. A
 * {@code @Transactional(readOnly = true)} service bean so the proxy + auto-RLS aspect engage: the
 * lookups carry NO {@code WHERE company_id}; the result is constrained solely by the auto-applied
 * RLS policy (rule 5), so a caller only ever sees their own tenant's employee/assignments.
 *
 * <p>The response masks PII (the employee's NIK / bank account; rule 6) — the reader returns the
 * masked {@link EmployeeWithAssignmentsResponse}, never the raw plaintext.
 */
@Service
public class EmployeeReader {

  private final EmployeeRepository employeeRepository;
  private final AssignmentRepository assignmentRepository;

  public EmployeeReader(
      EmployeeRepository employeeRepository, AssignmentRepository assignmentRepository) {
    this.employeeRepository = employeeRepository;
    this.assignmentRepository = assignmentRepository;
  }

  /**
   * The raw {@link Employee} aggregate, within the bound tenant — for internal/test reads that need
   * the audit columns (the HTTP boundary always uses {@link #findWithAssignments(UUID)}, which
   * masks PII). A {@code @Transactional} read so the auto-RLS aspect engages: a cross-tenant
   * employee is invisible (empty). PII on the returned entity stays encrypted at rest and is only
   * ever exposed masked by a response DTO (rule 6).
   */
  @Transactional(readOnly = true)
  public Optional<Employee> findEmployee(UUID employeeId) {
    return employeeRepository.findById(employeeId);
  }

  /**
   * The employee (PII masked) plus its assignments, within the bound tenant. Empty if no such
   * employee is visible (unknown id, or another tenant's — invisible under RLS).
   */
  @Transactional(readOnly = true)
  public Optional<EmployeeWithAssignmentsResponse> findWithAssignments(UUID employeeId) {
    return employeeRepository
        .findById(employeeId)
        .map(
            employee -> {
              List<AssignmentResponse> assignments =
                  assignmentRepository.findByEmployeeId(employeeId).stream()
                      .map(AssignmentResponse::from)
                      .toList();
              return new EmployeeWithAssignmentsResponse(
                  EmployeeResponse.from(employee), assignments);
            });
  }
}
