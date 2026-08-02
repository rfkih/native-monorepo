package id.co.nativeapp.employee.employee.dto;

import java.util.UUID;

/**
 * Application command to update an employee (partial; a {@code null} field leaves the attribute
 * unchanged). {@code company_id} and actor come from {@link id.co.nativeapp.tenant.TenantContext}
 * (rule 5).
 */
public record UpdateEmployeeCommand(
    UUID employeeId,
    String fullName,
    String ptkpStatus,
    String nik,
    String bankAccount,
    String npwp,
    String status) {}
