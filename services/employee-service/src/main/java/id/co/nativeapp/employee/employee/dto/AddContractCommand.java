package id.co.nativeapp.employee.employee.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Application command to add an employment contract to an employee. {@code company_id} and actor
 * come from {@link id.co.nativeapp.tenant.TenantContext} (rule 5). Holds no PII / Money.
 */
public record AddContractCommand(
    UUID employeeId,
    String employmentType,
    UUID legalEmployerId,
    LocalDate effectiveFrom,
    LocalDate effectiveTo) {}
