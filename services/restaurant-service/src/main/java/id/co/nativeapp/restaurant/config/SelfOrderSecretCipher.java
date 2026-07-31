package id.co.nativeapp.restaurant.config;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM authenticated encryption for the {@code self_order_access.secret_encrypted} column
 * (Phase 6, ADR 0029) — ported VERBATIM from the fleet's {@code PiiCipher} mechanism (employee-
 * service / loyalty-service {@code config.PiiCipher}), keyed from its OWN dedicated {@code
 * NATIVE_SELFORDER_KEY} (see {@link SelfOrderProperties}) rather than the shared {@code
 * NATIVE_PII_KEY}: a per-outlet QR-signing secret is not fleet-shared PII, and a separate key means
 * a compromised {@code NATIVE_PII_KEY} cannot also unlock every outlet's self-order secret.
 *
 * <p><strong>Random IV per value.</strong> GCM is only secure if the (key, IV) pair is never
 * reused; a fixed IV with a reused key is catastrophic. So {@link #encryptToString(String)} draws a
 * fresh 12-byte IV from a {@link SecureRandom} for <em>every</em> value and prepends it to the
 * ciphertext. The stored form is {@code base64( IV(12) || ciphertext+GCMtag(16) )}; {@link
 * #decryptFromString(String)} splits the IV back off.
 *
 * <p>GCM's 128-bit authentication tag means a tampered or truncated ciphertext fails to decrypt
 * (rather than returning garbage), so a corrupted column is detected rather than silently mis-read.
 *
 * <p><strong>Never logs.</strong> No method here logs a plaintext, a key, or a ciphertext; failures
 * throw a {@link SelfOrderSecretEncryptionException} whose message carries no secret material.
 */
public final class SelfOrderSecretCipher {

  /** AES-GCM with no padding (GCM is a stream mode; padding is neither needed nor allowed). */
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";

  private static final String ALGORITHM = "AES";

  /** AES-256 requires a 32-byte key. */
  private static final int KEY_BYTES = 32;

  /** The NIST-recommended GCM nonce length: 96 bits (12 bytes). */
  private static final int IV_BYTES = 12;

  /** GCM authentication-tag length in bits (the maximum / standard 128-bit tag). */
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom secureRandom = new SecureRandom();

  private SelfOrderSecretCipher(SecretKeySpec key) {
    this.key = key;
  }

  /**
   * Builds a cipher from the configured base64 key, failing fast (at bean creation / startup) if
   * the key is not a valid 32-byte AES-256 key. A misconfigured key can therefore never silently
   * degrade to weak or plaintext storage.
   *
   * @param base64Key the base64-encoded 32-byte key from {@link SelfOrderProperties}
   * @throws IllegalStateException if the key is not valid base64 or not exactly 32 bytes
   */
  public static SelfOrderSecretCipher fromBase64Key(String base64Key) {
    Objects.requireNonNull(base64Key, "base64Key");
    byte[] keyBytes;
    try {
      keyBytes = Base64.getDecoder().decode(base64Key.strip());
    } catch (IllegalArgumentException e) {
      // Do NOT include the key value in the message (rule 6).
      throw new IllegalStateException("native.self-order.secret-key is not valid base64", e);
    }
    if (keyBytes.length != KEY_BYTES) {
      throw new IllegalStateException(
          "native.self-order.secret-key must decode to "
              + KEY_BYTES
              + " bytes (AES-256); got "
              + keyBytes.length
              + " bytes");
    }
    SecretKeySpec spec = new SecretKeySpec(keyBytes, ALGORITHM);
    Arrays.fill(keyBytes, (byte) 0); // scrub the transient copy
    return new SelfOrderSecretCipher(spec);
  }

  /**
   * Encrypts a UTF-8 plaintext, returning {@code base64( IV || ciphertext+tag )}. A fresh random IV
   * is drawn for this value (never reused).
   *
   * @param plaintext the value to encrypt; {@code null} maps to {@code null} (a null column stays
   *     null)
   */
  public String encryptToString(String plaintext) {
    if (plaintext == null) {
      return null;
    }
    try {
      byte[] iv = new byte[IV_BYTES];
      secureRandom.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] combined = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, combined, 0, iv.length);
      System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
      return Base64.getEncoder().encodeToString(combined);
    } catch (Exception e) {
      // The message carries no plaintext/key (rule 6) — only the operation that failed.
      throw new SelfOrderSecretEncryptionException("Failed to encrypt a self-order secret", e);
    }
  }

  /**
   * Decrypts a value produced by {@link #encryptToString(String)}, splitting the prepended IV back
   * off. A tampered ciphertext fails the GCM tag check and throws (never returns garbage).
   *
   * @param stored the stored {@code base64( IV || ciphertext+tag )}; {@code null} maps to {@code
   *     null}
   */
  public String decryptFromString(String stored) {
    if (stored == null) {
      return null;
    }
    try {
      byte[] combined = Base64.getDecoder().decode(stored);
      if (combined.length <= IV_BYTES) {
        throw new IllegalArgumentException("ciphertext too short to contain an IV");
      }
      byte[] iv = Arrays.copyOfRange(combined, 0, IV_BYTES);
      byte[] ciphertext = Arrays.copyOfRange(combined, IV_BYTES, combined.length);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] plaintext = cipher.doFinal(ciphertext);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new SelfOrderSecretEncryptionException("Failed to decrypt a self-order secret", e);
    }
  }

  /** Thrown on any encrypt/decrypt failure; the message never carries plaintext or key material. */
  public static final class SelfOrderSecretEncryptionException extends RuntimeException {
    SelfOrderSecretEncryptionException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
