package id.co.nativeapp.finance.grouptb;

import id.co.nativeapp.finance.revenue.MissingEventIdException;
import id.co.nativeapp.finance.revenue.SaleRecordedListener;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.avro.AvroRuntimeException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code TrialBalancePublished} off Kafka into the group's {@link GroupTrialBalanceLine}
 * read model (P3d SEAM 2). Mirrors the {@code GroupDefined} consumer path exactly. NOTE: SEAM 2
 * builds ONLY the consumer — the producer (emitting {@code TrialBalancePublished} at a
 * within-company close) is SEAM 3/4 and is deliberately NOT wired here.
 *
 * <p><strong>Raw Avro bytes.</strong> The message value is the producer outbox payload (raw Avro
 * bytes shipped by Debezium), so the container delivers a {@code byte[]} value (the shared {@code
 * kafkaListenerContainerFactory}); this listener decodes it with {@code libs/events AvroSerde} via
 * {@link TrialBalancePublishedSchema}. No Confluent / Schema Registry serde.
 *
 * <p><strong>Idempotency by the real event id.</strong> The dedupe key is the event's
 * globally-unique UUID from the Debezium outbox event-id header ({@code id}) — NO synthetic ids
 * this seam. The dedupe + write happen transactionally in {@link GroupTrialBalanceIngestService} /
 * {@link GroupTrialBalanceWriter}. A record without a valid {@code id} header is a contract
 * violation: the listener fails closed ({@link MissingEventIdException}) so the record is DLT'd. An
 * undecodable payload becomes a non-retryable {@link TrialBalancePublishedDecodeException} routed
 * to {@code TrialBalancePublished.DLT}.
 *
 * <p><strong>Tenant + group from the event.</strong> The handler is bound to the group's resolved
 * LEAD company AND the event's {@code group_id} inside the service so the two-GUC conjunction RLS
 * applies.
 */
@Component
public class TrialBalancePublishedListener {

  private static final Logger log = LoggerFactory.getLogger(TrialBalancePublishedListener.class);

  private final GroupTrialBalanceIngestService ingestService;

  public TrialBalancePublishedListener(GroupTrialBalanceIngestService ingestService) {
    this.ingestService = ingestService;
  }

  /** Handles one record from the {@code TrialBalancePublished} topic. */
  @KafkaListener(
      topics = TrialBalancePublishedSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onTrialBalancePublished(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    TrialBalancePublishedEvent event = decode(eventId, record);
    boolean ingested = ingestService.handle(event);
    if (!ingested) {
      log.debug(
          "Skipped re-delivered TrialBalancePublished eventId={} (already processed)", eventId);
    }
  }

  /**
   * Decodes the raw Avro value into a {@link TrialBalancePublishedEvent}, converting a KNOWN decode
   * failure into a non-retryable {@link TrialBalancePublishedDecodeException} so a poison payload
   * deterministically lands on the DLT rather than retrying forever. The catch is NARROW to exactly
   * the decode/Avro family a malformed payload raises (mirroring the {@code SaleRecorded} / {@code
   * GroupDefined} listener intent):
   *
   * <ul>
   *   <li>{@link UncheckedIOException} — {@code AvroSerde.deserialize} wrapping a truncated/corrupt
   *       binary buffer ({@code IOException});
   *   <li>{@link AvroRuntimeException} (incl. {@code AvroTypeException}) — Avro's own
   *       malformed-data/schema-mismatch failures;
   *   <li>{@link IllegalArgumentException} — a non-UUID {@code company_id}/{@code group_id}/related
   *       party id from {@code UUID.fromString}, or the explicit {@code 'lines' is not an array};
   *   <li>{@link ClassCastException} — a field whose Avro type is not the expected {@code Boolean}
   *       / {@code Long} / nested record;
   *   <li>{@link NullPointerException} — {@code .toString()} on a required field absent from the
   *       payload;
   *   <li>{@link IndexOutOfBoundsException} — an index read off the end of a truncated buffer.
   * </ul>
   *
   * <p>Any OTHER {@link RuntimeException} (e.g. a genuine logic bug introduced later) is NOT a
   * decode failure: it propagates unchanged rather than being silently relabelled a non-retryable
   * DLT decode failure, which would lose the record. {@link MissingEventIdException} is thrown
   * OUTSIDE this method (in {@link #eventIdOf}), so id-header handling is unaffected.
   */
  private static TrialBalancePublishedEvent decode(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return TrialBalancePublishedSchema.decode(eventId, record.value());
    } catch (UncheckedIOException
        | AvroRuntimeException
        | IllegalArgumentException
        | ClassCastException
        | NullPointerException
        | IndexOutOfBoundsException decodeFailure) {
      throw new TrialBalancePublishedDecodeException(
          "Failed to decode TrialBalancePublished payload at "
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
   * non-retryably to the DLT, rather than synthesising a positional id that would defeat dedupe.
   */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(SaleRecordedListener.EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new MissingEventIdException(
          "TrialBalancePublished record at "
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
          "TrialBalancePublished '"
              + SaleRecordedListener.EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
