package id.co.nativeapp.employee.employee.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/employees/{employeeId}/login-link} — attach a console login
 * to an employee, and (optionally) hold the one-time password issued for it. The {@code userId} is
 * the Keycloak subject id returned by the org-service invite (an opaque, stable, non-PII id).
 *
 * <p>{@code temporaryPassword} is OPTIONAL and, when present, is stored ENCRYPTED (ADR 0014) so the
 * owner can hand it to the employee from the detail page until the employee first signs in. The
 * call is idempotent on the link, so the reset flow reuses it to replace the held password with a
 * fresh one. NEVER logged.
 *
 * @param userId the login's Keycloak subject id
 * @param temporaryPassword the one-time password to hold encrypted, or null to leave it unchanged
 */
public record LoginLinkRequest(
    @NotBlank @Size(max = 64) String userId, @Size(max = 128) String temporaryPassword) {}
