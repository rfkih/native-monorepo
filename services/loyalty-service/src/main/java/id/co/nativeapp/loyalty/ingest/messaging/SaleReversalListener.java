package id.co.nativeapp.loyalty.ingest.messaging;

import id.co.nativeapp.loyalty.config.EventDecodeException;
import id.co.nativeapp.loyalty.config.MissingEventIdException;
import id.co.nativeapp.loyalty.ingest.dto.SaleReversalFact;
import id.co.nativeapp.loyalty.ingest.service.SaleReversalService;
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
 * Consumes {@code SaleVoided} AND {@code SaleRefunded} off Kafka (restaurant-service's producers —
 * ADR 0006) and REVERSES the sale's earn/redeem entries by {@code sale_id}, idempotently (ADR
 * 0027).
 *
 * <p><strong>One listener class for two events (a deliberate consolidation, documented).</strong>
 * The task describes these as "{@code SaleVoidedListener}/{@code SaleRefundedListener}" — both
 * trigger the IDENTICAL behaviour (full-reversal of the sale's loyalty facts by {@code sale_id};
 * loyalty stored only sale-level facts, so even a partial refund reverses the FULL loyalty impact,
 * mirroring finance's posture). Rather than duplicate that behaviour across two near-identical
 * classes, this mirrors {@code StaffEventListener}'s house precedent (one class, two
 * {@code @KafkaListener} methods, one shared decode+apply path) — see {@link SaleReversalService} /
 * {@link id.co.nativeapp.loyalty.ingest.service.SaleReversalWriter SaleReversalWriter} for the
 * shared reversal logic.
 *
 * <p>Same raw-Avro-bytes / event-id-header dedupe / bounded-retry-then-DLT shape as {@link
 * SaleRecordedListener} — see its class javadoc for the full rationale.
 */
@Component
public class SaleReversalListener {

  public static final String EVENT_ID_HEADER = "id";

  private static final Logger log = LoggerFactory.getLogger(SaleReversalListener.class);

  private final SaleReversalService saleReversalService;

  public SaleReversalListener(SaleReversalService saleReversalService) {
    this.saleReversalService = saleReversalService;
  }

  /** Handles one {@code SaleVoided}. */
  @KafkaListener(
      topics = SaleReversalConsumerSchema.VOIDED_TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onSaleVoided(ConsumerRecord<String, byte[]> record) {
    handle(record, SaleReversalConsumerSchema::decodeVoided);
  }

  /** Handles one {@code SaleRefunded}. */
  @KafkaListener(
      topics = SaleReversalConsumerSchema.REFUNDED_TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onSaleRefunded(ConsumerRecord<String, byte[]> record) {
    handle(record, SaleReversalConsumerSchema::decodeRefunded);
  }

  private void handle(
      ConsumerRecord<String, byte[]> record, BiFunction<UUID, byte[], SaleReversalFact> decoder) {
    UUID eventId = eventIdOf(record);
    SaleReversalFact fact = decode(eventId, record, decoder);
    boolean applied = saleReversalService.apply(fact);
    if (!applied) {
      log.debug(
          "Skipped re-delivered {} eventId={} saleId={} (already processed)",
          fact.kind(),
          eventId,
          fact.saleId());
    }
  }

  private static SaleReversalFact decode(
      UUID eventId,
      ConsumerRecord<String, byte[]> record,
      BiFunction<UUID, byte[], SaleReversalFact> decoder) {
    try {
      return decoder.apply(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new EventDecodeException(
          "Failed to decode sale-reversal payload at "
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
          "Sale-reversal record at "
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
          "Sale-reversal '" + EVENT_ID_HEADER + "' header is not a UUID (" + raw + ")", badId);
    }
  }
}
