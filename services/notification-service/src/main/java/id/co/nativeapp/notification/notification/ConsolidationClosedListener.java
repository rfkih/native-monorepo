package id.co.nativeapp.notification.notification;

import id.co.nativeapp.notification.config.ConsolidationClosedDecodeException;
import id.co.nativeapp.notification.config.MissingEventIdException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code ConsolidationClosed} off Kafka — the notification TRIGGER — and hands it to the
 * idempotent {@link NotificationDeliveryService}, which creates a notification, delivers it through
 * the stubbed transport, persists a {@code delivery_receipt}, and publishes a {@code
 * DeliveryReceipt} event — all in one tenant-scoped transaction.
 *
 * <p><strong>Raw Avro bytes.</strong> The message value is the finance outbox payload — raw Avro
 * bytes shipped by Debezium — so the container delivers a {@code byte[]} value (configured in
 * {@code application.yml}); this listener decodes it with {@code libs/events AvroSerde} via {@link
 * ConsolidationClosedSchema}. No Confluent / Schema Registry serde.
 *
 * <p><strong>Idempotency by event id, not offset.</strong> The dedupe key is the event's UUID,
 * taken from the {@code id} Kafka header the Debezium outbox event router stamps. Deduping by the
 * durable id survives rebalances and compacted replay (HR-3). The dedupe + side effects happen
 * transactionally in {@link NotificationDeliveryService} / {@link NotificationDeliveryWriter}. A
 * record arriving WITHOUT a valid {@code id} header is a producer-side contract violation: the
 * listener fails closed ({@link MissingEventIdException}) so the record is DLT'd rather than
 * processed under a non-durable synthesised id that would defeat dedupe.
 *
 * <p><strong>Tenant from the event.</strong> There is no JWT on the consumer path; the handler is
 * bound to the event's {@code company_id} inside {@link NotificationDeliveryService} so RLS
 * applies.
 *
 * <p><strong>A delivery FAILURE is not poison.</strong> A stub-simulated send failure is a recorded
 * outcome (a FAILED notification + receipt + event) committed in the transaction — it does NOT
 * throw out of the handler and never reaches the DLT. Only an undecodable payload ({@link
 * ConsolidationClosedDecodeException}) or a missing {@code id} header routes to {@code
 * ConsolidationClosed.DLT}.
 */
@Component
public class ConsolidationClosedListener {

  /**
   * The Kafka header carrying the durable event id (the Debezium outbox-router {@code id}, the same
   * convention every Native consumer dedupes on).
   */
  public static final String EVENT_ID_HEADER = "id";

  private static final Logger log = LoggerFactory.getLogger(ConsolidationClosedListener.class);

  private final NotificationDeliveryService deliveryService;

  public ConsolidationClosedListener(NotificationDeliveryService deliveryService) {
    this.deliveryService = deliveryService;
  }

  /**
   * Handles one record from the {@code ConsolidationClosed} topic. Decodes the raw Avro bytes,
   * derives the event id from the header, and delegates to the idempotent delivery service.
   */
  @KafkaListener(
      topics = ConsolidationClosedSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onConsolidationClosed(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    ConsolidationClosedEvent event = decode(eventId, record);
    boolean handled = deliveryService.handle(event);
    if (!handled) {
      log.debug("Skipped re-delivered ConsolidationClosed eventId={} (already processed)", eventId);
    }
  }

  private static ConsolidationClosedEvent decode(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return ConsolidationClosedSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new ConsolidationClosedDecodeException(
          "Failed to decode ConsolidationClosed payload at "
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

  /**
   * Reads the durable event id from the {@code id} header. A missing or non-UUID {@code id} header
   * is a contract violation: we FAIL CLOSED — throwing {@link MissingEventIdException}, which the
   * container's error handler treats as non-retryable and routes straight to {@code
   * ConsolidationClosed.DLT}, rather than synthesising a positional id that would defeat dedupe
   * across a rebalance / compacted replay (creating a duplicate notification — HR-3 forbids it).
   */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new MissingEventIdException(
          "ConsolidationClosed record at "
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
          "ConsolidationClosed '"
              + EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
