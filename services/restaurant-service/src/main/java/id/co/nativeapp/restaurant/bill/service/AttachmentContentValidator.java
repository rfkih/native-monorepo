package id.co.nativeapp.restaurant.bill.service;

import id.co.nativeapp.mediastorage.ImageContentTypeValidator;
import id.co.nativeapp.restaurant.bill.domain.InvalidBillAttachmentException;
import java.util.Locale;

/**
 * Validates a BILL ATTACHMENT's ACTUAL bytes (ADR 0063): the whitelisted image formats (JPEG/PNG/
 * WEBP, via the fleet-shared {@link ImageContentTypeValidator}, ADR 0048) OR a PDF document
 * (leading {@code %PDF-} signature). Returns the CANONICAL content type to persist and later serve
 * — never the untrusted declared multipart {@code Content-Type}, which is only cross-checked.
 * Unrecognised bytes, or bytes that disagree with a non-null declared header, throw {@link
 * InvalidBillAttachmentException} ({@code 422}).
 *
 * <p>This is deliberately BILL-scoped (not folded into the shared image validator) so the menu / QR
 * image paths stay image-only — only a bill attachment accepts a PDF.
 */
public final class AttachmentContentValidator {

  public static final String CONTENT_TYPE_PDF = "application/pdf";

  /** {@code %PDF-} — the mandatory leading signature of every PDF (ISO 32000). */
  private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

  private AttachmentContentValidator() {}

  /**
   * @return the canonical content type detected from the bytes (an {@code image/*} constant or
   *     {@link #CONTENT_TYPE_PDF})
   * @throws InvalidBillAttachmentException if the bytes match no supported type, or disagree with a
   *     non-null {@code declaredContentType}
   */
  public static String validate(String declaredContentType, byte[] data) {
    String detected = detect(data);
    String normalizedDeclared =
        declaredContentType == null ? null : declaredContentType.strip().toLowerCase(Locale.ROOT);
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
