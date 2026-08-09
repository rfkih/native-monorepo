package id.co.nativeapp.org.user.dto;

import java.util.List;

/**
 * Request body for {@code PATCH /api/v1/users/{id}} — update a teammate's role SET and/or enabled
 * status. Both fields are optional (nullable), but at least one must be present; the service
 * rejects a request where both are null with a {@code 400}.
 *
 * <p><strong>{@code roles} is a SET, not a single role</strong> (preset role-based access model
 * Phase 1): a login may hold several business roles at once (e.g. {@code hr} + {@code accountant}),
 * and this REPLACES the target's entire current business-role set with the given one — it is not an
 * add/remove delta. Every element, when provided, must be one of the allowed business roles
 * (validated in the service layer for a stable RFC-7807 type URI on rejection). The {@code enabled}
 * field, when {@code false}, disables the account (the only form of user deletion — hard-delete is
 * not supported). Setting {@code enabled=false} on the caller themselves triggers a {@code 409
 * SelfLockout}, as does replacing the caller's own role set with one that no longer contains {@code
 * owner}.
 *
 * @param roles the new business role set, or {@code null} to leave the target's roles unchanged
 * @param enabled the new enabled state, or {@code null} to leave unchanged
 */
public record PatchUserRequest(List<String> roles, Boolean enabled) {}
