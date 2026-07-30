package id.co.nativeapp.loyalty.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires PII column-level encryption + hashing (rule 6): binds {@link PiiEncryptionProperties} (the
 * externalized AES-256 encryption key AND the separate HMAC-SHA256 hashing key, both env/Vault
 * -sourced under the shared {@code native.pii.*} namespace) and builds the singleton {@link
 * PiiCipher} / {@link PhoneHasher} beans {@link PiiBytesAttributeConverter} and the member
 * enrollment path delegate to.
 *
 * <p><strong>Two keys, two purposes (never conflated).</strong> {@link PiiCipher} uses a
 * randomized-IV AES-256-GCM key so the SAME plaintext never produces the same ciphertext twice
 * (defeats equality-leak / dictionary attacks on the encrypted column) — which is exactly why it
 * CANNOT be used for exact-match lookup. {@link PhoneHasher} uses a SEPARATE deterministic
 * HMAC-SHA256 key so the SAME phone always produces the SAME hash — enabling an indexed {@code
 * UNIQUE(company_id, phone_hash)} exact-match lookup without ever storing or searching the
 * plaintext. Reusing one key for both purposes would let the hash key attack the encryption
 * scheme's security property, so the two are configured, sourced, and validated independently.
 *
 * <p>Both cipher/hasher factories validate their key is a 32-byte value at bean creation, so the
 * application <strong>fails fast at startup</strong> on a missing or malformed key rather than
 * silently storing weak or plaintext PII.
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

  /**
   * The process-wide phone hasher, keyed from the externalized {@code native.pii.hmac-key} — a
   * SEPARATE key from {@link #piiCipher}, see class javadoc.
   */
  @Bean
  public PhoneHasher phoneHasher(PiiEncryptionProperties properties) {
    return PhoneHasher.fromBase64Key(properties.hmacKey());
  }
}
