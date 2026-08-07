package id.co.nativeapp.restaurant.payment.messaging;

/**
 * Thrown when a {@code PaymentChargeSucceeded} Kafka record has no valid {@code id} header (a
 * producer-side contract violation). The {@link id.co.nativeapp.restaurant.config.KafkaConfig}
 * error handler classifies this as <strong>non-retryable</strong>: the record is routed immediately
 * to the topic's {@code .DLT} — fail closed. Mirrors {@code
 * entitlement.messaging.EntitlementMissingEventIdException} exactly.
 */
public class PaymentChargeSucceededMissingEventIdException extends RuntimeException {

  public PaymentChargeSucceededMissingEventIdException(String message) {
    super(message);
  }

  public PaymentChargeSucceededMissingEventIdException(String message, Throwable cause) {
    super(message, cause);
  }
}
