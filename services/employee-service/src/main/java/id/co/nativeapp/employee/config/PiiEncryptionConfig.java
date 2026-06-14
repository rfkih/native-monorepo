package id.co.nativeapp.employee.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires PII column-level encryption (rule 6): binds {@link PiiEncryptionProperties} (the
 * externalized AES-256 key, env/Vault-sourced) and builds the singleton {@link PiiCipher} the
 * {@link PiiAttributeConverter} delegates to.
 *
 * <p>The {@link PiiCipher#fromBase64Key(String)} factory validates the key is a 32-byte AES-256 key
 * at bean creation, so the application <strong>fails fast at startup</strong> on a missing or
 * malformed key rather than silently storing weak or plaintext PII.
 */
@Configuration
@EnableConfigurationProperties(PiiEncryptionProperties.class)
public class PiiEncryptionConfig {

  /**
   * The process-wide PII cipher, keyed from the externalized {@code native.pii.key}. A single
   * instance is shared (it is stateless apart from the immutable key + a thread-safe {@link
   * java.security.SecureRandom}).
   */
  @Bean
  public PiiCipher piiCipher(PiiEncryptionProperties properties) {
    return PiiCipher.fromBase64Key(properties.key());
  }
}
