package id.co.nativeapp.gateway.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Maps a validated Keycloak access token to authorities, reading roles from both the top-level
 * {@code roles} claim (the curated realm-role protocol mapper) and Keycloak's conventional {@code
 * realm_access.roles}, then filtering to the curated <em>business</em> roles only.
 *
 * <p>The signature, issuer, and expiry are already verified by the resource-server JWKS decoder
 * before this converter runs; here we only project claims into Spring Security authorities so the
 * downstream {@code X-Roles} header reflects exactly what the token granted. Roles are exposed as
 * {@code ROLE_*} authorities (Spring's convention) and the raw role names are recovered downstream
 * by {@link id.co.nativeapp.gateway.filter.TenantContextHeaderFilter}.
 *
 * <p><strong>Least privilege.</strong> Keycloak's {@code realm_access.roles} always carries
 * infrastructure roles ({@code default-roles-native}, {@code offline_access}, {@code
 * uma_authorization}) that are meaningless to a downstream service and would leak the IdP's
 * internal authorization model across the trust boundary. We therefore whitelist the curated
 * business roles ({@code owner}/{@code manager}/{@code cashier}) and drop everything else, so
 * {@code X-Roles} carries only roles a service actually authorizes on.
 */
public final class TenantJwtAuthoritiesConverter
    implements Converter<Jwt, AbstractAuthenticationToken> {

  /** Standard OIDC subject claim — the acting principal. */
  public static final String SUBJECT_CLAIM = "sub";

  /** Custom claim carrying the tenant id (a protocol mapper maps the user attribute into it). */
  public static final String COMPANY_ID_CLAIM = "company_id";

  /** Flat list of role names placed on the token by a protocol mapper. */
  public static final String ROLES_CLAIM = "roles";

  /**
   * The curated business roles a downstream service authorizes on. Only these are projected into
   * authorities / {@code X-Roles}; Keycloak's infrastructure roles ({@code default-roles-native},
   * {@code offline_access}, {@code uma_authorization}, ...) are dropped at the edge.
   */
  public static final Set<String> BUSINESS_ROLES =
      Set.of("owner", "manager", "cashier", "employee");

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    Collection<GrantedAuthority> authorities = new ArrayList<>();
    for (String role : extractRoles(jwt)) {
      authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
    }
    return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
  }

  /**
   * Recovers the login's allowed company ids from the {@code company_id} claim (multi-company
   * ownership, ADR 0021). The claim is {@code string | string[]}: a multi-valued Keycloak mapper
   * emits a JSON array (one entry per company the login belongs to, first = the default active
   * company), while tokens minted before the mapper change carry a scalar — both shapes normalize
   * to a list here so old cached tokens keep working through the rollout. Blank values dropped;
   * deduplicated, order-preserving. Empty when the login has no company yet (pre-onboarding).
   */
  public static List<String> extractCompanyIds(Jwt jwt) {
    Object claim = jwt.getClaim(COMPANY_ID_CLAIM);
    List<String> ids = new ArrayList<>();
    if (claim instanceof String s) {
      if (!s.isBlank()) {
        ids.add(s);
      }
    } else if (claim instanceof Collection<?> values) {
      for (Object value : values) {
        if (value == null) {
          continue;
        }
        String id = value.toString();
        if (!id.isBlank() && !ids.contains(id)) {
          ids.add(id);
        }
      }
    }
    return ids;
  }

  /**
   * Recovers the curated business role names from the token. Candidates come from the flat {@code
   * roles} claim (the realm-role protocol mapper) and {@code realm_access.roles} (Keycloak's
   * default placement), but only roles in {@link #BUSINESS_ROLES} are kept — the {@code
   * default-roles-*} / {@code offline_access} / {@code uma_authorization} infrastructure noise is
   * dropped so it never leaks downstream. Deduplicated, order-preserving.
   */
  public static List<String> extractRoles(Jwt jwt) {
    List<String> roles = new ArrayList<>();
    addCuratedRoles(jwt.getClaim(ROLES_CLAIM), roles);
    Object realmAccess = jwt.getClaim("realm_access");
    if (realmAccess instanceof Map<?, ?> m) {
      addCuratedRoles(m.get(ROLES_CLAIM), roles);
    }
    return roles;
  }

  private static void addCuratedRoles(Object claim, List<String> roles) {
    if (claim instanceof Collection<?> c) {
      for (Object r : c) {
        if (r == null) {
          continue;
        }
        String name = r.toString();
        if (BUSINESS_ROLES.contains(name) && !roles.contains(name)) {
          roles.add(name);
        }
      }
    }
  }
}
