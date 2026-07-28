package id.co.nativeapp.employee.employee.dto;

import id.co.nativeapp.employee.assignment.dto.AssignmentResponse;
import java.util.List;

/**
 * The GET-employee response: the employee (PII masked — rule 6) plus its assignments and employment
 * contracts. Org/team/manager live on the assignments; the contracts are what a compensation
 * package references — the console needs their ids to set base pay. No PII on any leg.
 *
 * @param employee the employee, with PII masked
 * @param assignments the employee's assignments (RLS-scoped to the bound tenant)
 * @param contracts the employee's employment contracts (RLS-scoped; no PII)
 */
public record EmployeeWithAssignmentsResponse(
    EmployeeResponse employee,
    List<AssignmentResponse> assignments,
    List<ContractResponse> contracts) {}
