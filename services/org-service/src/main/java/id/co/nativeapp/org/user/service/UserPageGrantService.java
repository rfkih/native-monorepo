package id.co.nativeapp.org.user.service;

import id.co.nativeapp.org.user.dto.MyPagesResponse;
import id.co.nativeapp.tenant.TenantContext;
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
 */
@Service
public class UserPageGrantService {

  /** The grantable page keys (mirrors the console's grantable surface). */
  static final Set<String> ALLOWED_PAGE_KEYS = Set.of("pos", "kitchen", "menu", "me");

  private final KeycloakAdminClient keycloak;
  private final UserPageGrantWriter writer;
  private final UserPageGrantReader reader;

  public UserPageGrantService(
      KeycloakAdminClient keycloak, UserPageGrantWriter writer, UserPageGrantReader reader) {
    this.keycloak = keycloak;
    this.writer = writer;
    this.reader = reader;
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
   * @throws UserNotFoundException if the user is absent or cross-tenant (404)
   * @throws InvalidPageKeyException if any key is not in the whitelist (400)
   */
  public MyPagesResponse replacePagesForUser(String userId, List<String> pageKeys) {
    TenantContext.require();
    resolveInTenant(userId);
    for (String key : pageKeys) {
      if (!ALLOWED_PAGE_KEYS.contains(key)) {
        throw new InvalidPageKeyException(key);
      }
    }
    writer.replaceGrants(userId, pageKeys);
    return MyPagesResponse.of(reader.activePageKeys(userId));
  }

  private void resolveInTenant(String userId) {
    String callerCompanyId = TenantContext.require().companyId();
    KeycloakUser user =
        keycloak.getUserById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    if (!callerCompanyId.equals(user.companyId())) {
      throw new UserNotFoundException(userId);
    }
  }
}
