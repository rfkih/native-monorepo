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
 * Loads finance-service's CONSUMER copy of the {@code LaborCostAllocated} Avro schema from the
 * classpath ({@code avro/LaborCostAllocated.avsc}) and decodes raw Avro bytes into a {@link
 * LaborCostAllocatedEvent} — no Avro code-generation plugin, and no Confluent / Schema Registry
 * serde. finance owns its own consumer view; a contract test asserts this copy stays
 * backward-compatible with the producer's schema (rule 7).
 *
 * <p>The wire bytes are the producer outbox payload (raw Avro), so we deserialize with {@code
 * libs/events} {@link AvroSerde} against this parsed schema — exactly how the {@code SaleRecorded}
 * / {@code ExpenseRecorded} consumer paths work. Money is reconstructed as {@code libs/money}
 * {@link Money} from the integer {@code amount_minor} + ISO-4217 {@code currency} (never a float,
 * rule 8).
 */
public final class LaborCostAllocatedSchema {

  /** Classpath location of the consumer-copy {@code .avsc}. */
  public static final String RESOURCE = "avro/LaborCostAllocated.avsc";

  /** The Kafka topic the producer outbox event is routed to (one topic per event type). */
  public static final String TOPIC = "LaborCostAllocated";

  private static final Schema SCHEMA = parse();

  private LaborCostAllocatedSchema() {
    // static holder
  }

  /** The parsed reader schema for {@code LaborCostAllocated} (finance's consumer copy). */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Decodes raw Avro bytes (the producer outbox payload, shipped by Debezium) into a {@link
   * LaborCostAllocatedEvent}, using this consumer copy of the schema. {@code period} is read as the
   * run's authoritative period; {@code occurred_at} is epoch millis UTC.
   *
   * @param eventId the event's UUID (the outbox row / Debezium message id) — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static LaborCostAllocatedEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    Money amount =
        Money.ofMinor((long) record.get("amount_minor"), record.get("currency").toString());
    return new LaborCostAllocatedEvent(
        eventId,
        record.get("company_id").toString(),
        UUID.fromString(record.get("payroll_run_id").toString()),
        (int) record.get("run_seq"),
        record.get("period").toString(),
        UUID.fromString(record.get("outlet_id").toString()),
        record.get("gl_account").toString(),
        amount,
        (boolean) record.get("uses_illustrative_rules"),
        (boolean) record.get("unallocated"),
        Instant.ofEpochMilli((long) record.get("occurred_at")));
  }

  private static Schema parse() {
    try (InputStream in =
        LaborCostAllocatedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
