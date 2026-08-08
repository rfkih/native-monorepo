package id.co.nativeapp.mediastorage;

/**
 * Thrown by {@link ImageContentTypeValidator#validate} when an upload's actual bytes match no
 * whitelisted image signature, or disagree with a non-null declared {@code Content-Type} header.
 * Services translate this to their RFC-7807 {@code 422} the same way they did their (now unified)
 * per-service validator exceptions.
 */
public class UnsupportedImageTypeException extends RuntimeException {

  private final String declaredContentType;
  private final String detectedContentType;

  public UnsupportedImageTypeException(String declaredContentType, String detectedContentType) {
    super(
        "image content type rejected: declared="
            + declaredContentType
            + ", detected-from-bytes="
            + (detectedContentType == null
                ? "none (unrecognised signature)"
                : detectedContentType));
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
