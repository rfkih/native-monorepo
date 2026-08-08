package id.co.nativeapp.restaurant.menu.domain;

/**
 * A menu image upload's payload is structurally unusable — not a base64 data URL, malformed base64,
 * empty, or larger than the decoded-size cap (ADR 0048). Mapped to {@code 422 menu-image-invalid}
 * by {@code MenuImageAdvice}. (A payload that decodes fine but is not a whitelisted image format
 * throws the shared {@code UnsupportedImageTypeException} instead.)
 */
public class InvalidMenuImageException extends RuntimeException {

  public InvalidMenuImageException(String message) {
    super(message);
  }
}
