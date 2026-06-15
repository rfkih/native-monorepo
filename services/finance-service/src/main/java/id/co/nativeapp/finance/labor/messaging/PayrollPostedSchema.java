package id.co.nativeapp.finance.labor.messaging;

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
 * Loads finance-service's CONSUMER copy of the {@code PayrollPosted} Avro schema from the classpath
 * ({@code avro/PayrollPosted.avsc}) and decodes raw Avro bytes into a {@link PayrollPostedEvent} —
 * no Avro code-generation plugin, and no Confluent / Schema Registry serde. A contract test asserts
 * this copy stays backward-compatible with the producer's schema (rule 7).
 *
 * <p>All totals are reconstructed as {@code libs/money} {@link Money} from integer minor units +
 * the {@code base_currency} ISO-4217 code (never a float, rule 8). {@code rule_versions} is decoded
 * but not persisted in the ledger.
 */
public final class PayrollPostedSchema {

  /** Classpath location of the consumer-copy {@code .avsc}. */
  public static final String RESOURCE = "avro/PayrollPosted.avsc";

  /** The Kafka topic the producer outbox event is routed to (one topic per event type). */
  public static final String TOPIC = "PayrollPosted";

  private static final Schema SCHEMA = parse();

  private PayrollPostedSchema() {
    // static holder
  }

  /** The parsed reader schema for {@code PayrollPosted} (finance's consumer copy). */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Decodes raw Avro bytes into a {@link PayrollPostedEvent}, using this consumer copy of the
   * schema. Each total becomes a {@link Money} in the run's {@code base_currency}; {@code
   * posted_at} is epoch millis UTC.
   *
   * @param eventId the event's UUID (the outbox row / Debezium message id) — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static PayrollPostedEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    String currency = record.get("base_currency").toString();
    return new PayrollPostedEvent(
        eventId,
        record.get("company_id").toString(),
        UUID.fromString(record.get("payroll_run_id").toString()),
        (int) record.get("run_seq"),
        record.get("period").toString(),
        currency,
        Money.ofMinor((long) record.get("gross_total_minor"), currency),
        Money.ofMinor((long) record.get("employee_deduction_total_minor"), currency),
        Money.ofMinor((long) record.get("employer_contribution_total_minor"), currency),
        Money.ofMinor((long) record.get("net_total_minor"), currency),
        (boolean) record.get("uses_illustrative_rules"),
        Instant.ofEpochMilli((long) record.get("posted_at")));
  }

  private static Schema parse() {
    try (InputStream in =
        PayrollPostedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
