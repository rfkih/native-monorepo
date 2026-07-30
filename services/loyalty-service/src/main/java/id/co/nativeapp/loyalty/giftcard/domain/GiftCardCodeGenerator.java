package id.co.nativeapp.loyalty.giftcard.domain;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Derives a gift card's human-facing {@code code} DETERMINISTICALLY from its UUID {@code id} (the
 * task's explicit instruction — the code is derived, not independently generated/stored-then-
 * looked-up). Deterministic derivation means the code never needs its own uniqueness scan against
 * history: it is a pure function of an already-unique UUID, so {@code UNIQUE(company_id, code)} on
 * the {@code gift_card} table is really guarding against a UUID collision (astronomically unlikely)
 * rather than a code-generation collision. The {@code code} column itself carries NO PII (rule 6 —
 * it is a random-looking token, not a customer identifier).
 *
 * <p><strong>The chosen scheme (documented, per the task).</strong> Take the UUID's 16 bytes (8
 * bytes most-significant + 8 bytes least-significant), keep the FIRST 10 bytes, and Crockford-style
 * RFC 4648 Base32-encode them WITHOUT padding, uppercased. 10 bytes encode to exactly {@code
 * ceil(10*8/5) = 16} Base32 characters, comfortably inside the {@code VARCHAR(24)} column. Base32
 * (not Base64) is chosen specifically because it uses only unambiguous uppercase letters + digits
 * 2-7 — no {@code 0/O}, {@code 1/I/l} confusion and no {@code +/=} punctuation — so a printed gift
 * card code is easy to read aloud or key in at a till. Truncating to the first 10 (of 16) UUID
 * bytes is an accepted, deliberate trade-off: it shortens the printable code at the cost of a
 * theoretical (not practical, at UUIDv4 volumes) increase in collision probability, caught anyway
 * by the DB unique constraint (fails closed, never silently overwrites another card).
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
   * @param giftCardId the gift card's UUID (the aggregate id, from the event — never generated
   *     independently)
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
