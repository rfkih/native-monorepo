package id.co.nativeapp.employee.expense.domain;

import java.util.Locale;

/**
 * Verifies a receipt upload's ACTUAL bytes against its DECLARED {@code Content-Type} by magic-byte
 * signature — the declared multipart header is client-supplied and therefore untrusted (ADR 0030
 * §8): a spoofed header (e.g. an executable renamed {@code receipt.jpg}) must not be accepted just
 * because the client claims {@code image/jpeg}.
 *
 * <p>The whitelist is exactly three formats, each detected by its well-known leading-byte
 * signature:
 *
 * <ul>
 *   <li>{@code image/jpeg} — {@code FF D8 FF};
 *   <li>{@code image/png} — {@code 89 50 4E 47};
 *   <li>{@code image/webp} — {@code "RIFF"} at offset 0 followed by {@code "WEBP"} at offset 8 (the
 *       4-byte RIFF chunk size sits in between and is not checked).
 * </ul>
 *
 * <p>Stateless and framework-light (domain layer, CODE-STRUCTURE §3.4): no Spring dependency.
 */
public final class ReceiptContentTypeValidator {

  public static final String CONTENT_TYPE_JPEG = "image/jpeg";
  public static final String CONTENT_TYPE_PNG = "image/png";
  public static final String CONTENT_TYPE_WEBP = "image/webp";

  private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47};
  private static final byte[] RIFF_MAGIC = {'R', 'I', 'F', 'F'};
  private static final byte[] WEBP_MAGIC = {'W', 'E', 'B', 'P'};
  private static final int WEBP_MARK_OFFSET = 8;

  private ReceiptContentTypeValidator() {}

  /**
   * Detects the content type from the file's ACTUAL bytes.
   *
   * @return one of the {@code CONTENT_TYPE_*} constants, or {@code null} if {@code data} matches
   *     none of the whitelisted signatures (including a truncated/too-short input — a short-input
   *     bounds check, never an {@code ArrayIndexOutOfBoundsException})
   */
  public static String detect(byte[] data) {
    if (data == null) {
      return null;
    }
    if (startsWith(data, 0, JPEG_MAGIC)) {
      return CONTENT_TYPE_JPEG;
    }
    if (startsWith(data, 0, PNG_MAGIC)) {
      return CONTENT_TYPE_PNG;
    }
    if (startsWith(data, 0, RIFF_MAGIC) && startsWith(data, WEBP_MARK_OFFSET, WEBP_MAGIC)) {
      return CONTENT_TYPE_WEBP;
    }
    return null;
  }

  /**
   * Validates that {@code declaredContentType} matches the type {@link #detect(byte[])} derives
   * from {@code data} itself (case-insensitive on the declared value) and returns the CANONICAL
   * detected constant — callers persist THAT, never the raw declared header, so the later serve
   * {@code Content-Type} is guaranteed-canonical (E3 review S1). A {@code null} declared header (a
   * multipart part without one — browsers always send it, some API clients don't) is tolerated when
   * the bytes themselves match a whitelisted signature: the magic bytes are the authority, the
   * header only cross-checks it (E3 review S2).
   *
   * @return the canonical {@code CONTENT_TYPE_*} constant detected from the bytes
   * @throws InvalidReceiptContentTypeException if the actual bytes are unrecognised, or disagree
   *     with a non-null declared header (→ 422)
   */
  public static String validate(String declaredContentType, byte[] data) {
    String detected = detect(data);
    String normalizedDeclared =
        declaredContentType == null ? null : declaredContentType.strip().toLowerCase(Locale.ROOT);
    if (detected == null || (normalizedDeclared != null && !detected.equals(normalizedDeclared))) {
      throw new InvalidReceiptContentTypeException(declaredContentType, detected);
    }
    return detected;
  }

  private static boolean startsWith(byte[] data, int offset, byte[] magic) {
    if (data.length < offset + magic.length) {
      return false;
    }
    for (int i = 0; i < magic.length; i++) {
      if (data[offset + i] != magic[i]) {
        return false;
      }
    }
    return true;
  }
}
