package id.co.nativeapp.finance.register.messaging;

import id.co.nativeapp.finance.register.service.RegisterCloseService;
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
 * Consumes {@code RegisterSessionClosed} off Kafka and posts the drawer variance (ADR 0036). The
 * exact {@code SaleRecordedListener} contract: raw outbox Avro bytes; the durable event id comes
 * from the Debezium-stamped {@code id} header (missing/non-UUID → fail closed → DLT — a synthesized
 * id would defeat dedupe and risk double-posting); the tenant binds from the EVENT's company inside
 * {@link RegisterCloseService}; a decode failure OR a violated reconciliation identity is poison →
 * {@link RegisterSessionClosedDecodeException} → {@code RegisterSessionClosed.DLT}.
 */
@Component
public class RegisterSessionClosedListener {

  private static final Logger log = LoggerFactory.getLogger(RegisterSessionClosedListener.class);

  private final RegisterCloseService service;

  public RegisterSessionClosedListener(RegisterCloseService service) {
    this.service = service;
  }

  /** Handles one record from the {@code RegisterSessionClosed} topic. */
  @KafkaListener(
      topics = RegisterSessionClosedSchema.TOPIC,
      containerFactory = "kafkaListenerContainerFactory")
  public void onRegisterSessionClosed(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    RegisterSessionClosedEvent event = decodeAndAssert(eventId, record);
    boolean posted = service.handle(event);
    if (!posted) {
      log.debug("Skipped re-delivered RegisterSessionClosed eventId={} (already processed)", eventId);
    }
  }

  /**
   * Decodes the payload AND re-asserts the reconciliation identities — an inconsistent event can
   * never post correct money, so it is poison at decode time (same DLT path as garbage bytes).
   */
  private static RegisterSessionClosedEvent decodeAndAssert(
      UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      RegisterSessionClosedEvent event =
          RegisterSessionClosedSchema.decode(eventId, record.value());
      event.assertReconciliationIdentity();
      return event;
    } catch (RuntimeException failure) {
      throw new RegisterSessionClosedDecodeException(
          "Poison RegisterSessionClosed at "
              + record.topic()
              + "-"
              + record.partition()
              + "@"
              + record.offset()
              + " (eventId="
              + eventId
              + "); routing to DLT",
          failure);
    }
  }

  /** The durable event id from the {@code id} header — fail closed when absent (see class doc). */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader("id");
    if (header == null || header.value() == null) {
      throw new MissingEventIdException(
          "RegisterSessionClosed record at "
              + record.topic()
              + "-"
              + record.partition()
              + "@"
              + record.offset()
              + " has no 'id' header; routing to DLT (fail closed)");
    }
    String raw = new String(header.value(), StandardCharsets.UTF_8).strip();
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException badId) {
      throw new MissingEventIdException(
          "RegisterSessionClosed 'id' header is not a UUID (" + raw + "); routing to DLT", badId);
    }
  }
}
