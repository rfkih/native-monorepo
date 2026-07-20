package id.co.nativeapp.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.client.RestTemplate;

/**
 * The DEFAULT (non-dev) security path for every business service: local RS256 JWT validation plus
 * tenant binding from the verified token (#16). This is the in-service replica of the gateway's
 * single sync edge — ARCHITECTURE.md mandates each service validate the signature locally (JWKS),
 * never trust the gateway alone.
 *
 * <p>Active under any profile that is NOT {@code dev} (the production default). The {@code dev}
 * profile instead selects {@link DevSecurityConfig} (a permissive chain) so the header-trust {@code
 * DevTenantFilter} stays the tenant source with no Keycloak; the two are mutually exclusive by
 * profile, so a service runs EITHER dev OR secured.
 *
 * <p>The chain:
 *
 * <ul>
 *   <li>requires a valid token for {@code /api/v1/**} (everything that is not a probe);
 *   <li>{@code permitAll} ONLY for {@code /healthz} and the narrow probe/scrape actuator endpoints
 *       ({@code /actuator/health/**}, {@code /actuator/info}, {@code /actuator/prometheus}) — the
 *       whole {@code /actuator/**} tree is deliberately not opened;
 *   <li>validates the bearer token via the resource-server JWKS decoder ({@link #jwtDecoder}),
 *       rejecting a missing/invalid/expired/wrong-issuer token with a bare {@code 401}; and
 *   <li>installs {@link TenantBindingFilter} right after the {@link
 *       BearerTokenAuthenticationFilter} so the validated token's {@code company_id} claim + {@code
 *       sub} are bound into {@link id.co.nativeapp.tenant.TenantContext} before any controller /
 *       {@code @Transactional} runs — and a valid-but-tenant-less token is rejected {@code 403}
 *       (RFC-7807), UNLESS the request matches a {@code native.security.tenant-optional} bootstrap
 *       endpoint (e.g. {@code POST /api/v1/companies}), which proceeds unbound so the controller
 *       can create the new tenant (see {@link TenantOptionalProperties}).
 * </ul>
 *
 * <p>Stateless: no session, no CSRF (the bearer token is the entire credential), no HTTP Basic, no
 * form login — this is an API edge, so a failed authentication yields {@code 401}, never a login
 * redirect.
 */
@Configuration
@Profile("!dev")
public class JwtSecurityConfig {

  private final Duration clockSkew;
  private final TenantOptionalProperties tenantOptionalProperties;

  public JwtSecurityConfig(
      @Value("${native.security.jwt.clock-skew:5s}") Duration clockSkew,
      TenantOptionalProperties tenantOptionalProperties) {
    this.clockSkew = clockSkew;
    this.tenantOptionalProperties = tenantOptionalProperties;
  }

  @Bean
  SecurityFilterChain nativeJwtSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder)
      throws Exception {
    // Compile public-path matchers once; they are used both in the security chain (permitAll) and
    // in TenantBindingFilter (shouldNotFilter), so token-less public requests are fully exempt at
    // both points.
    List<RequestMatcher> publicMatchers = tenantOptionalProperties.publicPathMatchers();

    var authConfig =
        http.authorizeHttpRequests(
            auth -> {
              // Probes are always open (no token, no tenant).
              auth.requestMatchers(
                      "/healthz", "/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                  .permitAll();
              // Public paths (e.g. POST /api/v1/signup) — fully unauthenticated.
              for (RequestMatcher matcher : publicMatchers) {
                auth.requestMatchers(matcher).permitAll();
              }
              // Everything else requires a valid bearer token.
              auth.anyRequest().authenticated();
            });

    return authConfig
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .jwt(jwt -> jwt.decoder(jwtDecoder))
                    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        // Bind TenantContext from the validated token AFTER the bearer token has been authenticated
        // (so the SecurityContext holds the Jwt) but before the request reaches a controller. The
        // tenant-optional matchers let configured bootstrap endpoints (e.g. POST /api/v1/companies)
        // proceed with an authenticated-but-tenant-less token instead of a 403. The public-path
        // matchers cause shouldNotFilter to skip token-less requests entirely.
        .addFilterAfter(
            new TenantBindingFilter(tenantOptionalProperties.toRequestMatchers(), publicMatchers),
            BearerTokenAuthenticationFilter.class)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable())
        .httpBasic(httpBasic -> httpBasic.disable())
        .formLogin(formLogin -> formLogin.disable())
        .exceptionHandling(
            ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .build();
  }

  /**
   * The RS256 {@link JwtDecoder}, built explicitly so its clock skew is a tight, configurable value
   * ({@code native.security.jwt.clock-skew}, default {@code 5s}) rather than Spring Security's
   * generous 60-second default — an expired token should be rejected promptly at the in-service
   * edge, mirroring the gateway's decoder. The signature is validated against Keycloak's JWKS
   * ({@code jwk-set-uri}); the issuer ({@code iss}) is checked against the trusted realm issuer
   * when configured.
   *
   * <p>The JWKS fetch runs on a {@link RestTemplate} carrying EXPLICIT connect/read timeouts from
   * {@link JwksClientProperties} (§4). Spring Security's default ({@code
   * RestTemplateWithNimbusDefaultTimeouts}) already bounds this fetch to {@code 500ms}/{@code
   * 500ms}; passing our own {@code restOperations(...)} (defaulting to the same {@code 500ms})
   * makes that timeout explicit, externalized, and startup-asserted — owned config rather than a
   * transitive framework constant that could shift on a version bump (see {@link
   * JwksClientProperties}).
   */
  @Bean
  JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties, JwksClientProperties jwksProps) {
    OAuth2ResourceServerProperties.Jwt jwt = properties.getJwt();

    SimpleClientHttpRequestFactory jwksRequestFactory = new SimpleClientHttpRequestFactory();
    jwksRequestFactory.setConnectTimeout(jwksProps.getConnectTimeout());
    jwksRequestFactory.setReadTimeout(jwksProps.getReadTimeout());

    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(jwt.getJwkSetUri())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .restOperations(new RestTemplate(jwksRequestFactory))
            .build();

    List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
    validators.add(new JwtTimestampValidator(clockSkew));
    if (jwt.getIssuerUri() != null) {
      validators.add(new JwtIssuerValidator(jwt.getIssuerUri()));
    }
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
    return decoder;
  }
}
