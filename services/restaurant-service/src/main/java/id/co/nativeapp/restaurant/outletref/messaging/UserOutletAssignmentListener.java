package id.co.nativeapp.restaurant.outletref.messaging;

import id.co.nativeapp.restaurant.outletref.service.UserOutletAssignmentRefService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code UserOutletAssignmentChanged} off Kafka and applies events to restaurant-service's
 * local {@code user_outlet_assignment_ref} read model (Phase 5 — server-side outlet-scoping
 * enforcement). The read model backs the {@link
 * id.co.nativeapp.restaurant.order.service.OrderWriter} outlet-scoping guard: a cashier must have
 * an active assignment row for the requested outlet before their order is accepted.
 *
 * <p><strong>Raw Avro bytes.</strong> The message value is the org-service outbox payload — raw
 * Avro bytes shipped by Debezium (base64-transported and decoded by the container's {@code
 * Base64ByteArrayDeserializer}) — so the container delivers a {@code byte[]} value; this listener
 * decodes it with {@code libs/events AvroSerde} via {@link UserOutletAssignmentChangedSchemas}. No
 * Schema Registry serde is used, consistent with how the outbox stores events and with all other
 * consumers in this fleet.
 *
 * <p><strong>Idempotency by event id, not offset.</strong> The dedupe key is the event's UUID taken
 * from the {@code id} Kafka header the Debezium outbox event router stamps. A record arriving
 * WITHOUT a valid {@code id} header is a producer-side contract violation: the listener fails closed
 * ({@link #eventIdOf}) so the record is DLT'd. Deduping by the durable event id (not the offset)
 * survives rebalances and compacted replay; the dedupe + upsert happen transactionally in {@link
 * id.co.nativeapp.restaurant.outletref.service.UserOutletAssignmentRefWriter}.
 *
 * <p><strong>Tenant from the event.</strong> There is no JWT on the consumer path; the handler is
 * bound to the event's {@code company_id} inside {@link UserOutletAssignmentRefService} so RLS
 * applies on the {@code user_outlet_assignment_ref} upsert (rule 5).
 *
 * <p>A non-transient failure is retried a bounded number of times and then routed to {@code
 * UserOutletAssignmentChanged.DLT} by the container's error handler — never an infinite in-place
 * retry.
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
   * UserOutletAssignmentDecodeException} so a poison payload deterministically lands on the DLT
   * rather than retrying forever.
   */
  private static UserOutletAssignmentEvent decodePayload(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return UserOutletAssignmentChangedSchemas.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new UserOutletAssignmentDecodeException(
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
   * is a contract violation: we FAIL CLOSED — throwing {@link
   * UserOutletAssignmentMissingEventIdException}, which the container's error handler treats as
   * non-retryable and routes straight to {@code UserOutletAssignmentChanged.DLT}.
   */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new UserOutletAssignmentMissingEventIdException(
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
      throw new UserOutletAssignmentMissingEventIdException(
          "UserOutletAssignmentChanged '"
              + EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
