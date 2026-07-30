package id.co.nativeapp.barbershop.staff.messaging;

import id.co.nativeapp.barbershop.config.EventDecodeException;
import id.co.nativeapp.barbershop.config.MissingEventIdException;
import id.co.nativeapp.barbershop.staff.dto.StaffProjectedEvent;
import id.co.nativeapp.barbershop.staff.service.StaffProjectionService;
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
 * Consumes {@code EmployeeChanged} / {@code AssignmentChanged} off Kafka and applies them to the
 * local staff read model ({@link id.co.nativeapp.barbershop.staff.domain.Staff}) so the vertical
 * knows its staff (rule 2 — a cached read model, never a sync call).
 *
 * <p><strong>Raw Avro bytes.</strong> The message value is the employee-service outbox payload —
 * raw Avro bytes shipped by Debezium — so the container delivers a {@code byte[]} value; this
 * listener decodes it with {@code libs/events AvroSerde} via {@link StaffEventSchemas}. No Schema
 * Registry serde is used. NO PII is decoded — the events carry none (rule 6).
 *
 * <p><strong>Idempotency by event id, not offset.</strong> The dedupe key is the event's UUID,
 * taken from the {@code id} Kafka header the Debezium outbox event router stamps. Deduping by the
 * durable event id (not the offset) survives rebalances and compacted replay (HR-3); the dedupe +
 * projection upsert happen transactionally in {@link StaffProjectionService} / {@link
 * id.co.nativeapp.barbershop.staff.service.StaffProjectionWriter}. A record arriving WITHOUT a
 * valid {@code id} header fails closed (see {@link #eventIdOf}) so it is DLT'd.
 *
 * <p>A non-transient failure is retried a bounded number of times and then routed to {@code
 * <topic>.DLT} by the container's error handler — never an infinite in-place retry that blocks the
 * partition.
 */
@Component
public class StaffEventListener {

  /** The Kafka header carrying the durable event id (the Debezium outbox-router {@code id}). */
  public static final String EVENT_ID_HEADER = "id";

  private static final Logger log = LoggerFactory.getLogger(StaffEventListener.class);

  private final StaffProjectionService projectionService;

  public StaffEventListener(StaffProjectionService projectionService) {
    this.projectionService = projectionService;
  }

  /** Handles one {@code EmployeeChanged}: updates the staff row's active state. */
  @KafkaListener(
      topics = StaffEventSchemas.EMPLOYEE_TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onEmployeeChanged(ConsumerRecord<String, byte[]> record) {
    handle(record, StaffEventSchemas::decodeEmployee);
  }

  /** Handles one {@code AssignmentChanged}: updates the staff row's assigned org unit. */
  @KafkaListener(
      topics = StaffEventSchemas.ASSIGNMENT_TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onAssignmentChanged(ConsumerRecord<String, byte[]> record) {
    handle(record, StaffEventSchemas::decodeAssignment);
  }

  private void handle(
      ConsumerRecord<String, byte[]> record,
      BiFunction<UUID, byte[], StaffProjectedEvent> decoder) {
    UUID eventId = eventIdOf(record);
    StaffProjectedEvent event = decode(eventId, record, decoder);
    boolean applied = projectionService.apply(event);
    if (!applied) {
      log.debug(
          "Skipped re-delivered staff event eventId={} employee={} (already processed)",
          eventId,
          event.employeeId());
    }
  }

  private static StaffProjectedEvent decode(
      UUID eventId,
      ConsumerRecord<String, byte[]> record,
      BiFunction<UUID, byte[], StaffProjectedEvent> decoder) {
    try {
      return decoder.apply(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new EventDecodeException(
          "Failed to decode staff event payload at "
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
          "Staff event record at "
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
          "Staff event '"
              + EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
