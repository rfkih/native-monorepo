package id.co.nativeapp.loyalty.ingest.messaging;

import id.co.nativeapp.loyalty.config.EventDecodeException;
import id.co.nativeapp.loyalty.config.MissingEventIdException;
import id.co.nativeapp.loyalty.ingest.dto.SaleRecordedFact;
import id.co.nativeapp.loyalty.ingest.service.SaleIngestService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code SaleRecorded} off Kafka (EVERY vertical's producer — restaurant/carwash/
 * barbershop, ADR 0003 single shared schema) and applies its loyalty/gift-card facts idempotently
 * (ADR 0027): points EARN (when {@code loyalty_member_id} is present and an effective earn rule
 * exists) and/or points REDEEM and/or gift-card REDEEM (when the corresponding fields are present).
 * A sale with none of the five Phase-4 fields populated is a clean, cheap no-op (still marked
 * processed, so a re-delivery is equally cheap).
 *
 * <p><strong>Raw Avro bytes.</strong> The message value is a vertical's outbox payload — raw Avro
 * bytes shipped by Debezium — so the container delivers a {@code byte[]} value; this listener
 * decodes it with {@code libs/events AvroSerde} via {@link SaleRecordedConsumerSchema} (the SHARED
 * {@code libs/contracts} schema, ADR 0003). No Schema Registry serde is used.
 *
 * <p><strong>Idempotency by event id, not offset.</strong> The dedupe key is the event's UUID,
 * taken from the {@code id} Kafka header the Debezium outbox event router stamps. A record arriving
 * WITHOUT a valid {@code id} header fails closed (see {@link #eventIdOf}) so it is DLT'd.
 *
 * <p>A non-transient failure is retried a bounded number of times and then routed to {@code
 * SaleRecorded.DLT} by the container's error handler — never an infinite in-place retry that blocks
 * the partition.
 */
@Component
public class SaleRecordedListener {

  /** The Kafka header carrying the durable event id (the Debezium outbox-router {@code id}). */
  public static final String EVENT_ID_HEADER = "id";

  private static final Logger log = LoggerFactory.getLogger(SaleRecordedListener.class);

  private final SaleIngestService saleIngestService;

  public SaleRecordedListener(SaleIngestService saleIngestService) {
    this.saleIngestService = saleIngestService;
  }

  @KafkaListener(
      topics = SaleRecordedConsumerSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onSaleRecorded(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    SaleRecordedFact fact = decode(eventId, record);
    boolean applied = saleIngestService.apply(fact);
    if (!applied) {
      log.debug(
          "Skipped re-delivered SaleRecorded eventId={} saleId={} (already processed)",
          eventId,
          fact.saleId());
    }
  }

  private static SaleRecordedFact decode(UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return SaleRecordedConsumerSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new EventDecodeException(
          "Failed to decode SaleRecorded payload at "
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
      throw new MissingEventIdException(
          "SaleRecorded record at "
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
          "SaleRecorded '" + EVENT_ID_HEADER + "' header is not a UUID (" + raw + ")", badId);
    }
  }
}
