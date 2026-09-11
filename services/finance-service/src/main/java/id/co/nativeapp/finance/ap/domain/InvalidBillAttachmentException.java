package id.co.nativeapp.finance.ap.domain;

/**
 * The uploaded bytes are not a supported type (jpeg/png/webp/pdf) or contradict the declared one.
 */
public class InvalidBillAttachmentException extends RuntimeException {

  public InvalidBillAttachmentException(String declaredContentType, String detectedContentType) {
    super(
        "attachment bytes are not a supported type (jpeg/png/webp/pdf) or contradict the declared"
            + " type '"
            + declaredContentType
            + "' (detected: "
            + (detectedContentType == null ? "none" : detectedContentType)
            + ")");
  }
}
