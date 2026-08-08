package id.co.nativeapp.employee.config;

import java.time.Duration;
import java.util.Base64;
import java.util.Objects;

/**
 * Holds the decoded {@code native.operator-token.signing-key} bytes + the configured {@link
 * #tokenTtl()} — the small bean {@code OperatorSessionWriter} (operator feature) mints tokens with,
 * via {@code libs/security} {@code OperatorTokenCodec} (ADR 0049 P1).
 *
 * <p>Validation mirrors {@code PiiCipher.fromBase64Key} / {@code
 * SelfOrderSecretCipher.fromBase64Key} (fail fast at bean creation): valid base64, decoding to AT
 * LEAST {@link #MIN_KEY_BYTES} bytes — "at least", not "exactly", because unlike an AES key an
 * HMAC-SHA256 key has no fixed required length; 32 bytes is the floor for adequate entropy. A
 * misconfigured key can therefore never silently mint or accept a weak-signed token.
 */
public final class OperatorTokenSigningKey {

  /** The minimum acceptable HMAC signing-key length, in bytes (256 bits of entropy). */
  private static final int MIN_KEY_BYTES = 32;

  private final byte[] secretBytes;
  private final Duration tokenTtl;

  private OperatorTokenSigningKey(byte[] secretBytes, Duration tokenTtl) {
    this.secretBytes = secretBytes;
    this.tokenTtl = tokenTtl;
  }

  /**
   * Builds a signing key from the configured base64 value, failing fast (at bean creation /
   * startup) if it is not valid base64 or decodes to fewer than {@link #MIN_KEY_BYTES} bytes.
   *
   * @param base64Key the base64-encoded signing key from {@code OperatorTokenProperties}
   * @param tokenTtl how long a minted token stays valid before {@code exp}
   * @throws IllegalStateException if the key is not valid base64 or too short
   */
  public static OperatorTokenSigningKey fromBase64Key(String base64Key, Duration tokenTtl) {
    Objects.requireNonNull(base64Key, "base64Key");
    Objects.requireNonNull(tokenTtl, "tokenTtl");
    byte[] keyBytes;
    try {
      keyBytes = Base64.getDecoder().decode(base64Key.strip());
    } catch (IllegalArgumentException e) {
      // Do NOT include the key value in the message (rule 6).
      throw new IllegalStateException("native.operator-token.signing-key is not valid base64", e);
    }
    if (keyBytes.length < MIN_KEY_BYTES) {
      throw new IllegalStateException(
          "native.operator-token.signing-key must decode to at least "
              + MIN_KEY_BYTES
              + " bytes; got "
              + keyBytes.length
              + " bytes");
    }
    return new OperatorTokenSigningKey(keyBytes, tokenTtl);
  }

  /**
   * The raw HMAC signing-key bytes, for {@code OperatorTokenCodec.encode}. Never logged (rule 6);
   * returns a defensive copy so a caller cannot mutate the shared key in place.
   */
  public byte[] secretBytes() {
    return secretBytes.clone();
  }

  /** How long a freshly minted operator session stays valid before {@code exp}. */
  public Duration tokenTtl() {
    return tokenTtl;
  }
}
