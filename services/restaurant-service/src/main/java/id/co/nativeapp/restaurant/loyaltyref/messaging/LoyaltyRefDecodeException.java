package id.co.nativeapp.restaurant.loyaltyref.messaging;

/**
 * Thrown when a {@code LoyaltyBalanceChanged} or {@code GiftCardStateChanged} payload cannot be
 * decoded (garbage bytes, truncated Avro, or a schema mismatch). The {@link
 * id.co.nativeapp.restaurant.config.KafkaConfig} error handler classifies this as
 * <strong>non-retryable</strong>: a record that cannot be decoded will never succeed on retry and
 * is routed immediately to {@code <topic>.DLT} — fail closed, never an infinite in-place retry that
 * blocks the partition. Shared by both loyaltyref listeners (mirrors {@code
 * outletref.messaging.UserOutletAssignmentDecodeException}).
 */
public class LoyaltyRefDecodeException extends RuntimeException {

  public LoyaltyRefDecodeException(String message, Throwable cause) {
    super(message, cause);
  }
}
