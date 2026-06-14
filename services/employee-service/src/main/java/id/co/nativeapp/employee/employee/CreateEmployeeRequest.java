package id.co.nativeapp.employee.employee;

import jakarta.validation.constraints.NotBlank;

/**
 * The create-employee request body. Validated at the edge ({@code @Valid}); {@code company_id} and
 * actor are NEVER request fields — they come from {@link id.co.nativeapp.tenant.TenantContext}
 * (rule 5), so the tenant is un-spoofable.
 *
 * <p>{@code nik} and {@code bankAccount} are PII (rule 6): they are accepted here in plaintext,
 * then column-encrypted at rest by the {@link
 * id.co.nativeapp.employee.config.PiiAttributeConverter}; they are NEVER echoed back in plaintext
 * (the response returns masked forms).
 *
 * @param fullName the person's full name
 * @param ptkpStatus the PTKP tax status code (e.g. {@code "TK0"})
 * @param nik the national id number (PII)
 * @param bankAccount the bank account number (PII)
 */
public record CreateEmployeeRequest(
    @NotBlank String fullName,
    @NotBlank String ptkpStatus,
    @NotBlank String nik,
    @NotBlank String bankAccount) {}
