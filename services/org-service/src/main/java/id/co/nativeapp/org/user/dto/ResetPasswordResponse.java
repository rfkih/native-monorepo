package id.co.nativeapp.org.user.dto;

/**
 * Response body for {@code POST /api/v1/users/{id}/reset-password} — a fresh one-time temporary
 * password for an existing login.
 *
 * <p><strong>Credential hygiene.</strong> {@code temporaryPassword} is returned ONCE here so the
 * owner/manager can hand it to the user (or hold it encrypted per ADR 0014). It is NEVER stored by
 * org-service and NEVER logged. The field name is intentionally explicit so clients are not tempted
 * to cache it.
 *
 * @param userId the Keycloak user UUID whose password was reset
 * @param temporaryPassword the one-time temporary password — NEVER log or cache this
 */
public record ResetPasswordResponse(String userId, String temporaryPassword) {}
