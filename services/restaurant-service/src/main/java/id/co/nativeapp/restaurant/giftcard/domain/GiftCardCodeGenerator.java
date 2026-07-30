package id.co.nativeapp.restaurant.giftcard.domain;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Derives a gift card's human-facing {@code code} DETERMINISTICALLY from its UUID {@code id} —
 * ported VERBATIM from {@code loyalty-service}'s {@code
 * id.co.nativeapp.loyalty.giftcard.domain.GiftCardCodeGenerator} (the exact derivation, so the till
 * that mints the card and loyalty-service's own read of the same id agree on the printed code
 * without any cross-service call). See that class's javadoc for the full scheme rationale (Base32,
 * first 10 of 16 UUID bytes, no padding).
 *
 * <p>This vertical mints the gift card's UUID {@code id} at the till (ADR 0027 decision 5) — never
 * loyalty-service — so deriving the code HERE (not waiting for {@code GiftCardStateChanged} to
 * replicate back) lets the receipt/till print the code immediately.
 */
public final class GiftCardCodeGenerator {

  /** RFC 4648 Base32 alphabet (unambiguous, no padding character in the derived code). */
  private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

  /** Only the first N bytes of the 16-byte UUID are encoded (see class javadoc). */
  private static final int ENCODED_BYTE_COUNT = 10;

  private GiftCardCodeGenerator() {}

  /**
   * Derives the gift card's display code from its id.
   *
   * @param giftCardId the gift card's UUID (minted at the till — never generated independently)
   * @return a 16-character uppercase Base32 code (letters + 2-7, no padding)
   */
  public static String deriveCode(UUID giftCardId) {
    ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES * 2);
    buffer.putLong(giftCardId.getMostSignificantBits());
    buffer.putLong(giftCardId.getLeastSignificantBits());
    byte[] allBytes = buffer.array();
    byte[] truncated = new byte[ENCODED_BYTE_COUNT];
    System.arraycopy(allBytes, 0, truncated, 0, ENCODED_BYTE_COUNT);
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
