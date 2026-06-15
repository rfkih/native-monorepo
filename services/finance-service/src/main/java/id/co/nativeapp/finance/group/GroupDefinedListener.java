package id.co.nativeapp.finance.group;

import id.co.nativeapp.finance.revenue.MissingEventIdException;
import id.co.nativeapp.finance.revenue.SaleRecordedListener;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code GroupDefined} off Kafka into finance's local {@link GroupRef} read model (P3d
 * SEAM 1). Mirrors the {@code LaborCostAllocated} consumer path exactly.
 *
 * <p><strong>Raw Avro bytes.</strong> The message value is the producer outbox payload (raw Avro
 * bytes shipped by Debezium), so the container delivers a {@code byte[]} value (the shared {@code
 * kafkaListenerContainerFactory}); this listener decodes it with {@code libs/events AvroSerde} via
 * {@link GroupDefinedSchema}. No Confluent / Schema Registry serde.
 *
 * <p><strong>Idempotency by event id.</strong> The dedupe key is the event's UUID from the Debezium
 * outbox event-id header ({@code id}); the dedupe + write happen transactionally in {@link
 * GroupReadModelService} / {@link GroupReadModelWriter}. A record without a valid {@code id} header
 * is a contract violation: the listener fails closed ({@link MissingEventIdException}) so the
 * record is DLT'd. An undecodable payload becomes a non-retryable {@link
 * GroupDefinedDecodeException} routed to {@code GroupDefined.DLT}.
 *
 * <p><strong>Tenant from the event.</strong> The handler is bound to the event's {@code
 * lead_company_id} inside the service so RLS applies.
 */
@Component
public class GroupDefinedListener {

  private static final Logger log = LoggerFactory.getLogger(GroupDefinedListener.class);

  private final GroupReadModelService service;

  public GroupDefinedListener(GroupReadModelService service) {
    this.service = service;
  }

  /** Handles one record from the {@code GroupDefined} topic. */
  @KafkaListener(
      topics = GroupDefinedSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onGroupDefined(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    GroupDefinedEvent event = decode(eventId, record);
    boolean applied = service.handleGroupDefined(event);
    if (!applied) {
      log.debug("Skipped re-delivered GroupDefined eventId={} (already processed)", eventId);
    }
  }

  private static GroupDefinedEvent decode(UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return GroupDefinedSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new GroupDefinedDecodeException(
          "Failed to decode GroupDefined payload at "
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
   * Reads the durable event id from the {@code id} header. A missing or non-UUID header is a
   * contract violation: the consumer FAILS CLOSED with {@link MissingEventIdException}, routed
   * non-retryably to the DLT, rather than synthesising a positional id that would defeat dedupe.
   */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(SaleRecordedListener.EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new MissingEventIdException(
          "GroupDefined record at "
              + record.topic()
              + "-"
              + record.partition()
              + "@"
              + record.offset()
              + " has no '"
              + SaleRecordedListener.EVENT_ID_HEADER
              + "' header; routing to DLT (fail closed)");
    }
    String raw = new String(header.value(), StandardCharsets.UTF_8).strip();
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException badId) {
      throw new MissingEventIdException(
          "GroupDefined '"
              + SaleRecordedListener.EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
