package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.security.OperatorTokenProperties;
import id.co.nativeapp.security.OperatorTokenSigningKey;
import java.util.Arrays;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the ADR 0049 P2 operator-session signing key for restaurant-service — the OFFLINE VERIFIER
 * side (mirrors employee-service's {@code OperatorTokenSigningConfig}, the MINTER side): binds the
 * shared {@code libs/security} {@link OperatorTokenProperties} (the externalized HMAC key,
 * env/Vault-sourced) — NOT auto-registered by {@code libs/security} itself, so this service opts in
 * explicitly here — and builds the singleton {@link OperatorTokenSigningKey} that {@link
 * OperatorSessionFilterConfig} registers {@code OperatorSessionFilter} with.
 *
 * <p>{@link OperatorTokenSigningKey#fromBase64Key} validates the key at bean creation, so the
 * application <strong>fails fast at startup</strong> on a missing or too-short key rather than
 * silently accepting weakly-signed (or unsigned-in-practice) operator-session tokens.
 *
 * <p><strong>The committed dev placeholder is refused outside the {@code dev} profile</strong>
 * (mirrors payment-service's {@code PiiEncryptionConfig} L2 guard, and employee-service's identical
 * P2 guard on the minting side). The {@code application.yml} default exists only so the local dev
 * stack and the test suite have a deterministic round-trip key — but it is a well-formed HMAC key
 * sitting in a public repo, and this is the key restaurant-service trusts to attribute a sale's
 * seller (and therefore its commission). A production/UAT boot that forgot to inject {@code
 * NATIVE_OPERATOR_TOKEN_KEY} must fail loudly at startup, never silently verify operator-session
 * tokens against a public key.
 */
@Configuration
@EnableConfigurationProperties(OperatorTokenProperties.class)
public class OperatorTokenSigningConfig {

  /** The application.yml dev/test fallback — a PUBLIC value, never a real secret. */
  static final String DEV_PLACEHOLDER_KEY = "bmF0aXZlLWRldi1vcHRvay1wbGFjZWhvbGRlci1rZXk=";

  /**
   * The process-wide operator-token signing key, keyed from {@code
   * native.operator-token.signing-key} with TTL from {@code native.operator-token.token-ttl}. A
   * single instance is shared (immutable key bytes + ttl).
   */
  @Bean
  public OperatorTokenSigningKey operatorTokenSigningKey(
      OperatorTokenProperties properties, Environment environment) {
    boolean devProfile = Arrays.asList(environment.getActiveProfiles()).contains("dev");
    if (!devProfile && DEV_PLACEHOLDER_KEY.equals(properties.signingKey().strip())) {
      throw new IllegalStateException(
          "native.operator-token.signing-key is still the committed dev placeholder under a"
              + " non-dev profile — inject a real NATIVE_OPERATOR_TOKEN_KEY (Vault/env); refusing"
              + " to verify operator-session tokens (and therefore attribute sales commission)"
              + " against a public key (ADR 0049 P2)");
    }
    return OperatorTokenSigningKey.fromBase64Key(properties.signingKey(), properties.tokenTtl());
  }
}
