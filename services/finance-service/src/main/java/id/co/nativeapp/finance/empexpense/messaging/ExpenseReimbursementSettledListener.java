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
 * Consumes {@code ExpenseReimbursementSettled} off Kafka and settles the employee-expense payable
 * (ADR 0030, expense-claims program) — mirrors {@code ExpenseRecordedListener}.
 *
 * <p><strong>Raw Avro bytes / idempotency by event id / tenant from the event.</strong> Identical
 * wiring to {@link ExpenseClaimApprovedListener} — see its javadoc.
 *
 * <p><strong>Settle-once is NOT poison.</strong> {@link ExpenseClaimPostingService#handleSettled}
 * treats a second settlement for the same claim — a Kafka re-delivery or a payroll-supersession
 * re-emission — as a logged no-op, never an exception. Only an undecodable payload ({@link
 * ExpenseReimbursementSettledDecodeException}) or a missing {@code id} header routes to {@code
 * ExpenseReimbursementSettled.DLT}.
 */
@Component
public class ExpenseReimbursementSettledListener {

  private static final Logger log =
      LoggerFactory.getLogger(ExpenseReimbursementSettledListener.class);

  private final ExpenseClaimPostingService postingService;

  public ExpenseReimbursementSettledListener(ExpenseClaimPostingService postingService) {
    this.postingService = postingService;
  }

  /**
   * Handles one record from the {@code ExpenseReimbursementSettled} topic. Decodes the raw Avro
   * bytes, derives the event id from the header, and delegates to the idempotent settlement
   * service. A non-decodable value surfaces as a non-retryable {@link
   * ExpenseReimbursementSettledDecodeException} routed straight to the DLT.
   */
  @KafkaListener(
      topics = ExpenseReimbursementSettledSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onExpenseReimbursementSettled(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    ExpenseReimbursementSettledEvent event = decode(eventId, record);
    boolean posted = postingService.handleSettled(event);
    if (!posted) {
      log.debug(
          "Skipped re-delivered ExpenseReimbursementSettled eventId={} (already processed)",
          eventId);
    }
  }

  private static ExpenseReimbursementSettledEvent decode(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return ExpenseReimbursementSettledSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new ExpenseReimbursementSettledDecodeException(
          "Failed to decode ExpenseReimbursementSettled payload at "
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
          "ExpenseReimbursementSettled record at "
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
          "ExpenseReimbursementSettled '"
              + SaleRecordedListener.EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
