package id.co.nativeapp.finance.consolidation.service;

import id.co.nativeapp.finance.consolidation.domain.GroupConsolidationRole;
import id.co.nativeapp.finance.consolidation.dto.GroupRequester;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Builds a {@link GroupRequester} from the request's VERIFIED identity (P3d SEAM 4b): the {@code
 * company_id} + actor from the bound {@link TenantContext} (set by {@code libs/security}'s
 * TenantBindingFilter from the locally-validated JWT, never a client header), and the group-level
 * roles read DIRECTLY from the JWT claims.
 *
 * <p><strong>Roles come solely from the verified token.</strong> Group roles are read from the flat
 * {@code roles} claim and Keycloak's {@code realm_access.roles} (the same placements the gateway's
 * {@code TenantJwtAuthoritiesConverter} reads), but ONLY the group-consolidation roles ({@link
 * GroupConsolidationRole#VIEW} / {@link GroupConsolidationRole#CLOSE}) are projected — never an
 * arbitrary claim a client could forge into the request, and never the per-company business roles.
 * The signature/issuer/expiry are already validated by the resource-server decoder before a
 * controller runs, so the claims here are trustworthy.
 *
 * <p>The tenant ({@code company_id}) is taken from {@link TenantContext}, NOT re-read from the
 * token, so it is exactly the tenant RLS is keyed on — the candidate lead the service validates.
 * When no JWT is present (the {@code dev} profile's header-trust path) the roles default to empty;
 * a deployment that needs group access in dev grants it via the dev header path, but production
 * authority comes only from the token.
 */
@Component
public class GroupRequesterFactory {

  /** Flat list of role names placed on the token by a protocol mapper (gateway convention). */
  private static final String ROLES_CLAIM = "roles";

  /** The group-consolidation roles this service authorizes on — nothing else is projected. */
  private static final Set<String> GROUP_ROLES =
      Set.of(GroupConsolidationRole.VIEW, GroupConsolidationRole.CLOSE);

  /**
   * The requester for the current request: the bound tenant + actor, with the group roles the
   * verified JWT granted.
   *
   * @throws IllegalStateException if no tenant is bound (no controller should run unscoped — the
   *     security chain binds the tenant before the controller)
   */
  public GroupRequester current() {
    TenantContext.Tenant tenant = TenantContext.require();
    UUID companyId = UUID.fromString(tenant.companyId());
    return new GroupRequester(companyId, tenant.actor(), currentGroupRoles());
  }

  /** The group-consolidation roles on the current verified JWT (empty when no JWT is present). */
  private static Set<String> currentGroupRoles() {
    Jwt jwt = currentJwt();
    if (jwt == null) {
      return Set.of();
    }
    Set<String> roles = new LinkedHashSet<>();
    addGroupRoles(jwt.getClaim(ROLES_CLAIM), roles);
    Object realmAccess = jwt.getClaim("realm_access");
    if (realmAccess instanceof Map<?, ?> m) {
      addGroupRoles(m.get(ROLES_CLAIM), roles);
    }
    return roles;
  }

  private static void addGroupRoles(Object claim, Set<String> roles) {
    if (claim instanceof Collection<?> c) {
      for (Object r : c) {
        if (r != null && GROUP_ROLES.contains(r.toString())) {
          roles.add(r.toString());
        }
      }
    }
  }

  private static Jwt currentJwt() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken token) {
      return token.getToken();
    }
    return null;
  }
}
