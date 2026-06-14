package id.co.nativeapp.carwash.entitlement;

import id.co.nativeapp.events.AvroSerde;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads carwash-service's CONSUMER copies of the {@code EntitlementGranted} / {@code
 * EntitlementRevoked} Avro schemas from the classpath and decodes raw Avro bytes into the {@link
 * EntitlementProjectedEvent} the consumer applies — no Avro code-generation plugin, and no
 * Confluent / Schema Registry serde (the same approach finance-service / employee-service use for
 * their consumed events). carwash-service owns its own consumer view of each contract (full name
 * {@code id.co.nativeapp.events.entitlement.EntitlementGranted} / {@code .EntitlementRevoked},
 * matching docs/EVENT-CATALOG.md); a contract test asserts these copies stay backward-compatible
 * with the producer schemas (rule 7).
 *
 * <p>The wire bytes are the entitlement-service outbox payload (raw Avro), so we deserialize with
 * {@code libs/events} {@link AvroSerde} against the parsed schema.
 */
public final class EntitlementEventSchemas {

  /** Classpath location of the {@code EntitlementGranted} consumer-copy {@code .avsc}. */
  public static final String GRANTED_RESOURCE = "avro/EntitlementGranted.avsc";

  /** Classpath location of the {@code EntitlementRevoked} consumer-copy {@code .avsc}. */
  public static final String REVOKED_RESOURCE = "avro/EntitlementRevoked.avsc";

  /** The {@code EntitlementGranted} Kafka topic (one topic per event type). */
  public static final String GRANTED_TOPIC = "EntitlementGranted";

  /** The {@code EntitlementRevoked} Kafka topic. */
  public static final String REVOKED_TOPIC = "EntitlementRevoked";

  private EntitlementEventSchemas() {
    // static holder
  }

  /**
   * Lazy holder: each {@code .avsc} is parsed on FIRST use, not in a static field initializer, so a
   * parse failure surfaces as a normal, repeatable exception at the call site rather than an {@code
   * ExceptionInInitializerError} on first class load (order-dependent under a partial test
   * selection).
   */
  private static final class Holder {
    private static final Schema GRANTED_SCHEMA = parse(GRANTED_RESOURCE);
    private static final Schema REVOKED_SCHEMA = parse(REVOKED_RESOURCE);

    private Holder() {}
  }

  /** The parsed reader schema for {@code EntitlementGranted} (carwash's consumer copy). */
  public static Schema grantedSchema() {
    return Holder.GRANTED_SCHEMA;
  }

  /** The parsed reader schema for {@code EntitlementRevoked} (carwash's consumer copy). */
  public static Schema revokedSchema() {
    return Holder.REVOKED_SCHEMA;
  }

  /**
   * Decodes raw {@code EntitlementGranted} Avro bytes into the projected event (entitled = true).
   *
   * @param eventId the event's UUID — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static EntitlementProjectedEvent decodeGranted(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, Holder.GRANTED_SCHEMA);
    return new EntitlementProjectedEvent(
        eventId, record.get("company_id").toString(), record.get("module_key").toString(), true);
  }

  /**
   * Decodes raw {@code EntitlementRevoked} Avro bytes into the projected event (entitled = false).
   *
   * @param eventId the event's UUID — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static EntitlementProjectedEvent decodeRevoked(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, Holder.REVOKED_SCHEMA);
    return new EntitlementProjectedEvent(
        eventId, record.get("company_id").toString(), record.get("module_key").toString(), false);
  }

  private static Schema parse(String resource) {
    try (InputStream in =
        EntitlementEventSchemas.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + resource);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + resource, e);
    }
  }
}
