package id.co.nativeapp.employee.assignment.domain;

import java.util.UUID;

/**
 * The operation references an assignment not visible to the caller: an unknown id, another
 * employee's assignment (the path pair does not match), or — invisible under RLS — another
 * tenant's. All three collapse to the same {@code 404} (anti-enumeration). The message names only
 * the assignment id (a UUID), never PII (rule 6).
 */
public class AssignmentNotFoundException extends RuntimeException {

  public AssignmentNotFoundException(UUID assignmentId) {
    super("Assignment " + assignmentId + " not found");
  }
}
