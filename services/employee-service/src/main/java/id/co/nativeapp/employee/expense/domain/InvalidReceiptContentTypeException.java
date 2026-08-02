package id.co.nativeapp.employee.expense.domain;

/**
 * A receipt upload's ACTUAL bytes (magic-byte signature) disagree with its DECLARED {@code
 * Content-Type} — or match no whitelisted format at all (ADR 0030 §8: jpeg/png/webp only, verified
 * by file signature, never the client-supplied header). Mapped to HTTP 422 (Unprocessable Entity)
 * by {@code EmployeeApiAdvice}: the request is well-formed multipart, but the file itself is
 * rejected.
 */
public class InvalidReceiptContentTypeException extends RuntimeException {

  public InvalidReceiptContentTypeException(
      String declaredContentType, String detectedContentType) {
    super(
        "Receipt content-type mismatch: declared '"
            + declaredContentType
            + "', but the file signature is "
            + (detectedContentType == null
                ? "an unrecognised format"
                : "'" + detectedContentType + "'")
            + " (only image/jpeg, image/png, image/webp are accepted, verified by magic bytes)");
  }
}
