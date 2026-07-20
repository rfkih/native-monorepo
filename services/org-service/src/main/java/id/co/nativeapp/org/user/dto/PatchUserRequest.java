package id.co.nativeapp.org.user.dto;

/**
 * Request body for {@code PATCH /api/v1/users/{id}} — update a teammate's role and/or enabled
 * status. Both fields are optional (nullable), but at least one must be present; the service
 * rejects a request where both are null with a {@code 400}.
 *
 * <p>The {@code role}, when provided, must be one of {@code owner}, {@code manager}, {@code
 * cashier} — validated in the service layer for a stable RFC-7807 type URI. The {@code enabled}
 * field, when {@code false}, disables the account (the only form of user deletion — hard-delete is
 * not supported). Setting {@code enabled=false} on the caller themselves triggers a {@code 409
 * SelfLockout}.
 *
 * @param role the new role ({@code owner}/{@code manager}/{@code cashier}), or {@code null} to
 *     leave unchanged
 * @param enabled the new enabled state, or {@code null} to leave unchanged
 */
public record PatchUserRequest(String role, Boolean enabled) {}
