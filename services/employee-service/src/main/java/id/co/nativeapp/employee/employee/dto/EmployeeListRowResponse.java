package id.co.nativeapp.employee.employee.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of {@code GET /api/v1/employees} — an employee together with ONE current assignment in
 * scope (an employee with several concurrent assignments appears once per assignment; the console
 * groups by {@code employeeId}). Assignment fields are null when the employee has no current
 * assignment (unscoped list only).
 *
 * <p>Carries <strong>no PII</strong> (rule 6): no NIK, no bank account, no pay amount — {@code
 * hasCompensation} says only whether a covering compensation package exists.
 *
 * @param employeeId the employee id
 * @param fullName the employee's display name
 * @param status ACTIVE | INACTIVE
 * @param ptkpStatus the PTKP status code (not PII)
 * @param userId the linked console login's Keycloak subject id (non-PII), or null when unlinked
 * @param assignmentId the current assignment's id, or null
 * @param orgUnitId the org unit of that assignment, or null
 * @param role the free-text job role of that assignment, or null
 * @param reportingTo the manager employee id, or null
 * @param effectiveFrom the assignment's start, or null
 * @param effectiveTo the assignment's end ({@code 9999-12-31} = open), or null
 * @param hasCompensation whether a compensation package covers the as-of date
 */
public record EmployeeListRowResponse(
    UUID employeeId,
    String fullName,
    String status,
    String ptkpStatus,
    String userId,
    UUID assignmentId,
    UUID orgUnitId,
    String role,
    UUID reportingTo,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    boolean hasCompensation) {}
