package id.co.nativeapp.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * The gateway's single synchronous security edge (HR-2).
 *
 * <p>Every routed request must carry a valid RS256 bearer token; the resource-server JWKS decoder
 * validates signature, issuer, and expiry against Keycloak (configured via {@code
 * spring.security.oauth2.resourceserver.jwt.*}). A missing, malformed, expired, or wrong-signature
 * token is rejected here with {@code 401} — it never reaches a downstream service.
 *
 * <p>Probes are exempt, but <strong>narrowly</strong>: {@code /healthz} (the LB liveness probe) and
 * only the specific actuator endpoints a probe/scrape needs — the two NAMED probe groups {@code
 * /actuator/health/liveness} and {@code /actuator/health/readiness}, plus {@code /actuator/info}
 * and {@code /actuator/prometheus} — are {@code permitAll} so they answer without a token. The
 * aggregate {@code /actuator/health} (and its wildcard {@code /actuator/health/**}) is deliberately
 * NOT opened: the full aggregate composes every indicator (db, kafka, ...) and, were {@code
 * show-details} ever widened, could surface internals unauthenticated. The whole {@code
 * /actuator/**} tree is likewise NOT opened: widening {@code
 * management.endpoints.web.exposure.include} (e.g. to {@code env}/{@code heapdump}/{@code
 * threaddump}) must never silently expose those unauthenticated at the edge. The LB only needs
 * liveness/readiness, so exactly those two named sub-paths are exposed. Everything else requires
 * authentication.
 *
 * <p>Stateless: no session, no CSRF (there is no browser session or form to protect — the bearer
 * token is the entire credential), no HTTP Basic, no form login. A failed authentication yields a
 * bare {@code 401} (an {@link HttpStatusEntryPoint}), never a login redirect — this is an API edge.
 */
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/healthz",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness",
                        "/actuator/info",
                        "/actuator/prometheus")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(new TenantJwtAuthoritiesConverter()))
                    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable())
        .httpBasic(httpBasic -> httpBasic.disable())
        .formLogin(formLogin -> formLogin.disable())
        .exceptionHandling(
            ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .build();
  }
}
