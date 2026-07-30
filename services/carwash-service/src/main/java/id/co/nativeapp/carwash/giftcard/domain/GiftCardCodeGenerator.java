package id.co.nativeapp.carwash.giftcard.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Derives a gift card's human-facing {@code code} DETERMINISTICALLY from its UUID {@code id} —
 * ported VERBATIM from {@code loyalty-service}'s {@code
 * id.co.nativeapp.loyalty.giftcard.domain.GiftCardCodeGenerator} (the exact derivation, so the till
 * that mints the card and loyalty-service's own read of the same id agree on the printed code
 * without any cross-service call). See that class's javadoc for the full scheme rationale
 * (HMAC-SHA256 keyed derivation, Base32 of the first 10 digest bytes, no padding — security review
 * W-4).
 *
 * <p>This vertical mints the gift card's UUID {@code id} at the till (ADR 0027 decision 5) — never
 * loyalty-service — so deriving the code HERE (not waiting for {@code GiftCardStateChanged} to
 * replicate back) lets the receipt/till print the code immediately.
 *
 * <p><strong>Keyed derivation (security review W-4) — fleet-wide key.</strong> The key comes from
 * this service's OWN {@code native.giftcard.code-key} ({@code NATIVE_GIFTCARD_CODE_KEY}), which
 * MUST be provisioned to the IDENTICAL value as loyalty-service's (and the other verticals') copy —
 * see {@code config.GiftCardProperties} javadoc.
 */
public final class GiftCardCodeGenerator {

  private static final String ALGORITHM = "HmacSHA256";

  /** The key length HMAC-SHA256 is conventionally keyed with (32 bytes = 256 bits). */
  private static final int KEY_BYTES = 32;

  /** RFC 4648 Base32 alphabet (unambiguous, no padding character in the derived code). */
  private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

  /** Only the first N bytes of the 32-byte HMAC-SHA256 digest are encoded (see class javadoc). */
  private static final int ENCODED_BYTE_COUNT = 10;

  private final SecretKeySpec key;

  private GiftCardCodeGenerator(SecretKeySpec key) {
    this.key = key;
  }

  /**
   * Builds a generator from the configured base64 key, failing fast (at bean creation / startup) if
   * the key is not a valid 32-byte value.
   *
   * @param base64Key the base64-encoded 32-byte key from {@code NATIVE_GIFTCARD_CODE_KEY} (via
   *     {@code config.GiftCardProperties})
   * @throws IllegalStateException if the key is not valid base64 or not exactly 32 bytes
   */
  public static GiftCardCodeGenerator fromBase64Key(String base64Key) {
    Objects.requireNonNull(base64Key, "base64Key");
    byte[] keyBytes;
    try {
      keyBytes = Base64.getDecoder().decode(base64Key.strip());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("native.giftcard.code-key is not valid base64", e);
    }
    if (keyBytes.length != KEY_BYTES) {
      throw new IllegalStateException(
          "native.giftcard.code-key must decode to "
              + KEY_BYTES
              + " bytes; got "
              + keyBytes.length
              + " bytes");
    }
    return new GiftCardCodeGenerator(new SecretKeySpec(keyBytes, ALGORITHM));
  }

  /**
   * Derives the gift card's display code from its id.
   *
   * @param giftCardId the gift card's UUID (minted at the till — never generated independently)
   * @return a 16-character uppercase Base32 code (letters + 2-7, no padding)
   */
  public String deriveCode(UUID giftCardId) {
    Objects.requireNonNull(giftCardId, "giftCardId");
    byte[] digest;
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      digest = mac.doFinal(giftCardId.toString().getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Failed to derive a gift-card code", e);
    }
    byte[] truncated = new byte[ENCODED_BYTE_COUNT];
    System.arraycopy(digest, 0, truncated, 0, ENCODED_BYTE_COUNT);
    return base32Encode(truncated);
  }

  /** A minimal RFC 4648 Base32 encoder (no external dependency, no padding). */
  private static String base32Encode(byte[] data) {
    StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
    int buffer = 0;
    int bitsLeft = 0;
    for (byte b : data) {
      buffer = (buffer << 8) | (b & 0xFF);
      bitsLeft += 8;
      while (bitsLeft >= 5) {
        int index = (buffer >> (bitsLeft - 5)) & 0x1F;
        out.append(ALPHABET[index]);
        bitsLeft -= 5;
      }
    }
    if (bitsLeft > 0) {
      int index = (buffer << (5 - bitsLeft)) & 0x1F;
      out.append(ALPHABET[index]);
    }
    return out.toString();
  }
}
