package id.co.nativeapp.org.user.service;

import id.co.nativeapp.tenant.TenantContext;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The fine-grained role-hierarchy guard behind the gateway's coarse route gate (ADR 0052).
 *
 * <p>The gateway lets both {@code owner} and {@code manager} reach every team-administration
 * surface ({@code /api/v1/users/**}, the page-grant and outlet-assignment replace-set PUTs, and the
 * device-credential lifecycle) — that route-level gate only proves the caller holds ONE of the two
 * roles, not which one, and not whether the specific action is one a {@code manager} may perform. A
 * {@code manager} runs the floor and must never be able to (a) self-grant a privileged role ({@code
 * owner}/{@code manager}/{@code accountant}/{@code hr} — the finance/payroll surfaces ADR 0052
 * withholds from managers), (b) escalate anyone else to one, or (c) touch (patch, deactivate,
 * re-scope the pages/outlets of, or reveal the device credentials guarding) a login that already
 * holds a privileged role. This class is the SINGLE place that check lives, shared by:
 *
 * <ul>
 *   <li>{@link UserService} — invite / patch (roles AND enabled) / deactivate.
 *   <li>{@link UserPageGrantService#replacePagesForUser} — page-grant replace-set PUT.
 *   <li>{@link UserOutletAssignmentService#replaceOutletsForUser} — outlet-assignment replace-set
 *       PUT.
 *   <li>{@code DeviceCredentialService} (create/reset/reveal/delete) — via {@link
 *       #requireOwnerOrManager()}, since minting/revealing a till credential is not itself a
 *       role-administration action but still must be owner/manager only, server-side, independent
 *       of the gateway route.
 * </ul>
 *
 * <p>Throws {@link InsufficientPrivilegeException} (mapped to {@code 403 Forbidden}) on any
 * violation. Never throws for an {@code owner} caller — an owner administers everyone.
 */
@Component
public class TeamAdministrationGuard {

  /**
   * The roles a NON-owner (i.e. {@code manager}) caller may assign to, or administer on, another
   * login — the floor / self-service roles. The office / privileged roles ({@code owner}, {@code
   * manager}, {@code accountant}, {@code hr}) are OWNER-administered only: a manager runs the
   * floor, and must not be able to hand out (or self-grant) finance/payroll access it does not
   * itself hold, nor escalate anyone to {@code owner}/{@code manager}. See {@link
   * #authorizeRoleAdministration} and ADR 0052.
   */
  private static final Set<String> MANAGER_MANAGEABLE_ROLES =
      Set.of("cashier", "waitress", "chef", "employee");

  private final KeycloakAdminClient keycloak;

  public TeamAdministrationGuard(KeycloakAdminClient keycloak) {
    this.keycloak = keycloak;
  }

  /**
   * Role-hierarchy guard for team administration (invite / patch-roles / patch-enabled / deactivate
   * / page-grant replace / outlet-assignment replace).
   *
   * <p>The gateway lets BOTH {@code owner} and {@code manager} reach these surfaces. That coarse
   * gate is NOT enough: without this guard a {@code manager} could {@code PATCH} their own login to
   * {@code {manager, accountant, hr}} (self-granting the finance + payroll surfaces this very model
   * exists to withhold from managers — ADR 0052) or straight to {@code owner} (full account/tenant
   * takeover), and could disable, demote, or re-scope the pages/outlets of the real owner. So:
   *
   * <ul>
   *   <li>An {@code owner} caller administers everyone — returns immediately (validation and
   *       self-lockout guards, where applicable, still apply on the caller side).
   *   <li>A NON-owner caller (a manager) may assign ONLY {@link #MANAGER_MANAGEABLE_ROLES} (never
   *       grant {@code owner}/{@code manager}/{@code accountant}/{@code hr}), and may administer
   *       ONLY a login that currently holds none but those floor roles (never touch an owner /
   *       manager / accountant / hr login).
   * </ul>
   *
   * <p>Combined with {@link UserService}'s self-lockout guard (an owner cannot drop their own
   * {@code owner} role), a company always retains at least one owner. Throws {@link
   * InsufficientPrivilegeException} (403).
   *
   * @param currentTargetRoles the target's business roles today — EMPTY for an invite (no existing
   *     login to protect)
   * @param requestedRoles the roles being assigned, or {@code null} when no role change is
   *     requested (a manager still may not administer a privileged target's other settings)
   */
  public void authorizeRoleAdministration(
      Set<String> currentTargetRoles, Set<String> requestedRoles) {
    Set<String> caller = callerRoles();
    if (caller.contains("owner")) {
      return; // an owner administers everyone
    }
    // Non-owner caller (a manager): may only GRANT floor roles...
    if (requestedRoles != null) {
      for (String role : requestedRoles) {
        if (!MANAGER_MANAGEABLE_ROLES.contains(role)) {
          throw new InsufficientPrivilegeException(
              "Only an owner may assign the '" + role + "' role.");
        }
      }
    }
    // ...and may only ADMINISTER a login that currently holds floor roles only.
    for (String role : currentTargetRoles) {
      if (!MANAGER_MANAGEABLE_ROLES.contains(role)) {
        throw new InsufficientPrivilegeException(
            "Only an owner may change an owner, manager, accountant, or HR login.");
      }
    }
  }

  /**
   * Requires the caller to hold {@code owner} or {@code manager} — the guard behind the
   * device-credential lifecycle ({@code create}/{@code reset}/{@code reveal}/{@code delete}).
   *
   * <p>A device-credential reveal returns a DECRYPTED till password. The gateway route ({@code
   * owner}/{@code manager} via the {@code /org-units} OPS route) is the primary gate, but a route
   * mistake must not re-expose a decrypted secret — the org-units read-widen CRITICAL finding
   * showed that gateway-only authorization on a decrypted secret is fragile, so this re-checks
   * owner/manager server-side too. Owner/manager mirrors the gateway + ADR 0049 (a manager may
   * reveal a till credential). Callers should invoke this FIRST, before any reader/existence check,
   * so an unauthorized caller gets a uniform 403 regardless of whether the credential exists (no
   * enumeration).
   *
   * @throws InsufficientPrivilegeException (403) if the caller holds neither role
   */
  public void requireOwnerOrManager() {
    Set<String> caller = callerRoles();
    if (!caller.contains("owner") && !caller.contains("manager")) {
      throw new InsufficientPrivilegeException("Only an owner or manager may perform this action.");
    }
  }

  /**
   * Resolves the caller's own business roles from Keycloak (authoritative — never trusts a stale
   * JWT claim for this fine-grained check).
   *
   * <p>Mirrors {@code UserService#resolveInTenant}: fetches the caller by their actor id and
   * enforces that they {@link KeycloakUser#belongsTo(String)} the bound tenant, failing closed with
   * {@link UserNotFoundException} (never {@link InsufficientPrivilegeException}) if either check
   * fails — an absent or cross-tenant caller is a data-integrity fault, not a mere privilege gap.
   */
  private Set<String> callerRoles() {
    String actorId = TenantContext.require().actor();
    String callerCompanyId = TenantContext.require().companyId();
    KeycloakUser caller =
        keycloak.getUserById(actorId).orElseThrow(() -> new UserNotFoundException(actorId));
    if (!caller.belongsTo(callerCompanyId)) {
      throw new UserNotFoundException(actorId);
    }
    return new LinkedHashSet<>(caller.roles());
  }
}
