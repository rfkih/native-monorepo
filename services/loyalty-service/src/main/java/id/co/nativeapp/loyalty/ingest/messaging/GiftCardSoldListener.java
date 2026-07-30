package id.co.nativeapp.loyalty.ingest.messaging;

import id.co.nativeapp.loyalty.config.EventDecodeException;
import id.co.nativeapp.loyalty.config.MissingEventIdException;
import id.co.nativeapp.loyalty.ingest.dto.GiftCardSoldFact;
import id.co.nativeapp.loyalty.ingest.service.GiftCardSoldService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code GiftCardSold} off Kafka (a vertical's producer — restaurant/carwash/barbershop,
 * ADR 0003 single shared schema) and creates/tops-up the card + appends a {@code LOAD} ledger
 * entry, idempotently (ADR 0027). Same raw-Avro-bytes / event-id-header dedupe / bounded-retry-
 * then-DLT shape as {@link SaleRecordedListener} — see its class javadoc for the full rationale.
 */
@Component
public class GiftCardSoldListener {

  public static final String EVENT_ID_HEADER = "id";

  private static final Logger log = LoggerFactory.getLogger(GiftCardSoldListener.class);

  private final GiftCardSoldService giftCardSoldService;

  public GiftCardSoldListener(GiftCardSoldService giftCardSoldService) {
    this.giftCardSoldService = giftCardSoldService;
  }

  @KafkaListener(
      topics = GiftCardSoldConsumerSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onGiftCardSold(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    GiftCardSoldFact fact = decode(eventId, record);
    boolean applied = giftCardSoldService.apply(fact);
    if (!applied) {
      log.debug(
          "Skipped re-delivered GiftCardSold eventId={} giftCardId={} (already processed)",
          eventId,
          fact.giftCardId());
    }
  }

  private static GiftCardSoldFact decode(UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return GiftCardSoldConsumerSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new EventDecodeException(
          "Failed to decode GiftCardSold payload at "
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
      throw new MissingEventIdException(
          "GiftCardSold record at "
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
      throw new MissingEventIdException(
          "GiftCardSold '" + EVENT_ID_HEADER + "' header is not a UUID (" + raw + ")", badId);
    }
  }
}
