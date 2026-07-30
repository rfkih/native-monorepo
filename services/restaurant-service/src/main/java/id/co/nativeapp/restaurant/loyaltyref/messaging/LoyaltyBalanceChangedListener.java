package id.co.nativeapp.restaurant.loyaltyref.messaging;

import id.co.nativeapp.restaurant.loyaltyref.service.LoyaltyBalanceChangedService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code LoyaltyBalanceChanged} off Kafka (produced by loyalty-service, ADR 0027) and
 * applies it to restaurant-service's local {@code member_balance_ref} read model, set-if-newer by
 * {@code balance_seq}. This read model backs {@link
 * id.co.nativeapp.restaurant.loyaltyref.service.LoyaltyRedemptionGuard}'s checkout-time points-
 * redemption clamp WITHOUT a synchronous call to loyalty-service (rule 2).
 *
 * <p>Mirrors {@code outletref.messaging.UserOutletAssignmentListener} in every particular: raw Avro
 * bytes (no Schema Registry serde), idempotency by the durable {@code id} Kafka header (not the
 * offset), tenant bound from the event's {@code company_id} (no JWT on the consumer path), and a
 * bounded-retry-then-DLT error-handling posture wired in {@link
 * id.co.nativeapp.restaurant.config.KafkaConfig}.
 */
@Component
public class LoyaltyBalanceChangedListener {

  public static final String EVENT_ID_HEADER = "id";

  private static final Logger log = LoggerFactory.getLogger(LoyaltyBalanceChangedListener.class);

  private final LoyaltyBalanceChangedService service;

  public LoyaltyBalanceChangedListener(LoyaltyBalanceChangedService service) {
    this.service = service;
  }

  @KafkaListener(
      topics = LoyaltyBalanceChangedConsumerSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onLoyaltyBalanceChanged(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    LoyaltyBalanceChangedEvent event = decodePayload(eventId, record);
    boolean applied = service.apply(event);
    if (!applied) {
      log.debug(
          "Skipped re-delivered LoyaltyBalanceChanged eventId={} memberId={} (already processed or"
              + " stale balanceSeq)",
          eventId,
          event.memberId());
    }
  }

  private static LoyaltyBalanceChangedEvent decodePayload(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return LoyaltyBalanceChangedConsumerSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new LoyaltyRefDecodeException(
          "Failed to decode LoyaltyBalanceChanged payload at "
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
          "LoyaltyBalanceChanged record at "
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
          "LoyaltyBalanceChanged '" + EVENT_ID_HEADER + "' header is not a UUID (" + raw + ")");
    }
  }
}
