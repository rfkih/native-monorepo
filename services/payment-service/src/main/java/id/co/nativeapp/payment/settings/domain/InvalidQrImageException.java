package id.co.nativeapp.payment.settings.domain;

/**
 * Thrown when a static-QRIS upload's actual bytes match no whitelisted image signature, or disagree
 * with the declared multipart {@code Content-Type} (→ 422 via {@code PaymentAdvice}). The message
 * names the declared and detected types only — never any byte content.
 */
public class InvalidQrImageException extends RuntimeException {

  public InvalidQrImageException(String declaredContentType, String detectedContentType) {
    super(
        "QRIS image content type mismatch: declared="
            + (declaredContentType == null ? "(none)" : declaredContentType)
            + ", detected="
            + (detectedContentType == null ? "(unrecognised)" : detectedContentType)
            + " — only image/jpeg, image/png and image/webp are accepted");
  }
}
