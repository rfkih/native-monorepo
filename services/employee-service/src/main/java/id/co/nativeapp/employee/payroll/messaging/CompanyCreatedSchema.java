package id.co.nativeapp.employee.payroll.messaging;

import id.co.nativeapp.events.AvroSerde;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads employee-service's CONSUMER copy of the {@code CompanyCreated} Avro schema from the shared
 * {@code libs/contracts} classpath resource ({@code avro/CompanyCreated.avsc}) and decodes raw Avro
 * bytes into a {@link CompanyCreatedEvent} — no Avro code-generation, and no Confluent / Schema
 * Registry serde. The same idiom as {@link PeriodSealedSchema} and entitlement-service's {@code
 * CompanyCreatedSchema}; a contract test ({@code CompanyCreatedConsumerContractTest}) asserts this
 * reader stays backward-compatible with org-service's producer schema (rule 7).
 *
 * <p>The wire bytes are the producer outbox payload (raw Avro), so we deserialize with {@code
 * libs/events} {@link AvroSerde} against this parsed schema — consistent with how the outbox stores
 * events.
 */
public final class CompanyCreatedSchema {

  /**
   * Classpath location of the shared {@code .avsc} (libs/contracts — the single source of truth).
   */
  public static final String RESOURCE = "avro/CompanyCreated.avsc";

  /** The Kafka topic the producer outbox event is routed to (one topic per event type). */
  public static final String TOPIC = "CompanyCreated";

  private static final Schema SCHEMA = parse();

  private CompanyCreatedSchema() {
    // static holder
  }

  /** The parsed reader schema for {@code CompanyCreated} (employee-service's consumer view). */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Decodes raw Avro bytes (the org-service outbox payload, shipped by Debezium) into a {@link
   * CompanyCreatedEvent}, using this consumer copy of the schema as both writer and reader schema.
   *
   * @param eventId the event's UUID (the outbox row / Debezium message id) — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static CompanyCreatedEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    return new CompanyCreatedEvent(
        eventId, record.get("company_id").toString(), record.get("base_currency").toString());
  }

  private static Schema parse() {
    try (InputStream in =
        CompanyCreatedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
