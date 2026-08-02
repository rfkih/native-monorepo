package id.co.nativeapp.employee.employee.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * The update-employee request body — a partial update. Every field is optional: a {@code null}
 * field leaves the corresponding employee attribute unchanged (the constraints below pass null by
 * definition). {@code company_id} and actor are never request fields (rule 5).
 *
 * <p>{@code nik}, {@code bankAccount} and {@code npwp} are PII (rule 6) — supplied in plaintext
 * only when changing them (format-validated before encryption), column-encrypted at rest, never
 * echoed back in plaintext. {@code hireDate} is NOT PII (Track P Phase P8, ADR 0035 — feeds THR
 * proration).
 *
 * @param fullName the new full name, or {@code null} to leave unchanged
 * @param ptkpStatus the new PTKP status code (TK0–TK3 / K0–K3), or {@code null} to leave unchanged
 * @param nik the new NIK (PII, exactly 16 digits), or {@code null} to leave unchanged
 * @param bankAccount the new bank account (PII, digits only), or {@code null} to leave unchanged
 * @param npwp the new NPWP (PII, 15 or 16 digits — legacy / NIK-based format), or {@code null} to
 *     leave unchanged
 * @param status the new status ({@code ACTIVE}/{@code INACTIVE}), or {@code null} to leave
 *     unchanged
 * @param hireDate the new hire date, or {@code null} to leave unchanged
 */
public record UpdateEmployeeRequest(
    @Size(max = 255) String fullName,
    @Pattern(regexp = "TK[0-3]|K[0-3]") String ptkpStatus,
    @Pattern(regexp = "\\d{16}") String nik,
    @Pattern(regexp = "\\d{6,32}") String bankAccount,
    @Pattern(regexp = "\\d{15,16}") String npwp,
    @Pattern(regexp = "ACTIVE|INACTIVE") String status,
    LocalDate hireDate) {}
