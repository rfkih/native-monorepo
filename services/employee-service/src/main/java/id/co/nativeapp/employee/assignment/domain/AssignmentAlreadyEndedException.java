package id.co.nativeapp.employee.assignment.domain;

import java.util.UUID;

/**
 * Ending an assignment that is no longer open (its {@code effective_to} is already set) — a state
 * conflict, mapped to {@code 409} by the employee-service advice. The message names only the
 * assignment id (a UUID), never PII (rule 6).
 */
public class AssignmentAlreadyEndedException extends RuntimeException {

  public AssignmentAlreadyEndedException(UUID assignmentId) {
    super("Assignment " + assignmentId + " is not open; only an open assignment can be ended");
  }
}
