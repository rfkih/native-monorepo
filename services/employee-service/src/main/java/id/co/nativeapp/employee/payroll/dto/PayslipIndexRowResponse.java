package id.co.nativeapp.employee.payroll.dto;

import java.util.UUID;

/**
 * One row of a run's payslip index ({@code GET /api/v1/payroll-runs/{id}/payslips}) — an employee
 * with a payslip in the run and their line count. NO amounts (salary PII, rule 6); the console
 * fetches the masked line detail per employee separately.
 *
 * @param employeeId the employee
 * @param fullName the employee's display name (not PII)
 * @param lineCount how many payslip lines the employee has in the run
 * @param illustrative whether the lines were computed from illustrative placeholder rules
 */
public record PayslipIndexRowResponse(
    UUID employeeId, String fullName, int lineCount, boolean illustrative) {}
