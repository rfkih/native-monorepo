package id.co.nativeapp.gateway.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Cross-origin policy for the bundled Android shell (ADR 0051).
 *
 * <p>The thin client (ADR 0043) renders the console SAME-ORIGIN with the API, so it needs no CORS —
 * {@code allowedOrigins} therefore DEFAULTS EMPTY, which turns CORS off entirely (byte-identical to
 * the pre-0051 edge). The bundled build is served from a local origin ({@code https://localhost}) and
 * calls the API cross-origin, so THAT deploy sets {@code NATIVE_GATEWAY_CORS_ALLOWED_ORIGINS} to its
 * exact origin(s).
 *
 * <p>It is an <strong>exact</strong> allow-list, never a wildcard: the gateway is the security edge,
 * and {@code *} would let any web origin drive the authenticated API from a victim's browser.
 * Credentials are off (auth is a bearer header, not a cookie — see {@code SecurityConfig}).
 */
@Validated
@ConfigurationProperties("native.gateway.cors")
public record CorsProperties(List<String> allowedOrigins) {
  public CorsProperties {
    allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    // Defence-in-depth (security review, ADR 0051 P2): the entire posture rests on "exact origins,
    // never a wildcard" — so make a misconfiguration UNBOOTABLE rather than merely discouraged. A
    // `*` (with credentials off it is not the classic foot-gun, but it is still far broader than any
    // bundled shell needs) or a cleartext non-loopback origin fails the app fast at startup, the same
    // way a missing route target does (ENGINEERING-STANDARDS §7).
    for (String origin : allowedOrigins) {
      if (origin == null || origin.isBlank() || origin.contains("*")) {
        throw new IllegalArgumentException(
            "native.gateway.cors.allowed-origins must be exact origins, never a wildcard: " + origin);
      }
      if (!origin.startsWith("https://")
          && !origin.equals("http://localhost")
          && !origin.startsWith("http://localhost:")) {
        throw new IllegalArgumentException(
            "native.gateway.cors.allowed-origins must be https:// (http://localhost[:port] allowed"
                + " for dev only): "
                + origin);
      }
    }
  }
}
