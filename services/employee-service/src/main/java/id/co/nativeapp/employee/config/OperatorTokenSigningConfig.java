package id.co.nativeapp.employee.config;

import id.co.nativeapp.security.OperatorTokenProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the ADR 0049 P1 operator-session signing key: binds the shared {@code libs/security} {@link
 * OperatorTokenProperties} (the externalized HMAC key, env/Vault-sourced) — NOT auto-registered by
 * {@code libs/security} itself, so employee-service opts in explicitly here — and builds the
 * singleton {@link OperatorTokenSigningKey} the operator feature's session writer mints tokens
 * with.
 *
 * <p>{@link OperatorTokenSigningKey#fromBase64Key} validates the key at bean creation, so the
 * application <strong>fails fast at startup</strong> on a missing or too-short key rather than
 * silently minting weakly-signed operator tokens.
 */
@Configuration
@EnableConfigurationProperties(OperatorTokenProperties.class)
public class OperatorTokenSigningConfig {

  /**
   * The process-wide operator-token signing key, keyed from {@code
   * native.operator-token.signing-key} with TTL from {@code native.operator-token.token-ttl}. A
   * single instance is shared (immutable key bytes + ttl).
   */
  @Bean
  public OperatorTokenSigningKey operatorTokenSigningKey(OperatorTokenProperties properties) {
    return OperatorTokenSigningKey.fromBase64Key(properties.signingKey(), properties.tokenTtl());
  }
}
