package id.co.nativeapp.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.gateway.security.TenantJwtAuthoritiesConverter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Pure unit proof (no Spring, no containers) that the converter maps token claims to authorities
 * and recovers role names from both the flat {@code roles} claim and Keycloak's {@code
 * realm_access.roles}, deduplicated.
 */
class TenantJwtAuthoritiesConverterTest {

  private static Jwt jwt(Map<String, Object> claims) {
    Jwt.Builder builder =
        Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .subject("user-123");
    claims.forEach(builder::claim);
    return builder.build();
  }

  @Test
  void mapsFlatRolesClaimToRoleAuthoritiesAndKeepsTheSubjectAsTheName() {
    Jwt token = jwt(Map.of("company_id", "11111111", "roles", List.of("owner", "manager")));

    AbstractAuthenticationToken auth = new TenantJwtAuthoritiesConverter().convert(token);

    assertThat(auth.getName()).isEqualTo("user-123");
    assertThat(auth.getAuthorities())
        .extracting(Object::toString)
        .containsExactlyInAnyOrder("ROLE_owner", "ROLE_manager");
  }

  @Test
  void mergesFlatRolesWithRealmAccessRolesAndDeduplicates() {
    Jwt token =
        jwt(
            Map.of(
                "roles",
                List.of("owner"),
                "realm_access",
                Map.of("roles", List.of("owner", "cashier"))));

    List<String> roles = TenantJwtAuthoritiesConverter.extractRoles(token);

    assertThat(roles).containsExactly("owner", "cashier");
  }

  @Test
  void yieldsNoAuthoritiesWhenNoRoleClaimIsPresent() {
    Jwt token = jwt(Map.of("company_id", "11111111"));

    AbstractAuthenticationToken auth = new TenantJwtAuthoritiesConverter().convert(token);

    assertThat(auth.getAuthorities()).isEmpty();
    assertThat(TenantJwtAuthoritiesConverter.extractRoles(token)).isEmpty();
  }

  @Test
  void theEmployeeRoleSurvivesTheCuratedWhitelist() {
    // The employee self-service surface depends on the role reaching X-Roles; a whitelist
    // omission would strip it at the edge and silently 403 every /me call.
    Jwt token = jwt(Map.of("realm_access", Map.of("roles", List.of("employee", "cashier"))));

    List<String> roles = TenantJwtAuthoritiesConverter.extractRoles(token);

    assertThat(roles).containsExactly("employee", "cashier");
  }

  @Test
  void dropsKeycloakInfrastructureRolesKeepingOnlyCuratedBusinessRoles() {
    // realm_access carries the Keycloak default noise; only the curated business roles survive.
    Jwt token =
        jwt(
            Map.of(
                "realm_access",
                Map.of(
                    "roles",
                    List.of(
                        "default-roles-native",
                        "offline_access",
                        "uma_authorization",
                        "owner",
                        "cashier"))));

    List<String> roles = TenantJwtAuthoritiesConverter.extractRoles(token);

    assertThat(roles).containsExactly("owner", "cashier");
    assertThat(roles).doesNotContain("offline_access", "uma_authorization", "default-roles-native");
  }
}
