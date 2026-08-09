package id.co.nativeapp.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.gateway.config.CorsProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Unit proof of the gateway's CORS policy (ADR 0051) — no containers. Asserts the two properties a
 * security review cares about: the DEFAULT (empty allow-list) turns CORS entirely off, and a
 * configured allow-list is exact (never {@code *}), credential-less, and scoped to {@code /api/**}.
 */
class GatewayCorsConfigurationTest {

  private final SecurityConfig config = new SecurityConfig();

  @Test
  void emptyAllowListRegistersNoCorsMappingSoTheEdgeBehavesAsBeforeAdr0051() {
    UrlBasedCorsConfigurationSource source =
        (UrlBasedCorsConfigurationSource)
            config.corsConfigurationSource(new CorsProperties(List.of()));

    // No mapping at all → getCorsConfiguration returns null for every request → no CORS header.
    assertThat(source.getCorsConfigurations()).isEmpty();
  }

  @Test
  void aNullAllowListIsTreatedAsEmpty() {
    UrlBasedCorsConfigurationSource source =
        (UrlBasedCorsConfigurationSource) config.corsConfigurationSource(new CorsProperties(null));

    assertThat(source.getCorsConfigurations()).isEmpty();
  }

  @Test
  void aConfiguredAllowListIsExactCredentiallessAndScopedToApiPaths() {
    UrlBasedCorsConfigurationSource source =
        (UrlBasedCorsConfigurationSource)
            config.corsConfigurationSource(new CorsProperties(List.of("https://localhost")));

    Map<String, CorsConfiguration> mappings = source.getCorsConfigurations();
    // CORS applies to /api/** ONLY — never actuator/health or any other edge path.
    assertThat(mappings).containsOnlyKeys("/api/**");

    CorsConfiguration cfg = mappings.get("/api/**");
    assertThat(cfg.getAllowedOrigins()).containsExactly("https://localhost").doesNotContain("*");
    // The allowed origin is honoured; anything else is refused by the CORS check itself.
    assertThat(cfg.checkOrigin("https://localhost")).isEqualTo("https://localhost");
    assertThat(cfg.checkOrigin("https://evil.example")).isNull();
    // Credentials MUST be off — auth is a bearer header, not a cookie.
    assertThat(cfg.getAllowCredentials()).isNotEqualTo(Boolean.TRUE);
    assertThat(cfg.getAllowedMethods())
        .containsExactlyInAnyOrder("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    assertThat(cfg.getAllowedHeaders())
        .contains(
            "Authorization",
            "Content-Type",
            "Accept",
            "X-Company-Id",
            "X-Operator-Session",
            "Idempotency-Key");
    // The download filename must be readable cross-origin (apiDownload reads Content-Disposition).
    assertThat(cfg.getExposedHeaders()).contains("Content-Disposition");
  }

  @Test
  void aWildcardOriginIsRejectedAtConfigTimeSoTheGatewayCannotBootPermissive() {
    assertThatThrownBy(() -> new CorsProperties(List.of("*")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CorsProperties(List.of("https://*.evil.example")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aCleartextNonLoopbackOriginIsRejected() {
    assertThatThrownBy(() -> new CorsProperties(List.of("http://app.example.com")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void httpsAndLoopbackDevOriginsAreAccepted() {
    // https anywhere, and http ONLY for loopback dev (5173/4173) — never other cleartext hosts.
    CorsProperties props =
        new CorsProperties(List.of("https://localhost", "http://localhost:5173"));
    assertThat(props.allowedOrigins())
        .containsExactly("https://localhost", "http://localhost:5173");
  }
}
