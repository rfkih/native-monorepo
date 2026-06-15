package id.co.nativeapp.finance.labor.messaging;

import id.co.nativeapp.finance.labor.service.LaborCostPostingService;
import id.co.nativeapp.finance.revenue.messaging.MissingEventIdException;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedListener;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code LaborCostAllocated} off Kafka and posts each (outlet, gl_account) bucket to the
 * dimensional ledger as an EXPENSE posting (#23) — the workhorse of the Phase-3 payroll consumer,
 * mirroring the {@code ExpenseRecorded} path.
 *
 * <p><strong>Raw Avro bytes.</strong> The message value is the producer outbox payload (raw Avro
 * bytes shipped by Debezium), so the container delivers a {@code byte[]} value (the same {@code
 * kafkaListenerContainerFactory}); this listener decodes it with {@code libs/events AvroSerde} via
 * {@link LaborCostAllocatedSchema}. No Confluent / Schema Registry serde.
 *
 * <p><strong>Idempotency by event id, not offset.</strong> The dedupe key is the event's UUID from
 * the Debezium outbox event-id header ({@code id}, the {@link SaleRecordedListener#EVENT_ID_HEADER}
 * constant). The dedupe + post happens transactionally in {@link LaborCostPostingService} / {@link
 * LaborCostPostingWriter}. A record without a valid {@code id} header is a contract violation: the
 * listener fails closed ({@link MissingEventIdException}) so the record is DLT'd.
 *
 * <p><strong>Tenant from the event.</strong> There is no JWT on the consumer path; the handler is
 * bound to the event's {@code company_id} inside the service so RLS applies.
 *
 * <p><strong>Unmappable gl_account does NOT come here.</strong> A labor hint with no matching
 * {@code mapping_rule} is not poison — it decodes fine and is posted to the suspense account (money
 * stays on the books). Only an undecodable payload ({@link LaborCostAllocatedDecodeException}) or a
 * missing {@code id} header routes to {@code LaborCostAllocated.DLT}.
 */
@Component
public class LaborCostAllocatedListener {

  private static final Logger log = LoggerFactory.getLogger(LaborCostAllocatedListener.class);

  private final LaborCostPostingService postingService;

  public LaborCostAllocatedListener(LaborCostPostingService postingService) {
    this.postingService = postingService;
  }

  /**
   * Handles one record from the {@code LaborCostAllocated} topic. Decodes the raw Avro bytes,
   * derives the event id from the header, and delegates to the idempotent posting service. A
   * non-decodable value surfaces as a non-retryable {@link LaborCostAllocatedDecodeException}
   * routed straight to the DLT — money is preserved for inspection, not silently dropped, and no
   * posting is written.
   */
  @KafkaListener(
      topics = LaborCostAllocatedSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onLaborCostAllocated(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    LaborCostAllocatedEvent event = decode(eventId, record);
    boolean posted = postingService.handle(event);
    if (!posted) {
      log.debug("Skipped re-delivered LaborCostAllocated eventId={} (already processed)", eventId);
    }
  }

  private static LaborCostAllocatedEvent decode(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return LaborCostAllocatedSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new LaborCostAllocatedDecodeException(
          "Failed to decode LaborCostAllocated payload at "
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
   * Reads the durable event id from the {@code id} header. A missing or non-UUID header is a
   * contract violation: the consumer FAILS CLOSED with {@link MissingEventIdException}, routed
   * non-retryably to the DLT, rather than synthesising a positional id that would defeat dedupe
   * across a rebalance / compacted replay (double-counting money — HR-3 forbids it).
   */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(SaleRecordedListener.EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new MissingEventIdException(
          "LaborCostAllocated record at "
              + record.topic()
              + "-"
              + record.partition()
              + "@"
              + record.offset()
              + " has no '"
              + SaleRecordedListener.EVENT_ID_HEADER
              + "' header; routing to DLT (fail closed)");
    }
    String raw = new String(header.value(), StandardCharsets.UTF_8).strip();
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException badId) {
      throw new MissingEventIdException(
          "LaborCostAllocated '"
              + SaleRecordedListener.EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
