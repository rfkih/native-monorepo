package id.co.nativeapp.employee.employee.dto;

/**
 * Application command to create an employee. {@code company_id} and actor are NOT fields — they
 * come from {@link id.co.nativeapp.tenant.TenantContext} (rule 5). {@code nik} / {@code
 * bankAccount} are PII (rule 6), carried in plaintext only as far as the encrypting persistence
 * boundary.
 */
public record CreateEmployeeCommand(
    String fullName, String ptkpStatus, String nik, String bankAccount) {}
