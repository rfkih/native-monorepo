package id.co.nativeapp.employee.employee.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/employees/{employeeId}/login-link} — attach a console login
 * to an employee. The {@code userId} is the Keycloak subject id returned by the org-service invite
 * (an opaque, stable, non-PII identifier).
 *
 * @param userId the login's Keycloak subject id
 */
public record LoginLinkRequest(@NotBlank @Size(max = 64) String userId) {}
