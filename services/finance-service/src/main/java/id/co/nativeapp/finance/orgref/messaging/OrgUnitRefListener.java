package id.co.nativeapp.finance.orgref.messaging;

import id.co.nativeapp.finance.orgref.service.OrgUnitRefService;
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
 * Consumes {@code OrgUnitCreated} / {@code OrgUnitChanged} off Kafka and applies them to
 * finance-service's local {@code org_unit_ref} read model (Phase 2 of the outlet-scoping
 * increment). The read model backs the {@code outletName} field on {@code GET /api/v1/pnl/outlets}:
 * each outlet revenue row is LEFT-JOINed against {@code org_unit_ref} to resolve its display name
 * without a synchronous call to org-service (rule 2 — a locally cached read model).
 *
 * <p><strong>Raw Avro bytes.</strong> The message value is the org-service outbox payload — raw
 * Avro bytes shipped by Debezium — so the container delivers a {@code byte[]} value; this listener
 * decodes it with {@code libs/events AvroSerde} via {@link OrgUnitRefSchemas}. No Schema Registry
 * serde is used, consistent with how the outbox stores events and with all other finance consumers.
 *
 * <p><strong>Idempotency by event id, not offset.</strong> The dedupe key is the event's UUID,
 * taken from the {@code id} Kafka header the Debezium outbox event router stamps. Deduping by the
 * durable event id (not the offset) survives rebalances and compacted replay (HR-3); the dedupe +
 * projection upsert happen transactionally in {@link OrgUnitRefService} / {@link
 * id.co.nativeapp.finance.orgref.service.OrgUnitRefWriter}. A record arriving WITHOUT a valid
 * {@code id} header is a producer-side contract violation: the listener fails closed (see {@link
 * #eventIdOf}) so the record is DLT'd.
 *
 * <p><strong>Tenant from the event.</strong> There is no JWT on the consumer path; the handler is
 * bound to the event's {@code company_id} inside {@link OrgUnitRefService} so RLS applies on the
 * {@code org_unit_ref} upsert (rule 5).
 *
 * <p>A non-transient failure is retried a bounded number of times and then routed to {@code
 * <topic>.DLT} by the container's error handler — never an infinite in-place retry.
 */
@Component
public class OrgUnitRefListener {

  /**
   * The Kafka header carrying the durable event id (the Debezium outbox-router {@code id} header).
   */
  public static final String EVENT_ID_HEADER = "id";

  private static final Logger log = LoggerFactory.getLogger(OrgUnitRefListener.class);

  private final OrgUnitRefService orgUnitRefService;

  public OrgUnitRefListener(OrgUnitRefService orgUnitRefService) {
    this.orgUnitRefService = orgUnitRefService;
  }

  /**
   * Handles one {@code OrgUnitCreated}: upserts the org unit into {@code org_unit_ref} as active,
   * with its name, type, and parent.
   */
  @KafkaListener(
      topics = OrgUnitRefSchemas.CREATED_TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onOrgUnitCreated(ConsumerRecord<String, byte[]> record) {
    handle(record, OrgUnitRefSchemas::decodeCreated);
  }

  /**
   * Handles one {@code OrgUnitChanged}: upserts the org unit's new state (name, parent, active
   * flag) into {@code org_unit_ref}. The {@code active} field in the event payload drives
   * activation/deactivation uniformly — the consumer does not branch on {@code change_kind} (the
   * catalog documents this as the idempotency strategy for changed events).
   */
  @KafkaListener(
      topics = OrgUnitRefSchemas.CHANGED_TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onOrgUnitChanged(ConsumerRecord<String, byte[]> record) {
    handle(record, OrgUnitRefSchemas::decodeChanged);
  }

  private void handle(
      ConsumerRecord<String, byte[]> record, BiFunction<UUID, byte[], OrgUnitRefEvent> decoder) {
    UUID eventId = eventIdOf(record);
    OrgUnitRefEvent event = decode(eventId, record, decoder);
    boolean applied = orgUnitRefService.apply(event);
    if (!applied) {
      log.debug(
          "Skipped re-delivered org-unit event eventId={} orgUnitId={} (already processed)",
          eventId,
          event.orgUnitId());
    }
  }

  /**
   * Decodes the raw Avro value, converting ANY decode failure into a non-retryable {@link
   * OrgUnitRefDecodeException} so a poison payload deterministically lands on the DLT rather than
   * retrying forever.
   */
  private static OrgUnitRefEvent decode(
      UUID eventId,
      ConsumerRecord<String, byte[]> record,
      BiFunction<UUID, byte[], OrgUnitRefEvent> decoder) {
    try {
      return decoder.apply(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new OrgUnitRefDecodeException(
          "Failed to decode org-unit event payload at "
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
   * is a contract violation: we FAIL CLOSED — throwing {@link OrgUnitRefMissingEventIdException},
   * which the container's error handler treats as non-retryable and routes straight to {@code
   * <topic>.DLT}. Synthesising a positional UUID would defeat the {@code ProcessedEventStore}
   * dedupe after a rebalance / compacted replay, so we never do it.
   */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new OrgUnitRefMissingEventIdException(
          "Org-unit event record at "
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
      throw new OrgUnitRefMissingEventIdException(
          "Org-unit event '"
              + EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
