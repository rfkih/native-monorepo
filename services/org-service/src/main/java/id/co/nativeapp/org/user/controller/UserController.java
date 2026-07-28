package id.co.nativeapp.org.user.controller;

import id.co.nativeapp.org.user.dto.InviteUserRequest;
import id.co.nativeapp.org.user.dto.InviteUserResponse;
import id.co.nativeapp.org.user.dto.PatchUserRequest;
import id.co.nativeapp.org.user.dto.ResetPasswordResponse;
import id.co.nativeapp.org.user.dto.UserResponse;
import id.co.nativeapp.org.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/users} — tenant-scoped team / user management.
 *
 * <p>All endpoints are authenticated and require the {@code owner} or {@code manager} role
 * (enforced at the gateway edge by {@link id.co.nativeapp.gateway.filter.RoleAuthorizationFilter}
 * with {@code DASHBOARD_ROLES}). The tenant is derived from the JWT via the gateway's {@link
 * id.co.nativeapp.gateway.filter.TenantContextHeaderFilter} — never from the request body or a path
 * parameter (rule 5).
 *
 * <p><strong>Cross-tenant identity guard.</strong> Addressed-by-id operations (PATCH, DELETE)
 * resolve the target user's {@code company_id} from Keycloak and return 404 if it does not match
 * the caller's tenant. The 404 (not 403) is intentional: callers must not be able to enumerate the
 * existence of other tenants' user ids.
 *
 * <p><strong>Self-lockout guard.</strong> The caller cannot disable or demote themselves (409).
 *
 * <p><strong>No {@code @Transactional}.</strong> All operations are Keycloak-only; there is no
 * local database interaction. {@code @Transactional} on a controller is forbidden by the layered
 * architecture (CODE-STRUCTURE.md §3.1).
 */
@Tag(name = "Users", description = "Tenant-scoped team management — owner/manager only")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

  private final UserService userService;

  public UserController(UserService userService) {
    this.userService = userService;
  }

  /**
   * Lists all users belonging to the caller's company.
   *
   * <p>The list is scoped to the caller's tenant (from the JWT {@code company_id} claim) — no
   * cross-tenant data is returned. Returns an empty list, not a 404, if the company has no users
   * yet.
   *
   * @return {@code 200} with an array of {@link UserResponse}; empty array if none
   */
  @Operation(
      summary = "List company users",
      description =
          "Lists all users belonging to the caller's company tenant. Scoped by the JWT company_id"
              + " claim — never returns users from other tenants. Requires owner or manager role.")
  @GetMapping
  public ResponseEntity<List<UserResponse>> listUsers() {
    return ResponseEntity.ok(userService.listUsers());
  }

  /**
   * Invites a new teammate into the caller's company.
   *
   * <p>Creates a Keycloak user with:
   *
   * <ul>
   *   <li>the caller's {@code company_id} attribute (from the JWT, not the request body),
   *   <li>the chosen role (owner/manager/cashier),
   *   <li>a server-generated temporary password and the {@code UPDATE_PASSWORD} required action.
   * </ul>
   *
   * <p>The temporary password is returned ONCE in the response body — it is not stored server-side.
   * Returns {@code 409} if the email is already registered in this Keycloak realm.
   *
   * @return {@code 201 Created} with {@link InviteUserResponse} including the one-time password
   */
  @Operation(
      summary = "Invite a teammate",
      description =
          "Creates a Keycloak user in the caller's company with the chosen role and a server-"
              + "generated temporary password (returned once). The invitee must change their"
              + " password on first login (UPDATE_PASSWORD required action). Returns 409 if the"
              + " email is already taken. Requires owner or manager role.")
  @PostMapping
  public ResponseEntity<InviteUserResponse> inviteUser(
      @Valid @RequestBody InviteUserRequest request) {
    InviteUserResponse body =
        userService.inviteUser(
            request.username(), request.email(), request.role(), request.additionalRoles());
    return ResponseEntity.created(URI.create("/api/v1/users/" + body.id())).body(body);
  }

  /**
   * Updates a teammate's role and/or enabled status.
   *
   * <p>At least one of {@code role} or {@code enabled} must be provided (both null → 400). The
   * cross-tenant guard rejects targeting a user from a different company with 404. The self-lockout
   * guard rejects disabling the caller's own account or demoting the caller away from {@code owner}
   * with 409.
   *
   * @param id the target user's Keycloak UUID
   * @return {@code 200} with the updated {@link UserResponse}
   */
  @Operation(
      summary = "Update a user's role or status",
      description =
          "Updates a teammate's role (owner/manager/cashier) and/or enabled status. At least one"
              + " field must be provided. Cross-tenant targets return 404. Self-lockout (disable"
              + " self / demote own owner role) returns 409. Requires owner or manager role.")
  @PatchMapping("/{id}")
  public ResponseEntity<UserResponse> patchUser(
      @PathVariable String id, @RequestBody PatchUserRequest request) {
    UserResponse body = userService.patchUser(id, request);
    return ResponseEntity.ok(body);
  }

  /**
   * Deactivates (disables) a user — a soft-delete, never a hard-delete.
   *
   * <p>The cross-tenant guard rejects targeting a user from a different company with 404. The
   * self-lockout guard rejects deactivating the caller's own account with 409.
   *
   * @param id the target user's Keycloak UUID
   * @return {@code 204 No Content}
   */
  @Operation(
      summary = "Deactivate a user",
      description =
          "Disables (soft-deletes) a teammate's account. Hard-delete is not supported — the"
              + " account is disabled and will no longer be able to log in. Cross-tenant targets"
              + " return 404. Self-deactivation returns 409. Requires owner or manager role.")
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deactivateUser(@PathVariable String id) {
    userService.deactivateUser(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Resets a login's password to a fresh one-time temporary password (change-on-next-login).
   *
   * <p>The cross-tenant guard rejects targeting a user from a different company with 404. The
   * one-time password is returned ONCE and is never stored/logged by org-service.
   *
   * @param id the target login's Keycloak UUID
   * @return {@code 200} with the new one-time {@link ResetPasswordResponse#temporaryPassword()}
   */
  @Operation(
      summary = "Reset a login's password",
      description =
          "Issues a fresh one-time temporary password for an existing login and re-arms Keycloak's"
              + " forced change on next sign-in. The password is returned ONCE (never stored/logged"
              + " server-side). Cross-tenant targets return 404. Requires owner or manager role.")
  @PostMapping("/{id}/reset-password")
  public ResetPasswordResponse resetPassword(@PathVariable String id) {
    return userService.resetPassword(id);
  }
}
