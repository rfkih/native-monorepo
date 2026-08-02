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
 * Consumes {@code ExpenseClaimVoided} off Kafka and posts the exact contra of the original approval
 * (ADR 0030, expense-claims program) — mirrors {@code ExpenseRecordedListener}/{@code
 * SaleVoidedListener}.
 *
 * <p><strong>Raw Avro bytes / idempotency by event id / tenant from the event.</strong> Identical
 * wiring to {@link ExpenseClaimApprovedListener} — see its javadoc.
 *
 * <p><strong>A void after a settlement is NOT poison.</strong> {@link
 * id.co.nativeapp.finance.empexpense.service.ExpenseClaimVoidWriter} treats an already-settled
 * claim as a loud logged skip, not an exception — only an undecodable payload ({@link
 * ExpenseClaimVoidedDecodeException}) or a missing {@code id} header routes to {@code
 * ExpenseClaimVoided.DLT}.
 */
@Component
public class ExpenseClaimVoidedListener {

  private static final Logger log = LoggerFactory.getLogger(ExpenseClaimVoidedListener.class);

  private final ExpenseClaimPostingService postingService;

  public ExpenseClaimVoidedListener(ExpenseClaimPostingService postingService) {
    this.postingService = postingService;
  }

  /**
   * Handles one record from the {@code ExpenseClaimVoided} topic. Decodes the raw Avro bytes,
   * derives the event id from the header, and delegates to the idempotent posting service. A
   * non-decodable value surfaces as a non-retryable {@link ExpenseClaimVoidedDecodeException}
   * routed straight to the DLT.
   */
  @KafkaListener(
      topics = ExpenseClaimVoidedSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onExpenseClaimVoided(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    ExpenseClaimVoidedEvent event = decode(eventId, record);
    boolean posted = postingService.handleVoided(event);
    if (!posted) {
      log.debug("Skipped re-delivered ExpenseClaimVoided eventId={} (already processed)", eventId);
    }
  }

  private static ExpenseClaimVoidedEvent decode(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return ExpenseClaimVoidedSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new ExpenseClaimVoidedDecodeException(
          "Failed to decode ExpenseClaimVoided payload at "
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
    Header header = record.headers().lastHeader(SaleRecordedListener.EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new MissingEventIdException(
          "ExpenseClaimVoided record at "
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
          "ExpenseClaimVoided '"
              + SaleRecordedListener.EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
