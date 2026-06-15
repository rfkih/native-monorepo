package id.co.nativeapp.employee.org.messaging;

import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.events.AvroSerde;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads employee-service's CONSUMER copies of the {@code OrgUnitCreated} / {@code OrgUnitChanged}
 * Avro schemas from the classpath and decodes raw Avro bytes into the {@link OrgUnitProjectedEvent}
 * the consumer applies — no Avro code-generation plugin, and no Confluent / Schema Registry serde
 * (the same approach finance-service / entitlement-service use for their consumed events).
 * employee-service owns its own consumer view of each contract (full name {@code
 * id.co.nativeapp.events.org.OrgUnitCreated} / {@code .OrgUnitChanged}, matching
 * docs/EVENT-CATALOG.md); contract tests assert these copies stay backward-compatible with the
 * producer schemas (rule 7).
 *
 * <p>The wire bytes are the org-service outbox payload (raw Avro), so we deserialize with {@code
 * libs/events} {@link AvroSerde} against the parsed schema — consistent with how the outbox stores
 * events.
 */
public final class OrgUnitEventSchemas {

  /** Classpath location of the {@code OrgUnitCreated} consumer-copy {@code .avsc}. */
  public static final String CREATED_RESOURCE = "avro/OrgUnitCreated.avsc";

  /** Classpath location of the {@code OrgUnitChanged} consumer-copy {@code .avsc}. */
  public static final String CHANGED_RESOURCE = "avro/OrgUnitChanged.avsc";

  /** The {@code OrgUnitCreated} Kafka topic (one topic per event type). */
  public static final String CREATED_TOPIC = "OrgUnitCreated";

  /** The {@code OrgUnitChanged} Kafka topic. */
  public static final String CHANGED_TOPIC = "OrgUnitChanged";

  private OrgUnitEventSchemas() {
    // static holder
  }

  /**
   * Lazy holder: each {@code .avsc} is parsed on FIRST use, not in a static field initializer. An
   * eager static initializer makes a parse failure surface as an {@code
   * ExceptionInInitializerError} /{@code NoClassDefFoundError} on the first class load — which,
   * under a partial test selection, depends on which class happens to touch the schema first and
   * flakes by execution order. The initialization-on-demand holder defers the parse to the {@code
   * *Schema()}/{@code decode*} accessors and surfaces any failure as a normal, repeatable exception
   * at the call site.
   */
  private static final class Holder {
    private static final Schema CREATED_SCHEMA = parse(CREATED_RESOURCE);
    private static final Schema CHANGED_SCHEMA = parse(CHANGED_RESOURCE);

    private Holder() {}
  }

  /** The parsed reader schema for {@code OrgUnitCreated} (employee's consumer copy). */
  public static Schema createdSchema() {
    return Holder.CREATED_SCHEMA;
  }

  /** The parsed reader schema for {@code OrgUnitChanged} (employee's consumer copy). */
  public static Schema changedSchema() {
    return Holder.CHANGED_SCHEMA;
  }

  /**
   * Decodes raw {@code OrgUnitCreated} Avro bytes into the projected event the consumer applies. A
   * created node is always active.
   *
   * @param eventId the event's UUID (the outbox row / Debezium message id) — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static OrgUnitProjectedEvent decodeCreated(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, Holder.CREATED_SCHEMA);
    return new OrgUnitProjectedEvent(
        eventId,
        UUID.fromString(record.get("org_unit_id").toString()),
        record.get("company_id").toString(),
        UUID.fromString(record.get("legal_employer_id").toString()),
        record.get("type").toString(),
        true);
  }

  /**
   * Decodes raw {@code OrgUnitChanged} Avro bytes into the projected event the consumer applies.
   * The changed event carries the node's new {@code type} and {@code active} state. It does NOT
   * carry {@code legal_employer_id} (the org-unit's legal employer is stable today), so the
   * consumer keeps the existing projected legal employer and updates only type + active.
   *
   * @param eventId the event's UUID — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static OrgUnitProjectedEvent decodeChanged(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, Holder.CHANGED_SCHEMA);
    return new OrgUnitProjectedEvent(
        eventId,
        UUID.fromString(record.get("org_unit_id").toString()),
        record.get("company_id").toString(),
        null, // legal_employer_id not on OrgUnitChanged; consumer retains the existing value
        record.get("type").toString(),
        (boolean) record.get("active"));
  }

  private static Schema parse(String resource) {
    try (InputStream in =
        OrgUnitEventSchemas.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + resource);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + resource, e);
    }
  }
}
