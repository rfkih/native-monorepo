package id.co.nativeapp.finance.empexpense.messaging;

import id.co.nativeapp.finance.empexpense.service.ExpenseClaimPostingService;
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
 * Consumes {@code ExpenseClaimApproved} off Kafka and posts it to the dimensional ledger as an
 * EXPENSE posting (ADR 0030, expense-claims program) — mirrors {@code ExpenseRecordedListener}.
 *
 * <p><strong>Raw Avro bytes.</strong> The message value is the producer outbox payload — raw Avro
 * bytes shipped by Debezium — so the container delivers a {@code byte[]} value (the same {@code
 * kafkaListenerContainerFactory} as {@code SaleRecorded}/{@code ExpenseRecorded}); this listener
 * decodes it with {@code libs/events AvroSerde} via {@link ExpenseClaimApprovedSchema}.
 *
 * <p><strong>Idempotency by event id, not offset.</strong> The dedupe key is the event's UUID,
 * taken from the Debezium outbox event-id Kafka header (header {@code id}, {@link
 * SaleRecordedListener#EVENT_ID_HEADER}). A record arriving WITHOUT a valid {@code id} header fails
 * closed with {@link MissingEventIdException} so it is DLT'd rather than processed under a
 * non-durable synthesised id that would defeat dedupe.
 *
 * <p><strong>Tenant from the event.</strong> There is no JWT on the consumer path; the handler is
 * bound to the event's {@code company_id} inside {@link ExpenseClaimPostingService}.
 *
 * <p><strong>Unmappable gl_hint does NOT come here.</strong> A gl_hint with no matching {@code
 * mapping_rule} is not poison — it decodes fine and is posted to the SUSPENSE account. Only an
 * undecodable payload ({@link ExpenseClaimApprovedDecodeException}) or a missing {@code id} header
 * routes to {@code ExpenseClaimApproved.DLT}.
 */
@Component
public class ExpenseClaimApprovedListener {

  private static final Logger log = LoggerFactory.getLogger(ExpenseClaimApprovedListener.class);

  private final ExpenseClaimPostingService postingService;

  public ExpenseClaimApprovedListener(ExpenseClaimPostingService postingService) {
    this.postingService = postingService;
  }

  /**
   * Handles one record from the {@code ExpenseClaimApproved} topic. Decodes the raw Avro bytes,
   * derives the event id from the header, and delegates to the idempotent posting service. A
   * non-decodable value surfaces as a non-retryable {@link ExpenseClaimApprovedDecodeException}
   * routed straight to the DLT — money is preserved for inspection, not silently dropped.
   */
  @KafkaListener(
      topics = ExpenseClaimApprovedSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onExpenseClaimApproved(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    ExpenseClaimApprovedEvent event = decode(eventId, record);
    boolean posted = postingService.handleApproved(event);
    if (!posted) {
      log.debug(
          "Skipped re-delivered ExpenseClaimApproved eventId={} (already processed)", eventId);
    }
  }

  private static ExpenseClaimApprovedEvent decode(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return ExpenseClaimApprovedSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new ExpenseClaimApprovedDecodeException(
          "Failed to decode ExpenseClaimApproved payload at "
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
   * SaleRecorded}/{@code ExpenseRecorded}). A missing or non-UUID {@code id} header is a contract
   * violation: the consumer FAILS CLOSED with {@link MissingEventIdException}.
   */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(SaleRecordedListener.EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new MissingEventIdException(
          "ExpenseClaimApproved record at "
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
          "ExpenseClaimApproved '"
              + SaleRecordedListener.EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
