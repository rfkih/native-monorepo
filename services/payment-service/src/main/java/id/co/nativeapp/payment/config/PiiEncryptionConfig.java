package id.co.nativeapp.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires credential column-level encryption (rule 6): binds {@link PiiEncryptionProperties} (the
 * externalized AES-256 key under the shared {@code native.pii.*} namespace) and builds the
 * singleton {@link PiiCipher} bean {@link PiiBytesAttributeConverter} delegates to for the {@code
 * server_key_encrypted}/{@code client_key_encrypted} columns.
 *
 * <p>The cipher factory validates the key is a 32-byte value at bean creation, so the application
 * <strong>fails fast at startup</strong> on a missing or malformed key rather than silently storing
 * weak or plaintext credentials.
 *
 * <p><strong>The committed dev placeholder is refused outside the {@code dev} profile</strong> (ADR
 * 0045 security review W2). The {@code application.yml} default exists only so the local dev stack
 * and the test suite have a deterministic round-trip key — but it is a well-formed AES-256 key
 * sitting in a public repo, and this service encrypts the merchants' own Midtrans SERVER KEYS with
 * it. A production/UAT boot that forgot to inject {@code NATIVE_PII_KEY} must fail loudly at
 * startup, never silently encrypt live PSP credentials under a public key.
 */
@Configuration
@EnableConfigurationProperties(PiiEncryptionProperties.class)
public class PiiEncryptionConfig {

  /** The application.yml dev/test fallback — a PUBLIC value, never a real secret. */
  static final String DEV_PLACEHOLDER_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

  /**
   * The process-wide credential cipher, keyed from the externalized {@code native.pii.key}. A
   * single instance is shared (it is stateless apart from the immutable key + a thread-safe {@link
   * java.security.SecureRandom}).
   */
  @Bean
  public PiiCipher piiCipher(PiiEncryptionProperties properties, Environment environment) {
    boolean devProfile = java.util.Arrays.asList(environment.getActiveProfiles()).contains("dev");
    if (!devProfile && DEV_PLACEHOLDER_KEY.equals(properties.key().strip())) {
      throw new IllegalStateException(
          "native.pii.key is still the committed dev placeholder under a non-dev profile —"
              + " inject a real NATIVE_PII_KEY (Vault/env); refusing to encrypt merchant PSP"
              + " credentials with a public key (ADR 0045 security review W2)");
    }
    return PiiCipher.fromBase64Key(properties.key());
  }
}
