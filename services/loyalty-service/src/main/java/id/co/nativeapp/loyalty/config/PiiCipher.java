package id.co.nativeapp.loyalty.config;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM authenticated encryption for PII columns (rule 6). Ported VERBATIM from
 * employee-service's {@code config.PiiCipher} (the mechanism this service mirrors exactly per the
 * Phase-4 task): one instance holds the 256-bit key sourced from {@link PiiEncryptionProperties};
 * {@link PiiBytesAttributeConverter} delegates to it so the member {@code phone_encrypted} /
 * {@code display_name_encrypted} columns are ciphertext at rest and decrypt back transparently on
 * read.
 *
 * <p><strong>Random IV per value.</strong> GCM is only secure if the (key, IV) pair is never
 * reused; a fixed IV with a reused key is catastrophic. So {@link #encryptToString(String)} draws a
 * fresh 12-byte IV from a {@link SecureRandom} for <em>every</em> value and prepends it to the
 * ciphertext. The stored form is {@code base64( IV(12) || ciphertext+GCMtag(16) )}; {@link
 * #decryptFromString(String)} splits the IV back off. Two encryptions of the same plaintext
 * therefore produce different ciphertext, so the column never leaks equality of underlying values
 * (that is exactly why {@code phone_hash}, a SEPARATE deterministic HMAC — see {@link
 * PhoneHasher} — exists for exact-match lookup: the encrypted column itself can never be searched).
 *
 * <p>GCM's 128-bit authentication tag means a tampered or truncated ciphertext fails to decrypt
 * (rather than returning garbage), so a corrupted column is detected rather than silently mis-read.
 *
 * <p><strong>Never logs.</strong> No method here logs a plaintext, a key, or a ciphertext; failures
 * throw a {@link PiiEncryptionException} whose message carries no PII (rule 6).
 *
 * <p><strong>Column-type deviation from employee-service (documented).</strong> employee-service
 * stores the base64 output of {@link #encryptToString(String)} directly in a {@code TEXT} column.
 * The Phase-4 loyalty schema instead declares {@code phone_encrypted}/{@code
 * display_name_encrypted} as {@code BYTEA} (the task's explicit column type). {@link
 * PiiBytesAttributeConverter} bridges this: it base64-DECODES {@link #encryptToString(String)}'s
 * output into raw bytes before the {@code BYTEA} write, and base64-ENCODES the stored bytes back
 * before calling {@link #decryptFromString(String)} on read. The cryptographic mechanism itself —
 * AES-256-GCM, random IV per value, key sourcing, error handling — is otherwise byte-for-byte
 * identical to employee-service's.
 */
public final class PiiCipher {

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

  private PiiCipher(SecretKeySpec key) {
    this.key = key;
  }

  /**
   * Builds a cipher from the configured base64 key, failing fast (at bean creation / startup) if
   * the key is not a valid 32-byte AES-256 key. A misconfigured key can therefore never silently
   * degrade to weak or plaintext storage.
   *
   * @param base64Key the base64-encoded 32-byte key from {@link PiiEncryptionProperties}
   * @throws IllegalStateException if the key is not valid base64 or not exactly 32 bytes
   */
  public static PiiCipher fromBase64Key(String base64Key) {
    Objects.requireNonNull(base64Key, "base64Key");
    byte[] keyBytes;
    try {
      keyBytes = Base64.getDecoder().decode(base64Key.strip());
    } catch (IllegalArgumentException e) {
      // Do NOT include the key value in the message (rule 6).
      throw new IllegalStateException("native.pii.key is not valid base64", e);
    }
    if (keyBytes.length != KEY_BYTES) {
      throw new IllegalStateException(
          "native.pii.key must decode to "
              + KEY_BYTES
              + " bytes (AES-256); got "
              + keyBytes.length
              + " bytes");
    }
    SecretKeySpec spec = new SecretKeySpec(keyBytes, ALGORITHM);
    Arrays.fill(keyBytes, (byte) 0); // scrub the transient copy
    return new PiiCipher(spec);
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
      throw new PiiEncryptionException("Failed to encrypt a PII value", e);
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
      throw new PiiEncryptionException("Failed to decrypt a PII value", e);
    }
  }
}
