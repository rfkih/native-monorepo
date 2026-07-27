package id.co.nativeapp.org.user.service;

import id.co.nativeapp.org.user.dto.InviteUserResponse;
import id.co.nativeapp.org.user.dto.PatchUserRequest;
import id.co.nativeapp.org.user.dto.UserResponse;
import id.co.nativeapp.org.user.service.KeycloakAdminClient.InviteResult;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Business logic for team / user management within a company tenant.
 *
 * <p><strong>NOT {@code @Transactional}</strong> — this service delegates entirely to Keycloak via
 * {@link KeycloakAdminClient}; there is no local database interaction for these operations. The
 * Keycloak calls are not transactional by nature, so wrapping them in a Spring transaction would be
 * meaningless and misleading.
 *
 * <p><strong>Cross-tenant identity guard (the key invariant).</strong> Every operation that
 * addresses a specific user by id ({@link #getUserById(String)}, {@link #patchUser(String,
 * PatchUserRequest)}, {@link #deactivateUser(String)}) resolves the target user's {@code
 * company_id} attribute FROM Keycloak and rejects the call with a {@link UserNotFoundException}
 * (404) if it does not match the caller's tenant from {@link TenantContext#require()}. The 404
 * response (not 403) is intentional: a caller must not be able to enumerate the existence of other
 * tenants' user ids.
 *
 * <p><strong>Self-lockout guard.</strong> The caller's Keycloak user id (the JWT {@code sub},
 * available via {@link TenantContext.Tenant#actor()}) is compared against the target user id for
 * PATCH-disable and DELETE (deactivate) operations. A caller cannot disable or demote themselves.
 *
 * <p><strong>PII hygiene (rule 6).</strong> Email addresses and temporary passwords are NEVER
 * logged. Log statements carry only stable, non-PII identifiers (user UUIDs, company UUIDs, roles).
 */
@Service
public class UserService {

  private static final Logger log = LoggerFactory.getLogger(UserService.class);

  /** The allowed business roles for validation. */
  private static final Set<String> ALLOWED_ROLES = Set.of("owner", "manager", "cashier");

  private final KeycloakAdminClient keycloak;
  private final UserOutletAssignmentService outletAssignments;

  public UserService(KeycloakAdminClient keycloak, UserOutletAssignmentService outletAssignments) {
    this.keycloak = keycloak;
    this.outletAssignments = outletAssignments;
  }

  // ---------------------------------------------------------------------------
  // List users
  // ---------------------------------------------------------------------------

  /**
   * Lists all users belonging to the caller's company tenant.
   *
   * <p>The company id comes exclusively from {@link TenantContext#require()} — never from a request
   * parameter (rule 5).
   *
   * @return users belonging to the caller's company
   */
  public List<UserResponse> listUsers() {
    String companyId = TenantContext.require().companyId();
    List<KeycloakUser> users = keycloak.listUsersByCompanyId(companyId);

    // Enrich each user with their active outlet count in one grouped query (no N+1).
    // Goes through the assignment SERVICE (not the reader) so the empty-user-list guard
    // applies — a bare IN () is a Postgres syntax error.
    // The user list from a single company is small (< 1 000), so no partitioning needed here.
    List<String> userIds = users.stream().map(KeycloakUser::id).toList();
    Map<String, Long> countByUser = outletAssignments.outletCountsByUserIds(userIds);

    return users.stream()
        .map(
            u ->
                new UserResponse(
                    u.id(),
                    u.username(),
                    u.email(),
                    u.roles(),
                    u.enabled(),
                    countByUser.getOrDefault(u.id(), 0L).intValue()))
        .toList();
  }

  // ---------------------------------------------------------------------------
  // Invite user
  // ---------------------------------------------------------------------------

  /**
   * Invites a new teammate into the caller's company by creating a Keycloak user with a
   * server-generated temporary password and the {@code UPDATE_PASSWORD} required action.
   *
   * <p>The company id on the new user comes from {@link TenantContext#require()} (rule 5). A
   * duplicate email is rejected with {@link EmailAlreadyExistsException} (409). An invalid role is
   * rejected with {@link InvalidRoleException} (400).
   *
   * @param email the invitee's email — NEVER logged here
   * @param role the initial role ({@code owner}/{@code manager}/{@code cashier})
   * @return the invite response containing the temporary password (returned ONCE — not stored)
   */
  public InviteUserResponse inviteUser(String email, String role) {
    validateRole(role);

    String companyId = TenantContext.require().companyId();

    // Pre-check the email before creating the user to surface the 409 cleanly.
    if (keycloak.usernameOrEmailExists(email)) {
      throw new EmailAlreadyExistsException(email);
    }

    // createInvitedUser returns the Keycloak userId AND the generated temporary password.
    // The password is NEVER logged — the InviteResult carries it only to pass it to the response.
    InviteResult result = keycloak.createInvitedUser(email, companyId, role);

    log.info(
        "Invited user created: userId={}, companyId={}, role={}", result.userId(), companyId, role);
    return new InviteUserResponse(result.userId(), email, role, result.temporaryPassword());
  }

  // ---------------------------------------------------------------------------
  // Get user by id (used internally for guard resolution)
  // ---------------------------------------------------------------------------

  /**
   * Retrieves a user by Keycloak id, enforcing the cross-tenant guard.
   *
   * <p>Returns 404 (via {@link UserNotFoundException}) if the user does not exist OR if they belong
   * to a different company — the caller cannot distinguish these cases (anti-enumeration).
   *
   * @param userId the Keycloak user UUID from the path
   * @return the resolved user, guaranteed to belong to the caller's tenant
   */
  public UserResponse getUserById(String userId) {
    return toResponse(resolveInTenant(userId));
  }

  // ---------------------------------------------------------------------------
  // Patch user
  // ---------------------------------------------------------------------------

  /**
   * Updates a teammate's role and/or enabled status.
   *
   * <p>At least one of {@code role} or {@code enabled} must be non-null. The cross-tenant guard is
   * enforced (see class javadoc). The self-lockout guard rejects:
   *
   * <ul>
   *   <li>Setting {@code enabled=false} on yourself (deactivating yourself), and
   *   <li>Changing your own role away from {@code owner} (self-demotion).
   * </ul>
   *
   * @param userId the target user's Keycloak UUID
   * @param request the patch request; at least one non-null field required
   * @return the updated user state
   */
  public UserResponse patchUser(String userId, PatchUserRequest request) {
    if (request.role() == null && request.enabled() == null) {
      // The controller should guard this, but belt-and-suspenders: both null is a no-op and
      // the spec requires a 400 here rather than silently succeeding.
      throw new IllegalArgumentException("At least one of 'role' or 'enabled' must be provided");
    }

    if (request.role() != null) {
      validateRole(request.role());
    }

    TenantContext.Tenant caller = TenantContext.require();
    String callerId = caller.actor();

    // Resolve target user + cross-tenant guard (throws 404 if absent or cross-tenant).
    KeycloakUser target = resolveInTenant(userId);

    // Self-lockout guard: cannot disable self.
    if (target.id().equals(callerId) && Boolean.FALSE.equals(request.enabled())) {
      throw new SelfLockoutException("You cannot disable your own account.");
    }

    // Self-lockout guard: cannot demote self away from owner.
    if (target.id().equals(callerId)
        && request.role() != null
        && target.roles().contains("owner")
        && !request.role().equals("owner")) {
      throw new SelfLockoutException(
          "You cannot change your own role away from 'owner'. "
              + "Assign the owner role to another user first.");
    }

    // Apply changes.
    if (request.role() != null) {
      keycloak.replaceRealmRole(userId, request.role());
    }
    if (request.enabled() != null) {
      keycloak.setEnabled(userId, request.enabled());
    }

    log.info(
        "Patched user: userId={}, callerCompanyId={}, role={}, enabled={}",
        userId,
        caller.companyId(),
        request.role(),
        request.enabled());

    // Re-fetch the updated user to return current state.
    return toResponse(resolveInTenant(userId));
  }

  // ---------------------------------------------------------------------------
  // Deactivate user (soft-delete)
  // ---------------------------------------------------------------------------

  /**
   * Deactivates (disables) the target user — a soft-delete, never a hard-delete.
   *
   * <p>The cross-tenant guard is enforced. The self-lockout guard rejects deactivating yourself.
   *
   * @param userId the target user's Keycloak UUID
   */
  public void deactivateUser(String userId) {
    TenantContext.Tenant caller = TenantContext.require();
    String callerId = caller.actor();

    // Resolve target user + cross-tenant guard.
    KeycloakUser target = resolveInTenant(userId);

    // Self-lockout guard.
    if (target.id().equals(callerId)) {
      throw new SelfLockoutException("You cannot deactivate your own account.");
    }

    keycloak.setEnabled(userId, false);

    log.info("Deactivated user: userId={}, callerCompanyId={}", userId, caller.companyId());
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  /**
   * Resolves a user by id and enforces the cross-tenant guard.
   *
   * <p>Returns the {@link KeycloakUser} if it exists AND belongs to the caller's tenant. Returns
   * {@link UserNotFoundException} (404) in ALL other cases — user does not exist, user belongs to a
   * different company. The 404 (not 403) is the anti-enumeration design: a caller cannot learn
   * whether a user id exists in a different tenant.
   */
  private KeycloakUser resolveInTenant(String userId) {
    String callerCompanyId = TenantContext.require().companyId();

    KeycloakUser user =
        keycloak.getUserById(userId).orElseThrow(() -> new UserNotFoundException(userId));

    // Cross-tenant guard: reject if the user's company_id does not match the caller's tenant.
    // Use 404 (not 403) so the caller cannot enumerate existence of other tenants' user ids.
    if (!callerCompanyId.equals(user.companyId())) {
      throw new UserNotFoundException(userId);
    }

    return user;
  }

  /** Validates that {@code role} is one of the allowed business roles. */
  private static void validateRole(String role) {
    if (!ALLOWED_ROLES.contains(role)) {
      throw new InvalidRoleException(role);
    }
  }

  /**
   * Maps the service-layer {@link KeycloakUser} carrier to the boundary {@link UserResponse} dto.
   * Lives here (service → dto), not on the dto, so the dto never depends on the service layer
   * (ArchUnit layering rule).
   */
  private static UserResponse toResponse(KeycloakUser user) {
    return UserResponse.withoutCount(
        user.id(), user.username(), user.email(), user.roles(), user.enabled());
  }
}
