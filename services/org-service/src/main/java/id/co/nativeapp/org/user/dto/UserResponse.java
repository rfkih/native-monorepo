package id.co.nativeapp.org.user.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/v1/users} (individual element) and {@code PATCH
 * /api/v1/users/{id}}.
 *
 * <p>Represents a team member as visible to the caller: their identity ({@code id}, {@code
 * username}, {@code email}), their realm roles, their account status, and the count of active
 * outlet assignments for the Team page Outlets column.
 *
 * <p>The mapping from the service-layer {@code KeycloakUser} carrier lives in {@code UserService}
 * (service → dto), not here — a dto must not depend on the service layer (ArchUnit layering rule).
 *
 * @param id the Keycloak user UUID
 * @param username the Keycloak username
 * @param email the user's email address
 * @param roles the business realm roles (owner/manager/cashier)
 * @param enabled whether the account is active
 * @param outletCount the number of active outlet assignments for this user (0 when unassigned)
 */
public record UserResponse(
    String id,
    String username,
    String email,
    List<String> roles,
    boolean enabled,
    int outletCount) {

  /**
   * Convenience factory that produces a response with {@code outletCount = 0}. Used by operations
   * that return a single user (PATCH, GET by id) where the outlet count is not included — callers
   * that do not need the count (e.g. invite/patch responses) use this to avoid carrying a
   * meaningless zero.
   */
  public static UserResponse withoutCount(
      String id, String username, String email, List<String> roles, boolean enabled) {
    return new UserResponse(id, username, email, roles, enabled, 0);
  }
}
