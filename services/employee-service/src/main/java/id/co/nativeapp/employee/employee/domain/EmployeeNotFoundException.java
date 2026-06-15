package id.co.nativeapp.employee.employee.domain;

import java.util.UUID;

/**
 * Raised when an operation references an employee that is NOT visible in the bound tenant: an
 * unknown id, or — critically — ANOTHER tenant's employee (which RLS makes invisible, so the
 * RLS-scoped {@code findById} returns empty rather than the cross-tenant row). Mapped to {@code 404
 * Not Found} by {@link id.co.nativeapp.employee.config.EmployeeApiAdvice}.
 *
 * <p>Used to guard an assignment write against a phantom employee: {@code company_id} is stamped
 * from the caller's tenant, so without this guard ANY {@code employeeId} (including a foreign
 * tenant's) would save cleanly under the caller's {@code company_id} and emit an {@code
 * AssignmentChanged} for a non-existent employee (rule 5). The message names only the employee id
 * (a UUID), never any PII (rule 6), so it is safe to surface as the {@code ProblemDetail} detail.
 */
public class EmployeeNotFoundException extends RuntimeException {

  public EmployeeNotFoundException(UUID employeeId) {
    super("Employee " + employeeId + " was not found in this tenant");
  }
}
