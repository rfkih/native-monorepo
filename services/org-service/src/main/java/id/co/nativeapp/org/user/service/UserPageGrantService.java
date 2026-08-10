package id.co.nativeapp.org.user.service;

import id.co.nativeapp.org.user.dto.MyPagesResponse;
import id.co.nativeapp.tenant.TenantContext;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Business logic for per-login page grants (the adjustable, subtractive, console-only page access).
 * Orchestrates {@link UserPageGrantWriter} (replace-set) and {@link UserPageGrantReader}; the
 * transaction boundary + RLS GUC live on those beans (the {@code *Writer}/{@code *Reader} pattern).
 *
 * <p>Addressed-by-id operations enforce the cross-tenant guard (resolve the target in the caller's
 * tenant, 404 otherwise — anti-enumeration, same contract as {@link UserService}). The {@code /me}
 * read needs no guard: a caller reading their own grants cannot enumerate anyone else.
 *
 * <p><strong>Role-hierarchy guard.</strong> {@link #replacePagesForUser} additionally runs the
 * target through {@link TeamAdministrationGuard#authorizeRoleAdministration} (no role change, so
 * only the target-hierarchy half fires): a non-owner (manager) caller may re-scope the pages of a
 * floor login but not of an owner/manager/accountant/hr login — the same fine-grained boundary
 * {@link UserService} enforces for role/enabled changes (ADR 0052).
 */
@Service
public class UserPageGrantService {

  /** The grantable page keys (mirrors the console's grantable surface). */
  static final Set<String> ALLOWED_PAGE_KEYS = Set.of("pos", "kitchen", "menu", "me");

  private final KeycloakAdminClient keycloak;
  private final UserPageGrantWriter writer;
  private final UserPageGrantReader reader;
  private final TeamAdministrationGuard guard;

  public UserPageGrantService(
      KeycloakAdminClient keycloak,
      UserPageGrantWriter writer,
      UserPageGrantReader reader,
      TeamAdministrationGuard guard) {
    this.keycloak = keycloak;
    this.writer = writer;
    this.reader = reader;
    this.guard = guard;
  }

  /**
   * The caller's own page-access mode (resolved from the JWT sub; no cross-tenant guard needed).
   */
  public MyPagesResponse pagesForMe() {
    String callerId = TenantContext.require().actor();
    return MyPagesResponse.of(reader.activePageKeys(callerId));
  }

  /** A specific user's page-access mode (owner/manager; cross-tenant guarded). */
  public MyPagesResponse pagesForUser(String userId) {
    TenantContext.require();
    resolveInTenant(userId);
    return MyPagesResponse.of(reader.activePageKeys(userId));
  }

  /**
   * Replace-set the user's page grants. Every key is whitelist-validated before any write
   * (all-or-nothing). An empty set clears grants back to the full role surface.
   *
   * <p>Role-hierarchy guard: the target's current roles are run through {@link
   * TeamAdministrationGuard#authorizeRoleAdministration} with no requested-roles change — a
   * non-owner (manager) caller may re-scope a floor login's pages but not an owner / manager /
   * accountant / hr login's (ADR 0052).
   *
   * @throws UserNotFoundException if the user is absent or cross-tenant (404)
   * @throws InvalidPageKeyException if any key is not in the whitelist (400)
   * @throws InsufficientPrivilegeException if a non-owner caller targets a privileged login (403)
   */
  public MyPagesResponse replacePagesForUser(String userId, List<String> pageKeys) {
    TenantContext.require();
    KeycloakUser target = resolveInTenant(userId);
    guard.authorizeRoleAdministration(new LinkedHashSet<>(target.roles()), null);
    for (String key : pageKeys) {
      if (!ALLOWED_PAGE_KEYS.contains(key)) {
        throw new InvalidPageKeyException(key);
      }
    }
    writer.replaceGrants(userId, pageKeys);
    return MyPagesResponse.of(reader.activePageKeys(userId));
  }

  /**
   * Resolves a user by id and enforces the cross-tenant guard. Returns the {@link KeycloakUser} if
   * it exists AND belongs to the caller's tenant; throws {@link UserNotFoundException} (404) in ALL
   * other cases — user does not exist, or belongs to a different company (anti-enumeration, mirrors
   * {@link UserService#resolveInTenant}).
   */
  private KeycloakUser resolveInTenant(String userId) {
    String callerCompanyId = TenantContext.require().companyId();
    KeycloakUser user =
        keycloak.getUserById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    // A multi-company login belongs to each of its companies (ADR 0021); 404 anti-enumeration.
    if (!user.belongsTo(callerCompanyId)) {
      throw new UserNotFoundException(userId);
    }
    return user;
  }
}
