package id.co.nativeapp.finance.orgref.messaging;

import id.co.nativeapp.events.AvroSerde;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads finance-service's consumer view of the org-service {@code OrgUnitCreated} / {@code
 * OrgUnitChanged} Avro schemas from the classpath ({@code avro/OrgUnitCreated.avsc} / {@code
 * avro/OrgUnitChanged.avsc}, on the classpath via {@code libs/contracts}, the shared single source
 * of truth — ADR 0003) and decodes raw Avro bytes into the {@link OrgUnitRefEvent} the consumer
 * applies — no Avro code-generation plugin, and no Confluent / Schema Registry serde (consistent
 * with all other finance consumers).
 *
 * <p>finance keeps its own consumer view of the contract: the full names {@code
 * id.co.nativeapp.events.org.OrgUnitCreated} / {@code .OrgUnitChanged} match the producer schemas
 * (docs/EVENT-CATALOG.md); the contract test ({@code OrgUnitConsumerContractTest}) asserts these
 * schemas stay backward-compatible with the producer schemas (rule 7). The wire bytes are the
 * org-service outbox payload (raw Avro), so we deserialize with {@code libs/events} {@link
 * AvroSerde} against the parsed schema — exactly how the other finance consumer paths work.
 *
 * <p><strong>Lazy holder.</strong> Each {@code .avsc} is parsed on FIRST use, not in a static field
 * initializer, to avoid {@code ExceptionInInitializerError} flakiness in partial test runs.
 */
public final class OrgUnitRefSchemas {

  /**
   * Classpath location of the {@code OrgUnitCreated} consumer schema (from {@code libs/contracts}).
   */
  public static final String CREATED_RESOURCE = "avro/OrgUnitCreated.avsc";

  /**
   * Classpath location of the {@code OrgUnitChanged} consumer schema (from {@code libs/contracts}).
   */
  public static final String CHANGED_RESOURCE = "avro/OrgUnitChanged.avsc";

  /** The {@code OrgUnitCreated} Kafka topic (one topic per event type). */
  public static final String CREATED_TOPIC = "OrgUnitCreated";

  /** The {@code OrgUnitChanged} Kafka topic. */
  public static final String CHANGED_TOPIC = "OrgUnitChanged";

  private OrgUnitRefSchemas() {
    // static holder
  }

  /** Lazy initialization-on-demand holder: schemas are parsed on FIRST use. */
  private static final class Holder {
    private static final Schema CREATED_SCHEMA = parse(CREATED_RESOURCE);
    private static final Schema CHANGED_SCHEMA = parse(CHANGED_RESOURCE);

    private Holder() {}
  }

  /** The parsed reader schema for {@code OrgUnitCreated} (finance's consumer view). */
  public static Schema createdSchema() {
    return Holder.CREATED_SCHEMA;
  }

  /** The parsed reader schema for {@code OrgUnitChanged} (finance's consumer view). */
  public static Schema changedSchema() {
    return Holder.CHANGED_SCHEMA;
  }

  /**
   * Decodes raw {@code OrgUnitCreated} Avro bytes into the {@link OrgUnitRefEvent} the consumer
   * upserts into {@code org_unit_ref}. A created node is always active (it may have been
   * deactivated later, but at creation time it is active).
   *
   * @param eventId the event's UUID (the outbox row / Debezium message id) — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static OrgUnitRefEvent decodeCreated(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, Holder.CREATED_SCHEMA);
    return new OrgUnitRefEvent(
        eventId,
        UUID.fromString(record.get("org_unit_id").toString()),
        record.get("company_id").toString(),
        record.get("type").toString(),
        record.get("parent_id") != null
            ? UUID.fromString(record.get("parent_id").toString())
            : null,
        record.get("name").toString(),
        true); // a newly created org unit is always active
  }

  /**
   * Decodes raw {@code OrgUnitChanged} Avro bytes into the {@link OrgUnitRefEvent} the consumer
   * upserts into {@code org_unit_ref}. The event carries the node's NEW state (name, parent, type,
   * active) after the change, so the consumer applies it idempotently by keying on {@code active},
   * not {@code change_kind} — a DEACTIVATED/REACTIVATED event sets {@code active} uniformly.
   *
   * @param eventId the event's UUID — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static OrgUnitRefEvent decodeChanged(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, Holder.CHANGED_SCHEMA);
    return new OrgUnitRefEvent(
        eventId,
        UUID.fromString(record.get("org_unit_id").toString()),
        record.get("company_id").toString(),
        record.get("type").toString(),
        record.get("parent_id") != null
            ? UUID.fromString(record.get("parent_id").toString())
            : null,
        record.get("name").toString(),
        (boolean) record.get("active"));
  }

  private static Schema parse(String resource) {
    try (InputStream in = OrgUnitRefSchemas.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + resource);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + resource, e);
    }
  }
}
