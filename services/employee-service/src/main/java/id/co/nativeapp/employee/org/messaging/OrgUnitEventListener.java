package id.co.nativeapp.employee.org.messaging;

import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.service.OrgProjectionService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.BiFunction;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code OrgUnitCreated} / {@code OrgUnitChanged} off Kafka and applies them to the local
 * org read model ({@link OrgUnitProjection}) — the projection the same-legal-employer assignment
 * invariant is checked against (ARCHITECTURE.md §2; rule 2 — a cached read model, never a sync
 * call).
 *
 * <p><strong>Raw Avro bytes.</strong> The message value is the org-service outbox payload — raw
 * Avro bytes shipped by Debezium — so the container delivers a {@code byte[]} value; this listener
 * decodes it with {@code libs/events AvroSerde} via {@link OrgUnitEventSchemas}. No Schema Registry
 * serde is used, consistent with how the outbox stores events and with the other consumers in the
 * codebase.
 *
 * <p><strong>Idempotency by event id, not offset.</strong> The dedupe key is the event's UUID,
 * taken from the {@code id} Kafka header the Debezium outbox event router stamps. Deduping by the
 * durable event id (not the offset) survives rebalances and compacted replay (HR-3); the dedupe +
 * projection upsert happen transactionally in {@link OrgProjectionService} / {@link
 * OrgProjectionWriter}. A record arriving WITHOUT a valid {@code id} header is a producer-side
 * contract violation: the listener fails closed (see {@link #eventIdOf}) so the record is DLT'd.
 *
 * <p><strong>Tenant from the event.</strong> There is no JWT on the consumer path; the handler is
 * bound to the event's {@code company_id} inside {@link OrgProjectionService} so RLS applies on the
 * projection write.
 *
 * <p>A non-transient failure is retried a bounded number of times and then routed to {@code
 * <topic>.DLT} by the container's error handler — never an infinite in-place retry that blocks the
 * partition.
 */
@Component
public class OrgUnitEventListener {

  /**
   * The Kafka header carrying the durable event id (the Debezium outbox-router {@code id} header).
   */
  public static final String EVENT_ID_HEADER = "id";

  private static final Logger log = LoggerFactory.getLogger(OrgUnitEventListener.class);

  private final OrgProjectionService projectionService;

  public OrgUnitEventListener(OrgProjectionService projectionService) {
    this.projectionService = projectionService;
  }

  /**
   * Handles one {@code OrgUnitCreated}: upserts the projection (active, with its legal employer).
   */
  @KafkaListener(
      topics = OrgUnitEventSchemas.CREATED_TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onOrgUnitCreated(ConsumerRecord<String, byte[]> record) {
    handle(record, OrgUnitEventSchemas::decodeCreated);
  }

  /** Handles one {@code OrgUnitChanged}: updates the projection's type + active state. */
  @KafkaListener(
      topics = OrgUnitEventSchemas.CHANGED_TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onOrgUnitChanged(ConsumerRecord<String, byte[]> record) {
    handle(record, OrgUnitEventSchemas::decodeChanged);
  }

  private void handle(
      ConsumerRecord<String, byte[]> record,
      BiFunction<UUID, byte[], OrgUnitProjectedEvent> decoder) {
    UUID eventId = eventIdOf(record);
    OrgUnitProjectedEvent event = decode(eventId, record, decoder);
    boolean applied = projectionService.apply(event);
    if (!applied) {
      log.debug(
          "Skipped re-delivered org event eventId={} orgUnitId={} (already processed)",
          eventId,
          event.orgUnitId());
    }
  }

  /**
   * Decodes the raw Avro value, converting ANY decode failure into a non-retryable {@link
   * OrgUnitDecodeException} so a poison payload deterministically lands on the DLT rather than
   * retrying forever.
   */
  private static OrgUnitProjectedEvent decode(
      UUID eventId,
      ConsumerRecord<String, byte[]> record,
      BiFunction<UUID, byte[], OrgUnitProjectedEvent> decoder) {
    try {
      return decoder.apply(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new OrgUnitDecodeException(
          "Failed to decode org event payload at "
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
   * container's error handler treats as non-retryable and routes straight to {@code <topic>.DLT}.
   */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new MissingEventIdException(
          "Org event record at "
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
          "Org event '"
              + EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
