package id.co.nativeapp.org.user.service;

import id.co.nativeapp.org.user.dto.InviteUserResponse;
import id.co.nativeapp.org.user.dto.PatchUserRequest;
import id.co.nativeapp.org.user.dto.ResetPasswordResponse;
import id.co.nativeapp.org.user.dto.UserResponse;
import id.co.nativeapp.org.user.service.KeycloakAdminClient.InviteResult;
import id.co.nativeapp.tenant.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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

  /**
   * The allowed business roles for validation.
   *
   * <p>{@code hr}/{@code accountant}/{@code chef}/{@code waitress} (preset role-based access model
   * Phase 1) join the original four: {@code hr}/{@code accountant} split the back-office surface
   * finer than {@code owner}/{@code manager}, {@code chef}/{@code waitress} ring the till like
   * {@code cashier}. Multiple roles per login are supported (invite {@code additionalRoles}; patch
   * {@code roles}) — access is the union.
   */
  private static final Set<String> ALLOWED_ROLES =
      Set.of("owner", "manager", "cashier", "employee", "hr", "accountant", "chef", "waitress");

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
   * <p>The company id on the new user comes from {@link TenantContext#require()} (rule 5). The
   * login is keyed by {@code username}; a duplicate username is rejected with {@link
   * UsernameAlreadyExistsException} (409). {@code email} is OPTIONAL (some employees have none) —
   * when present it is stored as contact metadata and a duplicate email is rejected with {@link
   * EmailAlreadyExistsException} (409). An invalid role is rejected with {@link
   * InvalidRoleException} (400).
   *
   * @param username the login identifier — NEVER logged here
   * @param email the invitee's email, or {@code null}/blank if none — NEVER logged here
   * @param role the primary role
   * @param additionalRoles further roles for the same login (e.g. an employee who can also run the
   *     POS gets {@code employee} + {@code cashier}); null/empty for just the primary
   * @return the invite response containing the temporary password (returned ONCE — not stored)
   */
  public InviteUserResponse inviteUser(
      String username, String email, String role, List<String> additionalRoles) {
    // Primary first, then de-duplicated extras — every role passes the same whitelist.
    List<String> roles = new ArrayList<>();
    roles.add(role);
    if (additionalRoles != null) {
      for (String extra : additionalRoles) {
        if (!roles.contains(extra)) {
          roles.add(extra);
        }
      }
    }
    roles.forEach(UserService::validateRole);

    String companyId = TenantContext.require().companyId();

    // Pre-check the login identifier (and the email, when given) to surface the 409 cleanly.
    if (keycloak.usernameExists(username)) {
      throw new UsernameAlreadyExistsException(username);
    }
    String email0 = (email == null || email.isBlank()) ? null : email.strip();
    if (email0 != null && keycloak.usernameOrEmailExists(email0)) {
      throw new EmailAlreadyExistsException(email0);
    }

    // createInvitedUser returns the Keycloak userId AND the generated temporary password.
    // The password is NEVER logged — the InviteResult carries it only to pass it to the response.
    InviteResult result = keycloak.createInvitedUser(username, email0, companyId, roles);

    log.info(
        "Invited user created: userId={}, companyId={}, roles={}",
        result.userId(),
        companyId,
        roles);
    return new InviteUserResponse(
        result.userId(), username, email0, role, roles, result.temporaryPassword());
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
  // Reset password
  // ---------------------------------------------------------------------------

  /**
   * Resets an existing login's password to a fresh one-time temporary password, re-arming
   * Keycloak's forced change-on-next-login. The target must belong to the caller's tenant, else
   * {@link UserNotFoundException} (404 — anti-enumeration).
   *
   * <p><strong>Role-hierarchy guard.</strong> The gateway lets both owner and manager reach this
   * endpoint, but a reset hands the caller a WORKING credential for the target. So a privileged
   * target (an {@code owner} or {@code manager} login) may be reset ONLY by an {@code owner} —
   * otherwise a manager could reset the owner's password and take over the account ({@link
   * InsufficientPrivilegeException} → 403). A manager may still reset cashier/employee logins (the
   * feature's purpose: hand an email-less employee a password).
   *
   * <p>The password is returned ONCE and is NEVER stored or logged by org-service (the caller holds
   * it encrypted — ADR 0014).
   *
   * @param userId the target login's Keycloak UUID
   * @return the new one-time temporary password (returned ONCE)
   */
  public ResetPasswordResponse resetPassword(String userId) {
    // 404 if absent or cross-tenant (anti-enumeration).
    KeycloakUser target = resolveInTenant(userId);
    // Role-hierarchy guard: only an owner may reset a privileged (owner/manager) login.
    boolean targetPrivileged =
        target.roles().contains("owner") || target.roles().contains("manager");
    if (targetPrivileged) {
      KeycloakUser caller = resolveInTenant(TenantContext.require().actor());
      if (!caller.roles().contains("owner")) {
        throw new InsufficientPrivilegeException(
            "Only an owner may reset an owner or manager login.");
      }
    }
    String temp = keycloak.resetTemporaryPassword(userId);
    log.info(
        "Password reset for user {} in company {}", userId, TenantContext.require().companyId());
    return new ResetPasswordResponse(userId, temp);
  }

  // ---------------------------------------------------------------------------
  // Patch user
  // ---------------------------------------------------------------------------

  /**
   * Updates a teammate's business-role SET and/or enabled status.
   *
   * <p>At least one of {@code roles} or {@code enabled} must be non-null. {@code roles}, when
   * provided, REPLACES the target's entire current business-role set (preset role-based access
   * model Phase 1 — a login may hold several roles at once; every element is validated against
   * {@link #ALLOWED_ROLES}). The cross-tenant guard is enforced (see class javadoc). The
   * self-lockout guard rejects:
   *
   * <ul>
   *   <li>Setting {@code enabled=false} on yourself (deactivating yourself), and
   *   <li>Replacing your own role set with one that no longer contains {@code owner}
   *       (self-demotion) — {@code hr}/{@code accountant}/{@code chef}/{@code waitress} are NOT
   *       "privileged" for this guard (only {@code owner} matters here, exactly as before).
   * </ul>
   *
   * @param userId the target user's Keycloak UUID
   * @param request the patch request; at least one non-null field required
   * @return the updated user state
   */
  public UserResponse patchUser(String userId, PatchUserRequest request) {
    if (request.roles() == null && request.enabled() == null) {
      // The controller should guard this, but belt-and-suspenders: both null is a no-op and
      // the spec requires a 400 here rather than silently succeeding.
      throw new IllegalArgumentException("At least one of 'roles' or 'enabled' must be provided");
    }

    if (request.roles() != null) {
      request.roles().forEach(UserService::validateRole);
    }

    TenantContext.Tenant caller = TenantContext.require();
    String callerId = caller.actor();

    // Resolve target user + cross-tenant guard (throws 404 if absent or cross-tenant).
    KeycloakUser target = resolveInTenant(userId);

    // Self-lockout guard: cannot disable self.
    if (target.id().equals(callerId) && Boolean.FALSE.equals(request.enabled())) {
      throw new SelfLockoutException("You cannot disable your own account.");
    }

    // Self-lockout guard: cannot demote self away from owner (only "owner" is privileged here —
    // hr/accountant/chef/waitress are ordinary roles for this guard's purposes).
    if (target.id().equals(callerId)
        && request.roles() != null
        && target.roles().contains("owner")
        && !request.roles().contains("owner")) {
      throw new SelfLockoutException(
          "You cannot remove the 'owner' role from your own account. "
              + "Assign the owner role to another user first.");
    }

    // Apply changes.
    if (request.roles() != null) {
      keycloak.replaceRealmRoles(userId, new LinkedHashSet<>(request.roles()));
    }
    if (request.enabled() != null) {
      keycloak.setEnabled(userId, request.enabled());
    }

    log.info(
        "Patched user: userId={}, callerCompanyId={}, roles={}, enabled={}",
        userId,
        caller.companyId(),
        request.roles(),
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

    // Cross-tenant guard: reject unless the user BELONGS TO the caller's tenant (a multi-company
    // login belongs to each of its companies — ADR 0021). Use 404 (not 403) so the caller cannot
    // enumerate existence of other tenants' user ids.
    if (!user.belongsTo(callerCompanyId)) {
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
