package id.co.nativeapp.finance.giftcard.messaging;

import id.co.nativeapp.finance.giftcard.service.GiftCardPostingService;
import id.co.nativeapp.finance.revenue.messaging.MissingEventIdException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code GiftCardSold} off Kafka and posts the gift-card LIABILITY entry to the GL (ADR
 * 0027, Phase 4 of the POS-parity program).
 *
 * <p><strong>Raw Avro bytes.</strong> The message value is the producer outbox payload — raw Avro
 * bytes shipped by Debezium — so the container delivers a {@code byte[]} value (configured in
 * {@code application.yml}); this listener decodes it with {@code libs/events AvroSerde} via {@link
 * GiftCardSoldSchema}. No Confluent / Schema Registry serde is used, consistent with how the outbox
 * stores events. Mirrors {@code SaleRecordedListener}/{@code SaleVoidedListener}.
 *
 * <p><strong>Idempotency by event id, not offset.</strong> The dedupe key is the event's UUID,
 * taken from the outbox event-id Kafka header that the Debezium outbox event router stamps (header
 * {@code id}). Deduping by the durable event id (rather than the Kafka offset) survives rebalances
 * and compacted replay (HR-3). The actual dedupe + post happens transactionally in {@link
 * GiftCardPostingService} / {@link id.co.nativeapp.finance.giftcard.service.GiftCardPostingWriter}.
 * A record arriving WITHOUT a valid {@code id} header is a producer-side contract violation: the
 * listener fails closed (see {@link #eventIdOf}) so the record is DLT'd rather than processed under
 * a non-durable synthesised id that would defeat dedupe and risk double-counting money.
 *
 * <p><strong>Tenant from the event.</strong> There is no JWT on the consumer path; the handler is
 * bound to the event's {@code company_id} inside {@link GiftCardPostingService} so RLS applies.
 *
 * <p>A non-transient failure is retried a bounded number of times and then routed to {@code
 * GiftCardSold.DLT} by the container's error handler ({@link
 * id.co.nativeapp.finance.config.KafkaConfig}) — never an infinite in-place retry that blocks the
 * partition.
 */
@Component
public class GiftCardSoldListener {

  /**
   * The Kafka header carrying the durable event id. The Debezium outbox event router exposes the
   * outbox row's {@code id} as a message header named {@code id} by default; that UUID is the
   * dedupe key.
   */
  public static final String EVENT_ID_HEADER = "id";

  private static final Logger log = LoggerFactory.getLogger(GiftCardSoldListener.class);

  private final GiftCardPostingService postingService;

  public GiftCardSoldListener(GiftCardPostingService postingService) {
    this.postingService = postingService;
  }

  /**
   * Handles one record from the {@code GiftCardSold} topic. Decodes the raw Avro bytes, derives the
   * event id from the header, and delegates to the idempotent posting service.
   *
   * <p>A record whose value is NOT a valid {@code GiftCardSold} Avro payload (garbage bytes, a
   * truncated message, a wrong schema) is poison: it can never decode, so retrying in place is
   * futile and would block the partition. We surface it as a non-transient {@link
   * GiftCardSoldDecodeException} which the container's error handler treats as non-retryable and
   * routes straight to {@code GiftCardSold.DLT} — money is preserved for inspection on the DLT, not
   * silently dropped, and no liability posting is written.
   */
  @KafkaListener(
      topics = GiftCardSoldSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onGiftCardSold(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    GiftCardSoldEvent event = decode(eventId, record);
    boolean posted = postingService.handle(event);
    if (!posted) {
      log.debug("Skipped re-delivered GiftCardSold eventId={} (already processed)", eventId);
    }
  }

  /**
   * Decodes the raw Avro value into a {@link GiftCardSoldEvent}, converting ANY decode failure
   * (Avro's {@code UncheckedIOException}, {@code AvroRuntimeException}, an index-out-of-bounds from
   * a truncated buffer, etc.) into a non-retryable {@link GiftCardSoldDecodeException} so a poison
   * payload deterministically lands on the DLT rather than retrying forever.
   */
  private static GiftCardSoldEvent decode(UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return GiftCardSoldSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new GiftCardSoldDecodeException(
          "Failed to decode GiftCardSold payload at "
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
   * is a <strong>contract violation</strong>, not a normal case: the Debezium outbox event router
   * always stamps the outbox row's {@code id} (a UUID) as this header, so its absence means a
   * misconfigured producer. We FAIL CLOSED — throwing {@link MissingEventIdException}, which the
   * container's error handler treats as non-retryable and routes straight to {@code
   * GiftCardSold.DLT}. Synthesising a positional UUID from topic-partition-offset would defeat
   * dedupe after a rebalance / compacted replay / repartition, so money could be double-counted —
   * exactly what HR-3 forbids.
   */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new MissingEventIdException(
          "GiftCardSold record at "
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
          "GiftCardSold '"
              + EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
