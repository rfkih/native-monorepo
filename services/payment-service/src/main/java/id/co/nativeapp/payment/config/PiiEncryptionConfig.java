package id.co.nativeapp.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires credential column-level encryption (rule 6): binds {@link PiiEncryptionProperties} (the
 * externalized AES-256 key under the shared {@code native.pii.*} namespace) and builds the
 * singleton {@link PiiCipher} bean {@link PiiBytesAttributeConverter} delegates to for the {@code
 * server_key_encrypted}/{@code client_key_encrypted} columns.
 *
 * <p>The cipher factory validates the key is a 32-byte value at bean creation, so the application
 * <strong>fails fast at startup</strong> on a missing or malformed key rather than silently storing
 * weak or plaintext credentials.
 */
@Configuration
@EnableConfigurationProperties(PiiEncryptionProperties.class)
public class PiiEncryptionConfig {

  /**
   * The process-wide credential cipher, keyed from the externalized {@code native.pii.key}. A
   * single instance is shared (it is stateless apart from the immutable key + a thread-safe {@link
   * java.security.SecureRandom}).
   */
  @Bean
  public PiiCipher piiCipher(PiiEncryptionProperties properties) {
    return PiiCipher.fromBase64Key(properties.key());
  }
}
