package id.co.nativeapp.entitlement.entitlement.messaging;

import id.co.nativeapp.entitlement.entitlement.service.EntitlementService;
import id.co.nativeapp.entitlement.entitlement.service.EntitlementWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code CompanyCreated} off Kafka and grants the new company its DEFAULT entitlements.
 *
 * <p><strong>Raw Avro bytes.</strong> The message value is the producer outbox payload — raw Avro
 * bytes shipped by Debezium — so the container delivers a {@code byte[]} value (configured in
 * {@code application.yml}); this listener decodes it with {@code libs/events AvroSerde} via {@link
 * CompanyCreatedSchema}. No Confluent / Schema Registry serde is used, consistent with how the
 * outbox stores events and with finance-service's {@code SaleRecordedListener}.
 *
 * <p><strong>Idempotency by event id, not offset.</strong> The dedupe key is the event's UUID,
 * taken from the {@code id} Kafka header the Debezium outbox event router stamps. Deduping by the
 * durable event id (rather than the Kafka offset) survives rebalances and compacted replay (HR-3);
 * the actual dedupe + grants happen transactionally in {@link EntitlementService} / {@link
 * EntitlementWriter}. A record arriving WITHOUT a valid {@code id} header is a producer-side
 * contract violation: the listener fails closed (see {@link #eventIdOf}) so the record is DLT'd
 * rather than processed under a synthesised id that would defeat dedupe and risk double-granting
 * defaults.
 *
 * <p><strong>Tenant from the event.</strong> There is no JWT on the consumer path; the handler
 * binds the event's {@code company_id} inside {@link EntitlementService} so RLS applies on the
 * default grants for the brand-new tenant.
 *
 * <p>A non-transient failure is retried a bounded number of times and then routed to {@code
 * CompanyCreated.DLT} by the container's error handler ({@link
 * id.co.nativeapp.entitlement.config.KafkaConfig}) — never an infinite in-place retry that blocks
 * the partition.
 */
@Component
public class CompanyCreatedListener {

  /**
   * The Kafka header carrying the durable event id. The Debezium outbox event router exposes the
   * outbox row's {@code id} as a message header named {@code id} by default; that UUID is the
   * dedupe key.
   */
  public static final String EVENT_ID_HEADER = "id";

  private static final Logger log = LoggerFactory.getLogger(CompanyCreatedListener.class);

  private final EntitlementService entitlementService;

  public CompanyCreatedListener(EntitlementService entitlementService) {
    this.entitlementService = entitlementService;
  }

  /**
   * Handles one record from the {@code CompanyCreated} topic. Decodes the raw Avro bytes, derives
   * the event id from the header, and delegates to the idempotent default-grant service.
   */
  @KafkaListener(
      topics = CompanyCreatedSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onCompanyCreated(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    CompanyCreatedEvent event = decode(eventId, record);
    boolean granted = entitlementService.grantDefaultsFor(event);
    if (!granted) {
      log.debug("Skipped re-delivered CompanyCreated eventId={} (already processed)", eventId);
    }
  }

  /**
   * Decodes the raw Avro value into a {@link CompanyCreatedEvent}, converting ANY decode failure
   * into a non-retryable {@link CompanyCreatedDecodeException} so a poison payload
   * deterministically lands on the DLT rather than retrying forever.
   */
  private static CompanyCreatedEvent decode(UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return CompanyCreatedSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new CompanyCreatedDecodeException(
          "Failed to decode CompanyCreated payload at "
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
   * CompanyCreated.DLT}. Synthesising a positional UUID would defeat dedupe after a rebalance /
   * compacted replay, so a company's defaults could be double-granted — exactly what HR-3 forbids.
   */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new MissingEventIdException(
          "CompanyCreated record at "
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
          "CompanyCreated '"
              + EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
