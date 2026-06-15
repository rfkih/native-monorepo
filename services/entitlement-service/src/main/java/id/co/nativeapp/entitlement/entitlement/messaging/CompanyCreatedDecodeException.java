package id.co.nativeapp.entitlement.entitlement.messaging;

/**
 * Thrown when a {@code CompanyCreated} record's value cannot be decoded as a valid {@code
 * CompanyCreated} Avro payload (garbage / truncated bytes, a wrong schema). It is a poison record:
 * it can never decode, so retrying in place is futile and would block the partition. The
 * container's error handler treats it as non-retryable and routes the record straight to {@code
 * CompanyCreated.DLT} for inspection rather than looping forever.
 */
public class CompanyCreatedDecodeException extends RuntimeException {

  public CompanyCreatedDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
