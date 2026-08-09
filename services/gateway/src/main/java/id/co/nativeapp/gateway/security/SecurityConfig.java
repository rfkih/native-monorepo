package id.co.nativeapp.gateway.security;

import id.co.nativeapp.gateway.config.CorsProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The gateway's single synchronous security edge (HR-2).
 *
 * <p>Every routed request must carry a valid RS256 bearer token; the resource-server JWKS decoder
 * validates signature, issuer, and expiry against Keycloak (configured via {@code
 * spring.security.oauth2.resourceserver.jwt.*}). A missing, malformed, expired, or wrong-signature
 * token is rejected here with {@code 401} — it never reaches a downstream service. The two narrow
 * exceptions below are the ONLY unauthenticated business routes, each carrying its own anonymous
 * rate limit instead (see {@link id.co.nativeapp.gateway.config.RoutingConfig}).
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
 * liveness/readiness, so exactly those two named sub-paths are exposed.
 *
 * <p>{@code /api/v1/signup} (public sign-up) and {@code /api/v1/self-order/**} (public self-order
 * QR, Phase 6, ADR 0029) are ALSO {@code permitAll}: both are genuinely pre-login surfaces — a new
 * company has no token yet, and a diner scanning a table's QR code never logs in at all. Every
 * other business route still requires authentication.
 *
 * <p>Stateless: no session, no CSRF (there is no browser session or form to protect — the bearer
 * token is the entire credential), no HTTP Basic, no form login. A failed authentication yields a
 * bare {@code 401} (an {@link HttpStatusEntryPoint}), never a login redirect — this is an API edge.
 */
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http, CorsConfigurationSource corsConfigurationSource)
      throws Exception {
    return http
        // CORS for the bundled Android shell (ADR 0051). With an empty allow-list (the default and
        // every thin-client deploy) the source registers NO mapping, so this is a no-op and the edge
        // behaves exactly as before. When configured, Spring Security's CorsFilter answers the
        // preflight (OPTIONS) before authentication and adds the headers to real responses — which
        // still pass through the unchanged JWT validation below.
        .cors(cors -> cors.configurationSource(corsConfigurationSource))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/healthz",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness",
                        "/actuator/info",
                        "/actuator/prometheus",
                        // Public sign-up: unauthenticated at the edge — no JWT required.
                        // The request is forwarded to org-service which owns the actual logic.
                        "/api/v1/signup",
                        // Public self-order QR (Phase 6, ADR 0029): unauthenticated at the edge —
                        // a diner scans a table's QR code and never logs in. The request is
                        // forwarded to restaurant-service, which validates the QR-issued
                        // X-Self-Order-Token itself; RoutingConfig's selfOrderRoute carries the
                        // dedicated anonymous rate limit + tenant-header strip instead of the
                        // authenticated-route filter chain.
                        "/api/v1/self-order/**",
                        // Public PSP settlement webhook (ADR 0045): Midtrans's notification
                        // servers carry no Keycloak token. The request is forwarded to
                        // payment-service, which authenticates it itself (provisional tenant bind
                        // from the callback URL + constant-time merchant-key signature check);
                        // RoutingConfig's pspWebhookRoute carries the dedicated anonymous rate
                        // limit + tenant-header strip instead of the authenticated filter chain.
                        "/api/v1/psp-webhooks/**",
                        // Public menu-image serving (ADR 0048): GET-only proxy onto the object
                        // store's restaurant/ prefix — an <img> tag cannot carry a bearer token,
                        // and the same images already reach anonymous self-order diners. The
                        // protection model is unguessable 256-bit content-hash keys + MinIO's
                        // anonymous policy covering ONLY this prefix; RoutingConfig's mediaRoute
                        // carries the anonymous rate limit + tenant-header strip. Deliberately
                        // NARROW: employee/ (receipts) and payment/ (QRIS) prefixes stay behind
                        // authenticated service endpoints and are NOT opened here.
                        "/api/media/restaurant/**")
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

  /**
   * CORS policy for the bundled Android shell (ADR 0051), scoped to {@code /api/**} only.
   *
   * <p>An EMPTY allow-list (the default, and every same-origin thin-client deploy — see {@link
   * CorsProperties}) registers no mapping, so {@code getCorsConfiguration} returns {@code null} for
   * every request and no CORS header is ever emitted: identical to the pre-0051 edge. A configured
   * bundled deploy gets an EXACT-origin policy (never {@code *}), the exact method + request-header
   * set the console sends (see {@code frontend/console/src/lib/api.ts}), {@code Content-Disposition}
   * exposed so a cross-origin file download can read its filename, and — critically —
   * {@code allowCredentials=false}: auth rides a bearer header, not a cookie, so credentials must
   * stay off (exact-origin + no-credentials is the only combination that is not a CORS foot-gun).
   */
  @Bean
  CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    if (props.allowedOrigins().isEmpty()) {
      return source; // no mapping → no CORS anywhere (same as before ADR 0051)
    }
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(props.allowedOrigins()); // exact origins, never "*"
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(
        List.of(
            "Authorization",
            "Content-Type",
            "Accept",
            "X-Company-Id",
            "X-Operator-Session",
            "Idempotency-Key"));
    config.setExposedHeaders(List.of("Content-Disposition"));
    config.setAllowCredentials(false);
    config.setMaxAge(Duration.ofHours(1));
    source.registerCorsConfiguration("/api/**", config);
    return source;
  }
}
