package id.co.nativeapp.barbershop.outletref.messaging;

import id.co.nativeapp.events.AvroSerde;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads barbershop-service's consumer view of the org-service {@code UserOutletAssignmentChanged}
 * Avro schema from the classpath ({@code avro/UserOutletAssignmentChanged.avsc}, provided by {@code
 * libs/contracts} — the shared single source of truth, ADR 0003) and decodes raw Avro bytes into
 * {@link UserOutletAssignmentEvent}s the consumer applies to {@code user_outlet_assignment_ref}.
 *
 * <p>Ported verbatim from carwash-service's {@code outletref} feature: NO local {@code .avsc} copy
 * is added under this service's own {@code src/main/resources/avro/} — this service resolves the
 * schema from the shared {@code libs/contracts} classpath resource instead, since barbershop-service
 * already depends on {@code libs/contracts} (see {@code build.gradle.kts}).
 *
 * <p>No Avro code-generation plugin and no Confluent / Schema Registry serde — consistent with how
 * the outbox stores events (raw Avro bytes, base64-transported by Debezium) and with all other
 * consumer paths in this service and fleet.
 *
 * <p><strong>Lazy holder.</strong> The {@code .avsc} is parsed on FIRST use, not in a static field
 * initializer, to avoid {@code ExceptionInInitializerError} flakiness in partial test runs.
 */
public final class UserOutletAssignmentChangedSchemas {

  /**
   * Classpath location of the consumer schema (from {@code libs/contracts}). The full name is
   * {@code id.co.nativeapp.events.org.UserOutletAssignmentChanged}.
   */
  public static final String RESOURCE = "avro/UserOutletAssignmentChanged.avsc";

  /** The Kafka topic this consumer reads. */
  public static final String TOPIC = "UserOutletAssignmentChanged";

  private UserOutletAssignmentChangedSchemas() {}

  /** Lazy initialization-on-demand holder: schema is parsed on FIRST use. */
  private static final class Holder {
    private static final Schema SCHEMA = parse(RESOURCE);

    private Holder() {}
  }

  /** The parsed reader schema for {@code UserOutletAssignmentChanged}. */
  public static Schema schema() {
    return Holder.SCHEMA;
  }

  /**
   * Decodes raw {@code UserOutletAssignmentChanged} Avro bytes into the {@link
   * UserOutletAssignmentEvent} the consumer upserts into {@code user_outlet_assignment_ref}.
   *
   * @param eventId the event UUID taken from the {@code id} Kafka header (the idempotency key)
   * @param payload the raw Avro bytes off the topic (post-base64-decode)
   */
  public static UserOutletAssignmentEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, Holder.SCHEMA);
    return new UserOutletAssignmentEvent(
        eventId,
        UUID.fromString(record.get("assignment_id").toString()),
        record.get("user_id").toString(),
        record.get("company_id").toString(),
        UUID.fromString(record.get("org_unit_id").toString()),
        record.get("change_kind").toString(),
        (int) record.get("effective_from"),
        (int) record.get("effective_to"));
  }

  private static Schema parse(String resource) {
    try (InputStream in =
        UserOutletAssignmentChangedSchemas.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + resource);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + resource, e);
    }
  }
}
