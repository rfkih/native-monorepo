package id.co.nativeapp.employee.employee.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The create-employee request body. Validated at the edge ({@code @Valid}); {@code company_id} and
 * actor are NEVER request fields — they come from {@link id.co.nativeapp.tenant.TenantContext}
 * (rule 5), so the tenant is un-spoofable.
 *
 * <p>{@code nik} and {@code bankAccount} are PII (rule 6): they are accepted here in plaintext,
 * then column-encrypted at rest by the {@link
 * id.co.nativeapp.employee.config.PiiAttributeConverter}; they are NEVER echoed back in plaintext
 * (the response returns masked forms). Their format is validated BEFORE encryption — a malformed
 * value would otherwise be sealed into ciphertext unnoticed.
 *
 * @param fullName the person's full name
 * @param ptkpStatus the PTKP tax status code (TK0–TK3 / K0–K3)
 * @param nik the national id number (PII) — an Indonesian NIK is exactly 16 digits
 * @param bankAccount the bank account number (PII) — digits only
 */
public record CreateEmployeeRequest(
    @NotBlank @Size(max = 255) String fullName,
    @NotBlank @Pattern(regexp = "TK[0-3]|K[0-3]") String ptkpStatus,
    @NotBlank @Pattern(regexp = "\\d{16}") String nik,
    @NotBlank @Pattern(regexp = "\\d{6,32}") String bankAccount) {}
