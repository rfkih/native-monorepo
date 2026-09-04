package id.co.nativeapp.restaurant.bill.domain;

/**
 * A bill attachment whose ACTUAL bytes match no supported type (JPEG/PNG/WEBP/PDF) or contradict
 * the declared multipart {@code Content-Type} (ADR 0063). The declared header is untrusted; the
 * magic-byte check is the authority. Mapped to {@code 422} ({@code bill-attachment-invalid}) by
 * {@code config.BillAttachmentAdvice}.
 */
public class InvalidBillAttachmentException extends RuntimeException {

  private final String declaredContentType;
  private final String detectedContentType;

  public InvalidBillAttachmentException(String declaredContentType, String detectedContentType) {
    super(
        "attachment bytes are not a supported type (jpeg/png/webp/pdf) or contradict the declared"
            + " type '"
            + declaredContentType
            + "' (detected: "
            + (detectedContentType == null ? "none" : detectedContentType)
            + ")");
    this.declaredContentType = declaredContentType;
    this.detectedContentType = detectedContentType;
  }

  public String getDeclaredContentType() {
    return declaredContentType;
  }

  public String getDetectedContentType() {
    return detectedContentType;
  }
}
