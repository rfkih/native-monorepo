package id.co.nativeapp.loyalty.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Deterministic HMAC-SHA256 hashing for the member {@code phone_hash} exact-match lookup column
 * (rule 6 — never store or search plaintext PII). Unlike {@link PiiCipher} (randomized-IV AES-GCM,
 * so encrypting the same value twice yields different ciphertext), this hasher MUST be
 * deterministic — the same normalized phone number always produces the same 64-character lowercase
 * hex digest — so {@code UNIQUE(company_id, phone_hash)} and an exact-match {@code WHERE phone_hash
 * = ?} query both work without ever touching the encrypted column.
 *
 * <p>Keyed (HMAC, not a bare hash) specifically so the digest cannot be brute-forced/rainbow-tabled
 * offline from a leaked database dump the way an unkeyed {@code SHA-256(phone)} could — an attacker
 * without the key cannot enumerate candidate phone numbers and match hashes.
 *
 * <p>Sourced from a SEPARATE key than {@link PiiCipher} (see {@link PiiEncryptionConfig} class
 * javadoc for why the two keys are never conflated), mirroring the same fail-fast startup
 * validation employee-service's {@link PiiCipher} established: a malformed/missing key aborts boot
 * rather than silently degrading to an unkeyed or absent hash.
 */
public final class PhoneHasher {

  private static final String ALGORITHM = "HmacSHA256";

  /** The key length HMAC-SHA256 is conventionally keyed with (32 bytes = 256 bits). */
  private static final int KEY_BYTES = 32;

  private final SecretKeySpec key;

  private PhoneHasher(SecretKeySpec key) {
    this.key = key;
  }

  /**
   * Builds a hasher from the configured base64 key, failing fast (at bean creation / startup) if
   * the key is not a valid 32-byte value.
   *
   * @param base64Key the base64-encoded 32-byte key from {@link PiiEncryptionProperties#hmacKey()}
   * @throws IllegalStateException if the key is not valid base64 or not exactly 32 bytes
   */
  public static PhoneHasher fromBase64Key(String base64Key) {
    Objects.requireNonNull(base64Key, "base64Key");
    byte[] keyBytes;
    try {
      keyBytes = Base64.getDecoder().decode(base64Key.strip());
    } catch (IllegalArgumentException e) {
      // Do NOT include the key value in the message (rule 6).
      throw new IllegalStateException("native.pii.hmac-key is not valid base64", e);
    }
    if (keyBytes.length != KEY_BYTES) {
      throw new IllegalStateException(
          "native.pii.hmac-key must decode to "
              + KEY_BYTES
              + " bytes; got "
              + keyBytes.length
              + " bytes");
    }
    return new PhoneHasher(new SecretKeySpec(keyBytes, ALGORITHM));
  }

  /**
   * Computes the deterministic HMAC-SHA256 hex digest of an ALREADY-NORMALIZED phone number (see
   * {@code member.domain.PhoneNormalizer}) — the caller must normalize first so that two spellings
   * of the same number (e.g. with/without dashes) hash identically.
   *
   * @param normalizedPhone the normalized phone number; must be non-blank
   * @return a 64-character lowercase hex digest
   */
  public String hash(String normalizedPhone) {
    Objects.requireNonNull(normalizedPhone, "normalizedPhone");
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      byte[] digest = mac.doFinal(normalizedPhone.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      // The message carries no PII/key (rule 6) — only the operation that failed.
      throw new PiiEncryptionException("Failed to hash a phone number", e);
    }
  }
}
