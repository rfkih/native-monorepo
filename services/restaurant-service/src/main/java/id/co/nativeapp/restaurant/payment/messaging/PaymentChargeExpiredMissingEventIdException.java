package id.co.nativeapp.restaurant.payment.messaging;

/**
 * Thrown when a {@code PaymentChargeExpired} Kafka record has no valid {@code id} header (a
 * producer-side contract violation). The {@link id.co.nativeapp.restaurant.config.KafkaConfig}
 * error handler classifies this as <strong>non-retryable</strong>: the record is routed immediately
 * to the topic's {@code .DLT} — fail closed. Mirrors {@link
 * PaymentChargeSucceededMissingEventIdException} exactly.
 */
public class PaymentChargeExpiredMissingEventIdException extends RuntimeException {

  public PaymentChargeExpiredMissingEventIdException(String message) {
    super(message);
  }

  public PaymentChargeExpiredMissingEventIdException(String message, Throwable cause) {
    super(message, cause);
  }
}
