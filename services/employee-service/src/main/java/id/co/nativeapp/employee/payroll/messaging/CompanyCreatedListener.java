package id.co.nativeapp.employee.payroll.messaging;

import id.co.nativeapp.employee.payroll.service.PayrollBootstrapService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code CompanyCreated} off Kafka and — for an Indonesian ({@code IDR}) company —
 * auto-activates the OFFICIAL statutory dataset so the tenant's payroll is OFFICIAL from creation.
 * This is ADR 0042's go-live default made AUTOMATIC: previously a tenant became OFFICIAL only when
 * the console's payroll setup-gate button was pressed, so a tenant that skipped it (or ran payroll
 * first) stayed on illustrative rules and every run was flagged {@code uses_illustrative_rules =
 * true} forever (a frozen snapshot, HR-7). Seeding on {@code CompanyCreated} closes that gap for
 * every new company.
 *
 * <p>Raw Avro bytes via {@link CompanyCreatedSchema} (no Schema Registry serde), the same shape as
 * {@link PeriodSealedListener} and entitlement-service's {@code CompanyCreatedListener}. Idempotent
 * by the event UUID from the {@code id} header — a missing/non-UUID id is a producer-side contract
 * violation, so the listener FAILS CLOSED ({@link MissingPayrollEventIdException}) and the record
 * is DLT'd rather than processed under a synthesised id that would defeat dedupe. The tenant comes
 * from the event's {@code company_id} inside {@link PayrollBootstrapService} (no JWT on the
 * consumer path), so RLS applies on the seed. A non-transient failure is retried a bounded number
 * of times then routed to {@code CompanyCreated.DLT} by {@code KafkaConfig}'s error handler.
 */
@Component
public class CompanyCreatedListener {

  /** The Kafka header carrying the durable event id (the Debezium outbox-router {@code id}). */
  public static final String EVENT_ID_HEADER = "id";

  private static final Logger log = LoggerFactory.getLogger(CompanyCreatedListener.class);

  private final PayrollBootstrapService bootstrapService;

  public CompanyCreatedListener(PayrollBootstrapService bootstrapService) {
    this.bootstrapService = bootstrapService;
  }

  /**
   * Handles one {@code CompanyCreated}: for an IDR company, activates the OFFICIAL payroll dataset
   * for the new tenant (idempotently); for a non-IDR company or a re-delivery, a no-op.
   */
  @KafkaListener(
      topics = CompanyCreatedSchema.TOPIC,
      containerFactory = "companyBootstrapListenerContainerFactory")
  public void onCompanyCreated(ConsumerRecord<String, byte[]> record) {
    UUID eventId = eventIdOf(record);
    CompanyCreatedEvent event = decode(eventId, record);
    boolean bootstrapped = bootstrapService.onCompanyCreated(event);
    if (!bootstrapped) {
      log.debug(
          "CompanyCreated eventId={} companyId={} baseCurrency={} did not auto-bootstrap payroll"
              + " (non-IDR company, or an already-processed re-delivery)",
          eventId,
          event.companyId(),
          event.baseCurrency());
    }
  }

  /**
   * Decodes the raw Avro value into a {@link CompanyCreatedEvent}, converting ANY decode failure
   * into a non-retryable {@link PayrollEventDecodeException} so a poison payload deterministically
   * lands on the DLT rather than retrying forever.
   */
  private static CompanyCreatedEvent decode(UUID eventId, ConsumerRecord<String, byte[]> record) {
    try {
      return CompanyCreatedSchema.decode(eventId, record.value());
    } catch (RuntimeException decodeFailure) {
      throw new PayrollEventDecodeException(
          "Failed to decode CompanyCreated payload at "
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
   * is a contract violation: we FAIL CLOSED — throwing {@link MissingPayrollEventIdException},
   * which the container's error handler treats as non-retryable and routes straight to {@code
   * CompanyCreated.DLT}. Synthesising a positional UUID would defeat dedupe after a rebalance /
   * compacted replay, so a company could be double-bootstrapped.
   */
  private static UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
    Header header = record.headers().lastHeader(EVENT_ID_HEADER);
    if (header == null || header.value() == null) {
      throw new MissingPayrollEventIdException(
          "CompanyCreated record at "
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
      throw new MissingPayrollEventIdException(
          "CompanyCreated '"
              + EVENT_ID_HEADER
              + "' header is not a UUID ("
              + raw
              + "); routing to DLT (fail closed)",
          badId);
    }
  }
}
