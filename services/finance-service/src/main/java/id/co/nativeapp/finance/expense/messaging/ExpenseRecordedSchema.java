package id.co.nativeapp.finance.expense.messaging;

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
 * Loads finance-service's CONSUMER copy of the {@code ExpenseRecorded} Avro schema from the
 * classpath ({@code avro/ExpenseRecorded.avsc}) and decodes raw Avro bytes into an {@link
 * ExpenseRecordedEvent} — no Avro code-generation plugin, and no Confluent / Schema Registry serde.
 * finance owns its own consumer view of the contract; a contract test asserts this copy stays
 * backward-compatible with the producer's schema (rule 7).
 *
 * <p>The wire bytes are the producer outbox payload (raw Avro), so we deserialize with {@code
 * libs/events} {@link AvroSerde} against this parsed schema — consistent with how the outbox stores
 * events and exactly how the {@code SaleRecorded} consumer path works.
 */
public final class ExpenseRecordedSchema {

  /** Classpath location of the consumer-copy {@code .avsc}. */
  public static final String RESOURCE = "avro/ExpenseRecorded.avsc";

  /** The Kafka topic the producer outbox event is routed to (one topic per event type). */
  public static final String TOPIC = "ExpenseRecorded";

  private static final Schema SCHEMA = parse();

  private ExpenseRecordedSchema() {
    // static holder
  }

  /** The parsed reader schema for {@code ExpenseRecorded} (finance's consumer copy). */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Decodes raw Avro bytes (the producer outbox payload, shipped by Debezium) into an {@link
   * ExpenseRecordedEvent}, using this consumer copy of the schema as both writer and reader schema.
   * The money is reconstructed as {@code libs/money} {@link Money} from the integer {@code
   * amount_minor} + ISO-4217 {@code currency} (never a float); {@code occurred_at} is epoch millis
   * UTC.
   *
   * @param eventId the event's UUID (the outbox row / Debezium message id) — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static ExpenseRecordedEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    Money amount =
        Money.ofMinor((long) record.get("amount_minor"), record.get("currency").toString());
    return new ExpenseRecordedEvent(
        eventId,
        record.get("company_id").toString(),
        UUID.fromString(record.get("business_id").toString()),
        amount,
        record.get("gl_hint").toString(),
        Instant.ofEpochMilli((long) record.get("occurred_at")));
  }

  private static Schema parse() {
    try (InputStream in =
        ExpenseRecordedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
