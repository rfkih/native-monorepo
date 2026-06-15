package id.co.nativeapp.employee.employee.dto;

/**
 * The update-employee request body — a partial update. Every field is optional: a {@code null}
 * field leaves the corresponding employee attribute unchanged. {@code company_id} and actor are
 * never request fields (rule 5).
 *
 * <p>{@code nik} and {@code bankAccount} are PII (rule 6) — supplied in plaintext only when
 * changing them, column-encrypted at rest, never echoed back in plaintext.
 *
 * @param fullName the new full name, or {@code null} to leave unchanged
 * @param ptkpStatus the new PTKP status code, or {@code null} to leave unchanged
 * @param nik the new NIK (PII), or {@code null} to leave unchanged
 * @param bankAccount the new bank account (PII), or {@code null} to leave unchanged
 * @param status the new status ({@code ACTIVE}/{@code INACTIVE}), or {@code null} to leave
 *     unchanged
 */
public record UpdateEmployeeRequest(
    String fullName, String ptkpStatus, String nik, String bankAccount, String status) {}
