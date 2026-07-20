package id.co.nativeapp.org.user.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/v1/users} (individual element) and {@code PATCH
 * /api/v1/users/{id}}.
 *
 * <p>Represents a team member as visible to the caller: their identity ({@code id}, {@code
 * username}, {@code email}), their realm roles, and their account status.
 *
 * <p>The mapping from the service-layer {@code KeycloakUser} carrier lives in {@code UserService}
 * (service → dto), not here — a dto must not depend on the service layer (ArchUnit layering rule).
 *
 * @param id the Keycloak user UUID
 * @param username the Keycloak username
 * @param email the user's email address
 * @param roles the business realm roles (owner/manager/cashier)
 * @param enabled whether the account is active
 */
public record UserResponse(
    String id, String username, String email, List<String> roles, boolean enabled) {}
