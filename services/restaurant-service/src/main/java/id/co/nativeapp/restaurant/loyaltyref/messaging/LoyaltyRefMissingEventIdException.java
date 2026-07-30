package id.co.nativeapp.restaurant.loyaltyref.messaging;

/**
 * Thrown when a {@code LoyaltyBalanceChanged}/{@code GiftCardStateChanged} Kafka record has no
 * valid {@code id} header (a producer-side contract violation). The {@link
 * id.co.nativeapp.restaurant.config.KafkaConfig} error handler classifies this as
 * <strong>non-retryable</strong>: the record is routed immediately to {@code <topic>.DLT} — fail
 * closed. Shared by both loyaltyref listeners.
 */
public class LoyaltyRefMissingEventIdException extends RuntimeException {

  public LoyaltyRefMissingEventIdException(String message) {
    super(message);
  }
}
