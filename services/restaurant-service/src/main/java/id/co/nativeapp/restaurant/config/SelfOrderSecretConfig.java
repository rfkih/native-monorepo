package id.co.nativeapp.restaurant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Phase 6 (ADR 0029) self-order secret encryption: builds the singleton {@link
 * SelfOrderSecretCipher} bean {@link SelfOrderSecretConverter} delegates to, keyed from the
 * externalized {@code native.self-order.secret-key} ({@code NATIVE_SELFORDER_KEY}).
 *
 * <p>{@link SelfOrderProperties} is bound once, fleet-wide, by {@link EventsConfig} (which also
 * needs it for the entitlement-check cache TTL / module key); this class only builds the cipher
 * bean.
 */
@Configuration
public class SelfOrderSecretConfig {

  /**
   * The process-wide self-order secret cipher, keyed from {@code native.self-order.secret-key}. A
   * single instance is shared (it is stateless apart from the immutable key + a thread-safe {@link
   * java.security.SecureRandom}).
   */
  @Bean
  public SelfOrderSecretCipher selfOrderSecretCipher(SelfOrderProperties properties) {
    return SelfOrderSecretCipher.fromBase64Key(properties.secretKey());
  }
}
