package id.co.nativeapp.employee.assignment;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Application command to add an assignment for an employee. {@code company_id} and actor come from
 * {@link id.co.nativeapp.tenant.TenantContext} (rule 5). Holds no PII. {@code employeeId} comes
 * from the path, the rest from the request body.
 */
public record AddAssignmentCommand(
    UUID employeeId,
    UUID orgUnitId,
    UUID reportingTo,
    String role,
    LocalDate effectiveFrom,
    LocalDate effectiveTo) {}
