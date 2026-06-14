package id.co.nativeapp.employee.payroll;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes carwash-service's {@code MetricPublished} off Kafka and projects it into {@link
 * MetricInput} — the variable-pay (commission) inputs {@code PER_METRIC_UNIT} earning rules read
 * (never a sync call — rule 2). Raw Avro bytes via {@link MetricPublishedConsumerSchema} (no Schema
 * Registry serde); idempotent by the event UUID from the {@code id} header (fail-closed to DLT).
 *
 * <p>The {@code MetricPublished} payload carries NO {@code company_id} (it is an outbox-row column
 * the Debezium outbox router stamps as a Kafka header), so the tenant is read from the {@code
 * company_id} header — also fail-closed to the DLT if absent.
 */
@Component
public class MetricPublishedListener {

  /** The Kafka header carrying the durable event id (the Debezium outbox-router {@code id}). */
  public static final String EVENT_ID_HEADER = "id";

  /** The Kafka header carrying the owning tenant (the outbox-row {@code company_id} column). */
  public static final String COMPANY_ID_HEADER = "company_id";

  private static final Logger log = LoggerFactory.getLogger(MetricPublishedListener.class);

  private final MetricInputProjectionService projectionService;

  public MetricPublishedListener(MetricInputProjectionService projectionService) {
    this.projectionService = projectionService;
  }

  /** Handles one {@code MetricPublished}: upserts the {@code metric_input} projection. */
  @KafkaListener(
      topics = MetricPublishedConsumerSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onMetricPublished(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    String companyId = companyIdOf(record);
    MetricProjectedEvent event = decode(eventId, companyId, record);
    boolean applied = projectionService.apply(event);
    if (!applied) {
      log.debug(
          "Skipped re-delivered MetricPublished eventId={} metricKey={} period={} (already"
              + " processed)",
          eventId,
          event.metricKey(),
          event.period());
    }
  }

  private static MetricProjectedEvent decode(
      UUID eventId, String companyId, ConsumerRecord<String, byte[]> record) {
    try {
      return MetricPublishedConsumerSchema.decode(eventId, companyId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new PayrollEventDecodeException(
          "Failed to decode MetricPublished payload at "
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
    String raw = requireHeader(record, EVENT_ID_HEADER);
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException badId) {
      throw new MissingPayrollEventIdException(
          "MetricPublished '"
              + EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }

  private static String companyIdOf(ConsumerRecord<String, byte[]> record) {
    return requireHeader(record, COMPANY_ID_HEADER);
  }

  private static String requireHeader(ConsumerRecord<String, byte[]> record, String name) {
    Header header = record.headers().lastHeader(name);
    if (header == null || header.value() == null) {
      throw new MissingPayrollEventIdException(
          "MetricPublished record at "
              + record.topic()
              + "-"
              + record.partition()
              + "@"
              + record.offset()
              + " has no '"
              + name
              + "' header; routing to DLT (fail closed)");
    }
    return new String(header.value(), StandardCharsets.UTF_8).strip();
  }
}
