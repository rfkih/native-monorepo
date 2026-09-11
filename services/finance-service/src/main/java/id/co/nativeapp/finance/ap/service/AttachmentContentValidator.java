package id.co.nativeapp.finance.ap.service;

import id.co.nativeapp.finance.ap.domain.InvalidBillAttachmentException;
import id.co.nativeapp.mediastorage.ImageContentTypeValidator;
import java.util.Locale;

/**
 * Detects a bill attachment's REAL type from its bytes — an image (jpeg/png/webp, by magic bytes)
 * or a PDF ({@code %PDF-}) — and refuses anything else or a declared type that disagrees. The
 * canonical detected type is what gets stored and served, never the untrusted header.
 */
public final class AttachmentContentValidator {

  public static final String CONTENT_TYPE_PDF = "application/pdf";

  /** {@code %PDF-} — the mandatory leading signature of every PDF (ISO 32000). */
  private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

  private AttachmentContentValidator() {}

  /**
   * @return the canonical content type detected from the bytes
   * @throws InvalidBillAttachmentException if the bytes match no supported type, or disagree with a
   *     non-null {@code declaredContentType}
   */
  public static String validate(String declaredContentType, byte[] data) {
    String detected = detect(data);
    // A browser sends `application/octet-stream` (or nothing) when the file's type is unknown —
    // common for a PDF handed over by an Android file manager. That is "undeclared", not a lie.
    String normalizedDeclared =
        declaredContentType == null ? null : declaredContentType.strip().toLowerCase(Locale.ROOT);
    if (normalizedDeclared != null
        && (normalizedDeclared.isEmpty()
            || normalizedDeclared.equals("application/octet-stream"))) {
      normalizedDeclared = null;
    }
    if (detected == null || (normalizedDeclared != null && !detected.equals(normalizedDeclared))) {
      throw new InvalidBillAttachmentException(declaredContentType, detected);
    }
    return detected;
  }

  private static String detect(byte[] data) {
    String image = ImageContentTypeValidator.detect(data);
    if (image != null) {
      return image;
    }
    return startsWith(data, PDF_MAGIC) ? CONTENT_TYPE_PDF : null;
  }

  private static boolean startsWith(byte[] data, byte[] magic) {
    if (data == null || data.length < magic.length) {
      return false;
    }
    for (int i = 0; i < magic.length; i++) {
      if (data[i] != magic[i]) {
        return false;
      }
    }
    return true;
  }
}
