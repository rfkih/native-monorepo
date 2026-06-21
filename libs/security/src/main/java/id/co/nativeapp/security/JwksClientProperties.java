package id.co.nativeapp.security;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Explicit, owned connect + read timeouts for the JWKS HTTP fetch the resource-server {@link
 * org.springframework.security.oauth2.jwt.JwtDecoder} performs against Keycloak
 * (ENGINEERING-STANDARDS §4 — every outbound client sets explicit timeouts, owned in our config
 * rather than left to a transitive library's internal default).
 *
 * <p><strong>Why surface this.</strong> On the pinned stack (Spring Security 7.1.0) {@code
 * NimbusJwtDecoder.withJwkSetUri(...)} already defaults its JWKS fetch to a bounded {@code
 * RestTemplateWithNimbusDefaultTimeouts} — {@code 500ms} connect and {@code 500ms} read (from
 * Nimbus' {@code RemoteJWKSet.DEFAULT_HTTP_*}). So this is NOT fixing an infinite-hang; it makes
 * that timeout an <em>explicit, externalized, startup-asserted</em> value instead of an internal
 * framework constant: an environment with a genuinely slow Keycloak can widen it via {@code
 * native.security.jwks.*} with no code change (§7), it is asserted positive at boot ({@link
 * OutboundClientTimeoutCheck}), and it can no longer silently shift if a future library bump
 * changes the framework default. {@link JwtSecurityConfig} feeds it into the decoder's {@code
 * restOperations}.
 *
 * <p>Defaults MATCH the framework's {@code 500ms}/{@code 500ms} — making the timeout explicit must
 * never be a back-door loosening. The values are {@code @NotNull} (a service that blanks one fails
 * to start — fail fast, §7) and additionally asserted <em>positive</em> by {@link
 * OutboundClientTimeoutCheck} (a {@code 0s} = infinite misconfiguration is rejected at boot).
 */
@Validated
@ConfigurationProperties("native.security.jwks")
public class JwksClientProperties {

  @NotNull private Duration connectTimeout = Duration.ofMillis(500);

  @NotNull private Duration readTimeout = Duration.ofMillis(500);

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public Duration getReadTimeout() {
    return readTimeout;
  }

  public void setReadTimeout(Duration readTimeout) {
    this.readTimeout = readTimeout;
  }
}
