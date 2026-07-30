package id.co.nativeapp.restaurant.loyaltyref.messaging;

import id.co.nativeapp.restaurant.loyaltyref.service.GiftCardStateChangedService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code GiftCardStateChanged} off Kafka (produced by loyalty-service, ADR 0027) and
 * applies it to restaurant-service's local {@code gift_card_ref} read model, set-if-newer by {@code
 * balance_seq}. Backs {@link
 * id.co.nativeapp.restaurant.loyaltyref.service.LoyaltyRedemptionGuard}'s checkout-time
 * gift-card-redemption clamp WITHOUT a synchronous call to loyalty-service (rule 2).
 *
 * <p>Mirrors {@link LoyaltyBalanceChangedListener} in every particular.
 */
@Component
public class GiftCardStateChangedListener {

  public static final String EVENT_ID_HEADER = "id";

  private static final Logger log = LoggerFactory.getLogger(GiftCardStateChangedListener.class);

  private final GiftCardStateChangedService service;

  public GiftCardStateChangedListener(GiftCardStateChangedService service) {
    this.service = service;
  }

  @KafkaListener(
      topics = GiftCardStateChangedConsumerSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onGiftCardStateChanged(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    GiftCardStateChangedEvent event = decodePayload(eventId, record);
    boolean applied = service.apply(event);
    if (!applied) {
      log.debug(
          "Skipped re-delivered GiftCardStateChanged eventId={} giftCardId={} (already processed or"
              + " stale balanceSeq)",
          eventId,
          event.giftCardId());
    }
  }

  private static GiftCardStateChangedEvent decodePayload(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return GiftCardStateChangedConsumerSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new LoyaltyRefDecodeException(
          "Failed to decode GiftCardStateChanged payload at "
              + record.topic()
              + "-"
              + record.partition()
              + "@"
              + record.offset()
              + " (eventId="
              + eventId
              + "); routing to DLT",
          decodeFailure);
    }
  }

  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new LoyaltyRefMissingEventIdException(
          "GiftCardStateChanged record at "
              + record.topic()
              + "-"
              + record.partition()
              + "@"
              + record.offset()
              + " has no '"
              + EVENT_ID_HEADER
              + "' header; routing to DLT (fail closed)");
    }
    String raw = new String(header.value(), StandardCharsets.UTF_8).strip();
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException badId) {
      throw new LoyaltyRefMissingEventIdException(
          "GiftCardStateChanged '" + EVENT_ID_HEADER + "' header is not a UUID (" + raw + ")");
    }
  }
}
