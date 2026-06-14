package id.co.nativeapp.finance.expense;

import id.co.nativeapp.finance.revenue.MissingEventIdException;
import id.co.nativeapp.finance.revenue.SaleRecordedListener;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code ExpenseRecorded} off Kafka and posts it to the dimensional ledger as an EXPENSE
 * posting (#21) — the second event finance consumes, mirroring the {@code SaleRecorded} path.
 *
 * <p><strong>Raw Avro bytes.</strong> The message value is the producer outbox payload — raw Avro
 * bytes shipped by Debezium — so the container delivers a {@code byte[]} value (the same {@code
 * kafkaListenerContainerFactory} as {@code SaleRecorded}); this listener decodes it with {@code
 * libs/events AvroSerde} via {@link ExpenseRecordedSchema}. No Confluent / Schema Registry serde.
 *
 * <p><strong>Idempotency by event id, not offset.</strong> The dedupe key is the event's UUID,
 * taken from the Debezium outbox event-id Kafka header (header {@code id}, the same {@link
 * SaleRecordedListener#EVENT_ID_HEADER} constant). Deduping by the durable id survives rebalances
 * and compacted replay (HR-3). The dedupe + post happens transactionally in {@link
 * ExpensePostingService} / {@link ExpensePostingWriter}. A record arriving WITHOUT a valid {@code
 * id} header is a producer-side contract violation: the listener fails closed (reusing {@link
 * MissingEventIdException}) so the record is DLT'd rather than processed under a non-durable
 * synthesised id that would defeat dedupe.
 *
 * <p><strong>Tenant from the event.</strong> There is no JWT on the consumer path; the handler is
 * bound to the event's {@code company_id} inside {@link ExpensePostingService} so RLS applies.
 *
 * <p><strong>Unmappable gl_hint does NOT come here.</strong> A gl_hint with no matching {@code
 * mapping_rule} is not poison — it decodes fine and is posted to the SUSPENSE account (money stays
 * on the books). Only an undecodable payload ({@link ExpenseRecordedDecodeException}) or a missing
 * {@code id} header routes to {@code ExpenseRecorded.DLT}.
 */
@Component
public class ExpenseRecordedListener {

  private static final Logger log = LoggerFactory.getLogger(ExpenseRecordedListener.class);

  private final ExpensePostingService postingService;

  public ExpenseRecordedListener(ExpensePostingService postingService) {
    this.postingService = postingService;
  }

  /**
   * Handles one record from the {@code ExpenseRecorded} topic. Decodes the raw Avro bytes, derives
   * the event id from the header, and delegates to the idempotent posting service. A non-decodable
   * value surfaces as a non-retryable {@link ExpenseRecordedDecodeException} routed straight to the
   * DLT — money is preserved for inspection, not silently dropped, and no ledger posting is
   * written.
   */
  @KafkaListener(
      topics = ExpenseRecordedSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onExpenseRecorded(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    ExpenseRecordedEvent event = decode(eventId, record);
    boolean posted = postingService.handle(event);
    if (!posted) {
      log.debug("Skipped re-delivered ExpenseRecorded eventId={} (already processed)", eventId);
    }
  }

  private static ExpenseRecordedEvent decode(UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return ExpenseRecordedSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new ExpenseRecordedDecodeException(
          "Failed to decode ExpenseRecorded payload at "
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
   * Reads the durable event id from the {@code id} header (the same dedupe-key convention as {@code
   * SaleRecorded}). A missing or non-UUID {@code id} header is a contract violation: the consumer
   * FAILS CLOSED with {@link MissingEventIdException}, which the container's error handler treats
   * as non-retryable and routes to the DLT, rather than synthesising a positional id that would
   * defeat dedupe across a rebalance / compacted replay (double-counting money — HR-3 forbids it).
   */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(SaleRecordedListener.EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new MissingEventIdException(
          "ExpenseRecorded record at "
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
          "ExpenseRecorded '"
              + SaleRecordedListener.EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
