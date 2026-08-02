package id.co.nativeapp.finance.register.messaging;

/**
 * A {@code RegisterSessionClosed} record that can never be processed: undecodable Avro bytes OR an
 * internally inconsistent reconciliation identity (ADR 0036). Registered as NON-RETRYABLE in {@code
 * KafkaConfig} so the poison record routes straight to {@code RegisterSessionClosed.DLT} instead of
 * blocking the partition — money is preserved for inspection, never posted from contradictory
 * figures.
 */
public class RegisterSessionClosedDecodeException extends RuntimeException {

  public RegisterSessionClosedDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
