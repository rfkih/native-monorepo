package id.co.nativeapp.employee.assignment;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Assignment response body — the assignment just created, or one of an employee's assignments in
 * the GET-with-assignments read. Holds no PII.
 *
 * @param id the assignment id
 * @param employeeId the employee assigned
 * @param orgUnitId the org unit assigned to
 * @param reportingTo the manager (employee id) this assignment reports to, or {@code null}
 * @param role the role held
 * @param effectiveFrom the date the assignment becomes effective
 * @param effectiveTo the date it ends ({@code 9999-12-31} when open-ended)
 */
public record AssignmentResponse(
    UUID id,
    UUID employeeId,
    UUID orgUnitId,
    UUID reportingTo,
    String role,
    LocalDate effectiveFrom,
    LocalDate effectiveTo) {

  public static AssignmentResponse from(Assignment assignment) {
    return new AssignmentResponse(
        assignment.getId(),
        assignment.getEmployeeId(),
        assignment.getOrgUnitId(),
        assignment.getReportingTo(),
        assignment.getRole(),
        assignment.getEffectiveFrom(),
        assignment.getEffectiveTo());
  }
}
