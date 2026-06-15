package id.co.nativeapp.carwash.entitlement.messaging;

import id.co.nativeapp.carwash.config.EventDecodeException;
import id.co.nativeapp.carwash.config.MissingEventIdException;
import id.co.nativeapp.carwash.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.carwash.entitlement.service.EntitlementProjectionService;
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
 * Consumes {@code EntitlementGranted} / {@code EntitlementRevoked} off Kafka and applies them to
 * the local entitlement projection ({@link
 * id.co.nativeapp.carwash.entitlement.domain.EntitlementProjection}) — the projection the
 * record-wash gate is checked against (the "real gating in the verticals", Phase-2; rule 2 — a
 * cached read model, never a sync call). On apply, the company's entitlement-check Redis cache is
 * invalidated, so a grant promptly OPENS the gate and a revoke promptly CLOSES it.
 *
 * <p><strong>Raw Avro bytes.</strong> The message value is the entitlement-service outbox payload —
 * raw Avro bytes shipped by Debezium — so the container delivers a {@code byte[]} value; this
 * listener decodes it with {@code libs/events AvroSerde} via {@link EntitlementEventSchemas}. No
 * Schema Registry serde is used.
 *
 * <p><strong>Idempotency by event id, not offset.</strong> The dedupe key is the event's UUID,
 * taken from the {@code id} Kafka header the Debezium outbox event router stamps. Deduping by the
 * durable event id (not the offset) survives rebalances and compacted replay (HR-3); the dedupe +
 * projection upsert happen transactionally in {@link EntitlementProjectionService} / {@link
 * id.co.nativeapp.carwash.entitlement.service.EntitlementProjectionWriter}. A record arriving
 * WITHOUT a valid {@code id} header is a producer-side contract violation: the listener fails
 * closed (see {@link #eventIdOf}) so the record is DLT'd.
 *
 * <p>A non-transient failure is retried a bounded number of times and then routed to {@code
 * <topic>.DLT} by the container's error handler — never an infinite in-place retry that blocks the
 * partition.
 */
@Component
public class EntitlementEventListener {

  /** The Kafka header carrying the durable event id (the Debezium outbox-router {@code id}). */
  public static final String EVENT_ID_HEADER = "id";

  private static final Logger log = LoggerFactory.getLogger(EntitlementEventListener.class);

  private final EntitlementProjectionService projectionService;

  public EntitlementEventListener(EntitlementProjectionService projectionService) {
    this.projectionService = projectionService;
  }

  /** Handles one {@code EntitlementGranted}: upserts the projection to entitled=true. */
  @KafkaListener(
      topics = EntitlementEventSchemas.GRANTED_TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onEntitlementGranted(ConsumerRecord<String, byte[]> record) {
    handle(record, EntitlementEventSchemas::decodeGranted);
  }

  /** Handles one {@code EntitlementRevoked}: upserts the projection to entitled=false. */
  @KafkaListener(
      topics = EntitlementEventSchemas.REVOKED_TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onEntitlementRevoked(ConsumerRecord<String, byte[]> record) {
    handle(record, EntitlementEventSchemas::decodeRevoked);
  }

  private void handle(
      ConsumerRecord<String, byte[]> record,
      BiFunction<UUID, byte[], EntitlementProjectedEvent> decoder) {
    UUID eventId = eventIdOf(record);
    EntitlementProjectedEvent event = decode(eventId, record, decoder);
    boolean applied = projectionService.apply(event);
    if (!applied) {
      log.debug(
          "Skipped re-delivered entitlement event eventId={} company={} module={} (already"
              + " processed)",
          eventId,
          event.companyId(),
          event.moduleKey());
    }
  }

  private static EntitlementProjectedEvent decode(
      UUID eventId,
      ConsumerRecord<String, byte[]> record,
      BiFunction<UUID, byte[], EntitlementProjectedEvent> decoder) {
    try {
      return decoder.apply(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new EventDecodeException(
          "Failed to decode entitlement event payload at "
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
          "Entitlement event record at "
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
          "Entitlement event '"
              + EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
