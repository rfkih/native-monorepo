package id.co.nativeapp.finance.empexpense.messaging;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads finance-service's reader view of the {@code ExpenseClaimVoided} contract from the classpath
 * ({@code avro/ExpenseClaimVoided.avsc}, single-sourced from libs/contracts — ADR 0003). Consumed
 * to post the exact contra of a voided approval: Dr {@code 2600 Employee Expense Payable} / Cr the
 * same category expense — the mapping is resolved effective at the ORIGINAL {@code approved_at},
 * the entry posts into the period of {@code voided_at} (ADR 0030). A void arriving after a
 * settlement is a loud logged skip, never a silent reversal of moved money.
 */
public final class ExpenseClaimVoidedSchema {

  /** Classpath location of the {@code .avsc}. */
  public static final String RESOURCE = "avro/ExpenseClaimVoided.avsc";

  /** The Kafka topic the producer outbox event is routed to (one topic per event type). */
  public static final String TOPIC = "ExpenseClaimVoided";

  private static final Schema SCHEMA = parse();

  private ExpenseClaimVoidedSchema() {
    // static holder
  }

  /** The parsed reader schema. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Decodes raw Avro bytes (the producer outbox payload, shipped by Debezium) into an {@link
   * ExpenseClaimVoidedEvent}, using this consumer copy of the schema as both writer and reader
   * schema. The money is reconstructed as {@code libs/money} {@link Money} from the integer {@code
   * amount_minor} + ISO-4217 {@code currency} (never a float); {@code approved_at}/{@code
   * voided_at} are epoch millis UTC.
   *
   * @param eventId the event's UUID (the outbox row / Debezium message id) — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static ExpenseClaimVoidedEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    Money amount =
        Money.ofMinor((long) record.get("amount_minor"), record.get("currency").toString());
    return new ExpenseClaimVoidedEvent(
        eventId,
        UUID.fromString(record.get("claim_id").toString()),
        record.get("company_id").toString(),
        UUID.fromString(record.get("org_unit_id").toString()),
        UUID.fromString(record.get("employee_id").toString()),
        amount,
        record.get("gl_hint").toString(),
        Instant.ofEpochMilli((long) record.get("approved_at")),
        Instant.ofEpochMilli((long) record.get("voided_at")));
  }

  private static Schema parse() {
    try (InputStream in =
        ExpenseClaimVoidedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
