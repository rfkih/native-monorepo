package id.co.nativeapp.carwash.outletref.messaging;

import id.co.nativeapp.carwash.config.EventDecodeException;
import id.co.nativeapp.carwash.config.MissingEventIdException;
import id.co.nativeapp.carwash.outletref.service.UserOutletAssignmentRefService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code UserOutletAssignmentChanged} off Kafka and applies events to carwash-service's
 * local {@code user_outlet_assignment_ref} read model — the foundation for server-side
 * outlet-scoping enforcement on the future ticket write paths (ported from restaurant-service's
 * {@code outletref} feature).
 *
 * <p><strong>Shared decode/missing-id exceptions.</strong> Unlike restaurant-service (which
 * declares feature-local {@code UserOutletAssignmentDecodeException} / {@code
 * UserOutletAssignmentMissingEventIdException} types), this listener reuses carwash-service's
 * ALREADY-SHARED {@link EventDecodeException} / {@link MissingEventIdException} — the same types
 * {@code EntitlementEventListener} and {@code StaffEventListener} throw. carwash's single {@code
 * KafkaConfig.kafkaErrorHandler} already registers both as non-retryable, so this listener needs NO
 * change to that wiring; a feature-local pair would only duplicate what the shared types already
 * do. This mirrors carwash's OWN established convention over a verbatim restaurant-service copy —
 * see the port task's deviation notes.
 *
 * <p><strong>Raw Avro bytes.</strong> The message value is the org-service outbox payload — raw
 * Avro bytes shipped by Debezium (base64-transported and decoded by the container's {@code
 * Base64ByteArrayDeserializer}) — so the container delivers a {@code byte[]} value; this listener
 * decodes it with {@code libs/events AvroSerde} via {@link UserOutletAssignmentChangedSchemas}. No
 * Schema Registry serde is used, consistent with how the outbox stores events and with every other
 * consumer in this fleet.
 *
 * <p><strong>Idempotency by event id, not offset.</strong> The dedupe key is the event's UUID taken
 * from the {@code id} Kafka header the Debezium outbox event router stamps. A record arriving
 * WITHOUT a valid {@code id} header is a producer-side contract violation: the listener fails
 * closed ({@link #eventIdOf}) so the record is DLT'd. Deduping by the durable event id (not the
 * offset) survives rebalances and compacted replay; the dedupe + upsert happen transactionally in
 * {@link id.co.nativeapp.carwash.outletref.service.UserOutletAssignmentRefWriter}.
 *
 * <p><strong>Tenant from the event.</strong> There is no JWT on the consumer path; the handler is
 * bound to the event's {@code company_id} inside {@link UserOutletAssignmentRefService} so RLS
 * applies on the {@code user_outlet_assignment_ref} upsert (rule 5).
 *
 * <p>A non-transient failure is retried a bounded number of times and then routed to {@code
 * UserOutletAssignmentChanged.DLT} by the shared container error handler — never an infinite
 * in-place retry.
 */
@Component
public class UserOutletAssignmentListener {

  /**
   * The Kafka header carrying the durable event id (the Debezium outbox-router {@code id} header).
   */
  public static final String EVENT_ID_HEADER = "id";

  private static final Logger log = LoggerFactory.getLogger(UserOutletAssignmentListener.class);

  private final UserOutletAssignmentRefService refService;

  public UserOutletAssignmentListener(UserOutletAssignmentRefService refService) {
    this.refService = refService;
  }

  /**
   * Handles one {@code UserOutletAssignmentChanged}: upserts the assignment state into {@code
   * user_outlet_assignment_ref} (active or inactive).
   */
  @KafkaListener(
      topics = UserOutletAssignmentChangedSchemas.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onUserOutletAssignmentChanged(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    UserOutletAssignmentEvent event = decodePayload(eventId, record);
    boolean applied = refService.apply(event);
    if (!applied) {
      log.debug(
          "Skipped re-delivered UserOutletAssignmentChanged eventId={} assignmentId={}",
          eventId,
          event.assignmentId());
    }
  }

  /**
   * Decodes the raw Avro value, converting ANY decode failure into a non-retryable {@link
   * EventDecodeException} so a poison payload deterministically lands on the DLT rather than
   * retrying forever.
   */
  private static UserOutletAssignmentEvent decodePayload(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return UserOutletAssignmentChangedSchemas.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new EventDecodeException(
          "Failed to decode UserOutletAssignmentChanged payload at "
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
   * UserOutletAssignmentChanged.DLT}.
   */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new MissingEventIdException(
          "UserOutletAssignmentChanged record at "
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
          "UserOutletAssignmentChanged '"
              + EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
