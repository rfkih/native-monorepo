package id.co.nativeapp.org.user.config;

import id.co.nativeapp.org.user.service.KeycloakAdminClient;
import id.co.nativeapp.security.OutboundClientTimeoutCheck;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Keycloak Admin REST client and its associated timeout self-check for the {@code user}
 * feature.
 *
 * <p>{@link KeycloakAdminProperties} are registered here via {@link EnableConfigurationProperties}
 * so the feature declares its own externalized config without polluting the service root. The
 * startup self-check (mirroring {@link OutboundClientTimeoutCheck} from {@code libs/security})
 * fails fast at boot if either timeout is null, zero, or negative — an outbound client must never
 * run with an infinite/zero timeout (ENGINEERING-STANDARDS §4).
 */
@Configuration
@EnableConfigurationProperties(KeycloakAdminProperties.class)
public class KeycloakAdminConfig {

  @Bean
  KeycloakAdminClient keycloakAdminClient(KeycloakAdminProperties props) {
    requirePositive("native.keycloak-admin.connect-timeout", props.getConnectTimeout());
    requirePositive("native.keycloak-admin.read-timeout", props.getReadTimeout());
    return new KeycloakAdminClient(props);
  }

  private static void requirePositive(String name, java.time.Duration value) {
    // Mirrors OutboundClientTimeoutCheck.requirePositive (libs/security) — but called here so the
    // Keycloak Admin timeouts are asserted at bean creation (context refresh), keeping the
    // "no infinite-timeout client" rule consistent with the JWKS decoder timeout check.
    OutboundClientTimeoutCheck.requirePositive(name, value);
  }
}
