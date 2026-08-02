package id.co.nativeapp.employee.employee.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Application command to update an employee (partial; a {@code null} field leaves the attribute
 * unchanged). {@code company_id} and actor come from {@link id.co.nativeapp.tenant.TenantContext}
 * (rule 5). {@code hireDate} feeds THR proration (Track P Phase P8, ADR 0035) — NOT PII.
 */
public record UpdateEmployeeCommand(
    UUID employeeId,
    String fullName,
    String ptkpStatus,
    String nik,
    String bankAccount,
    String npwp,
    String status,
    LocalDate hireDate) {}
