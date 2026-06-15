package id.co.nativeapp.employee.employee.dto;

import id.co.nativeapp.employee.assignment.dto.AssignmentResponse;
import java.util.List;

/**
 * The GET-employee response: the employee (PII masked — rule 6) plus its assignments.
 * Org/team/manager live on the assignments, so this is how a caller sees an employee's full
 * placement without exposing any PII.
 *
 * @param employee the employee, with PII masked
 * @param assignments the employee's assignments (RLS-scoped to the bound tenant)
 */
public record EmployeeWithAssignmentsResponse(
    EmployeeResponse employee, List<AssignmentResponse> assignments) {}
