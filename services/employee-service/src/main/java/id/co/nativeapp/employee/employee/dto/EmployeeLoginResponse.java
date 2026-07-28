package id.co.nativeapp.employee.employee.dto;

/**
 * The login state of an employee, for the owner/manager detail surface ({@code GET
 * /api/v1/employees/{id}/login}).
 *
 * <p><strong>{@code temporaryPassword} is a CREDENTIAL</strong> — the decrypted one-time password
 * held for the owner to hand to the employee, present ONLY while the employee has not yet signed in
 * (and within the TTL backstop); {@code null} once purged/activated/expired (ADR 0014). This field
 * is returned exclusively on this owner/manager-gated endpoint and is NEVER logged. The username is
 * resolved separately from org-service (Keycloak owns it); this response carries only the {@code
 * userId} (the Keycloak {@code sub}).
 *
 * @param userId the linked login's Keycloak subject id, or {@code null} if the employee has no
 *     login
 * @param temporaryPassword the decrypted one-time password if still held+fresh, else {@code null}
 */
public record EmployeeLoginResponse(String userId, String temporaryPassword) {}
