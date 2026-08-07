package id.co.nativeapp.restaurant.payment.messaging;

/**
 * Thrown when a {@code PaymentChargeSucceeded} payload cannot be decoded (garbage bytes, truncated
 * Avro, or a schema mismatch). The {@link id.co.nativeapp.restaurant.config.KafkaConfig} error
 * handler classifies this as <strong>non-retryable</strong>: a record that cannot be decoded will
 * never succeed on retry and is routed immediately to the topic's {@code .DLT} — fail closed, never
 * an infinite in-place retry that blocks the partition. Mirrors {@code
 * entitlement.messaging.EntitlementDecodeException} exactly.
 */
public class PaymentChargeSucceededDecodeException extends RuntimeException {

  public PaymentChargeSucceededDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
