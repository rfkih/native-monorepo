package id.co.nativeapp.employee.payroll.messaging;

import id.co.nativeapp.employee.payroll.dto.PeriodSealedProjectedEvent;
import id.co.nativeapp.employee.payroll.service.PeriodSealProjectionService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code PeriodSealed} off Kafka and projects it into {@link PeriodSeal} — the
 * completeness gate (ARCHITECTURE.md §4). Raw Avro bytes via {@link PeriodSealedSchema} (no Schema
 * Registry serde); idempotent by the event UUID from the {@code id} header (fail-closed to the DLT
 * on a missing/non-UUID id, exactly {@code OrgUnitEventListener}'s pattern). Tenant comes from the
 * event's {@code company_id} (in the payload) inside {@link PeriodSealProjectionService}.
 */
@Component
public class PeriodSealedListener {

  /** The Kafka header carrying the durable event id (the Debezium outbox-router {@code id}). */
  public static final String EVENT_ID_HEADER = "id";

  private static final Logger log = LoggerFactory.getLogger(PeriodSealedListener.class);

  private final PeriodSealProjectionService projectionService;

  public PeriodSealedListener(PeriodSealProjectionService projectionService) {
    this.projectionService = projectionService;
  }

  /** Handles one {@code PeriodSealed}: upserts the {@code period_seal} projection. */
  @KafkaListener(
      topics = PeriodSealedSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onPeriodSealed(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    PeriodSealedProjectedEvent event = decode(eventId, record);
    boolean applied = projectionService.apply(event);
    if (!applied) {
      log.debug(
          "Skipped re-delivered PeriodSealed eventId={} businessId={} period={} (already processed)",
          eventId,
          event.businessId(),
          event.period());
    }
  }

  private static PeriodSealedProjectedEvent decode(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return PeriodSealedSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new PayrollEventDecodeException(
          "Failed to decode PeriodSealed payload at "
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
      throw new MissingPayrollEventIdException(
          "PeriodSealed record at "
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
      throw new MissingPayrollEventIdException(
          "PeriodSealed '"
              + EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
