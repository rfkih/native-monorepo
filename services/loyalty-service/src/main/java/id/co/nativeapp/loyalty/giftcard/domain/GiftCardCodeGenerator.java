package id.co.nativeapp.loyalty.giftcard.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Derives a gift card's human-facing {@code code} DETERMINISTICALLY from its UUID {@code id}. The
 * {@code code} column carries NO PII (rule 6 — it is a random-looking token, not a customer
 * identifier), but it IS a bearer credential: whoever presents a valid code can redeem the card's
 * stored value at a POS (see {@link id.co.nativeapp.loyalty.giftcard.service.GiftCardReader}).
 *
 * <p><strong>Keyed derivation (security review W-4).</strong> The gift-card {@code id} itself is
 * NOT a secret — it is carried on the broadcast {@code GiftCardSold}/{@code GiftCardStateChanged}
 * events, on database rows readable by anyone with query access, and on trace spans. An UNKEYED
 * derivation (a bare function of the id, e.g. Base32 of its raw bytes) would therefore give the
 * printed code NO independent secrecy of its own: anyone who ever saw the id (an event consumer, a
 * DB dump, an ops trace) could recompute the exact same code and redeem the card without ever
 * seeing the physical/printed token. Keying the derivation with an HMAC closes that gap —
 * recovering the code from the id additionally requires the fleet-wide {@code
 * NATIVE_GIFTCARD_CODE_KEY}.
 *
 * <p><strong>The scheme.</strong> HMAC-SHA256(key, message) where {@code message} is the UTF-8
 * bytes of {@code giftCardId.toString()} (the canonical, lower-case, hyphenated UUID string — NOT
 * the raw 16 UUID bytes; a string message is trivial to reproduce with any off-the-shelf HMAC tool
 * for verification, with no custom byte-packing step to get subtly wrong). The FIRST 10 bytes of
 * the 32-byte digest are then Crockford-style RFC 4648 Base32-encoded WITHOUT padding, uppercased —
 * the exact same encoding this class used pre-W-4, so the code shape (16 unambiguous uppercase
 * letters/digits, {@code VARCHAR(24)}) is unchanged; only what feeds the encoder changed (a keyed
 * HMAC digest instead of the id's raw bytes).
 *
 * <p><strong>Fleet-wide key (must match everywhere).</strong> This EXACT class is ported verbatim
 * into the three vertical services that also mint gift cards at the till ({@code
 * restaurant-service} / {@code carwash-service} / {@code barbershop-service}) — a card's code must
 * be reproducible identically wherever it is derived, without a synchronous cross-service call
 * (rule 2). All four services therefore bind the SAME env var name, {@code
 * NATIVE_GIFTCARD_CODE_KEY}, to the SAME base64-encoded 32-byte value (Vault-provisioned in prod,
 * mirroring how {@code NATIVE_PII_*} is provisioned — see {@code GiftCardProperties}/{@code
 * GiftCardCodeConfig} in each service's {@code config} package). A key mismatch between services
 * would silently make the SAME card resolve to DIFFERENT codes depending on who minted vs. who
 * reads it — there is no automated cross-service check for this; it is an operational invariant
 * documented here and at each provisioning site.
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
   * the key is not a valid 32-byte value — the same fail-fast contract {@code PhoneHasher} and
   * {@code PiiCipher} established, so a misconfigured key can never silently degrade to an unkeyed
   * derivation.
   *
   * @param base64Key the base64-encoded 32-byte key from {@code NATIVE_GIFTCARD_CODE_KEY} (via each
   *     service's {@code GiftCardProperties})
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
   * @param giftCardId the gift card's UUID (the aggregate id, from the event — never generated
   *     independently)
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
      // The message carries no key material — only the operation that failed.
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
