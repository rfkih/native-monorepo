package id.co.nativeapp.employee.assignment;

import java.util.UUID;

/**
 * Raised when an assignment would violate the same-legal-employer invariant (ARCHITECTURE.md §2):
 * the employee already holds a CONCURRENT assignment whose org-unit resolves to a DIFFERENT {@code
 * legal_employer} than the one being added. Mapped to {@code 409 Conflict} by {@link
 * id.co.nativeapp.employee.config.EmployeeApiAdvice} — the request is well-formed, but it conflicts
 * with the employee's current assignments.
 *
 * <p>The message names only legal-employer ids (UUIDs), never any PII (rule 6), so it is safe to
 * surface as the {@code ProblemDetail} detail.
 */
public class ConflictingLegalEmployerException extends RuntimeException {

  public ConflictingLegalEmployerException(
      UUID employeeId, UUID requestedLegalEmployerId, UUID existingLegalEmployerId) {
    super(
        "Employee "
            + employeeId
            + " already holds a concurrent assignment under legal employer "
            + existingLegalEmployerId
            + "; cannot add a concurrent assignment under a different legal employer "
            + requestedLegalEmployerId
            + " (all concurrent assignments must resolve to the same legal employer)");
  }
}
