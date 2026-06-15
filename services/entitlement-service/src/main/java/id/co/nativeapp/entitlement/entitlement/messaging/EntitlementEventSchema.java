package id.co.nativeapp.entitlement.entitlement.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code EntitlementGranted} / {@code EntitlementRevoked} Avro schemas from the classpath
 * ({@code avro/*.avsc}) and builds {@link GenericRecord}s from them — no Avro code-generation
 * plugin. The schemas are the single source of truth registered in {@code docs/EVENT-CATALOG.md};
 * this class parses them once and the writer serializes the records to the outbox payload via
 * {@code libs/events} {@link id.co.nativeapp.events.AvroSerde AvroSerde}.
 *
 * <p>Field shape matches ARCHITECTURE.md §5: {@code company_id}, {@code module_key} (plus the
 * grant/revoke timestamp). The partition key / outbox aggregate id is the {@code company_id}.
 */
public final class EntitlementEventSchema {

  /** Classpath location of the {@code EntitlementGranted} {@code .avsc}. */
  public static final String GRANTED_RESOURCE = "avro/EntitlementGranted.avsc";

  /** Classpath location of the {@code EntitlementRevoked} {@code .avsc}. */
  public static final String REVOKED_RESOURCE = "avro/EntitlementRevoked.avsc";

  /** Outbox {@code event_type} for a grant. */
  public static final String GRANTED_EVENT_TYPE = "EntitlementGranted";

  /** Outbox {@code event_type} for a revoke. */
  public static final String REVOKED_EVENT_TYPE = "EntitlementRevoked";

  /** Outbox {@code aggregate_type} (partition routing): the entitlement aggregate kind. */
  public static final String AGGREGATE_TYPE = "entitlement";

  private static final Schema GRANTED_SCHEMA = parse(GRANTED_RESOURCE);
  private static final Schema REVOKED_SCHEMA = parse(REVOKED_RESOURCE);

  private EntitlementEventSchema() {
    // static holder
  }

  /** The parsed {@code EntitlementGranted} schema. */
  public static Schema grantedSchema() {
    return GRANTED_SCHEMA;
  }

  /** The parsed {@code EntitlementRevoked} schema. */
  public static Schema revokedSchema() {
    return REVOKED_SCHEMA;
  }

  /**
   * Builds an {@code EntitlementGranted} {@link GenericRecord}.
   *
   * @param companyId the owning tenant (UUID as string)
   * @param moduleKey the granted module
   * @param grantedAt when the grant took effect
   */
  public static GenericRecord granted(String companyId, String moduleKey, Instant grantedAt) {
    GenericRecord record = new GenericData.Record(GRANTED_SCHEMA);
    record.put("company_id", companyId);
    record.put("module_key", moduleKey);
    record.put("granted_at", grantedAt.toEpochMilli());
    return record;
  }

  /**
   * Builds an {@code EntitlementRevoked} {@link GenericRecord}.
   *
   * @param companyId the owning tenant (UUID as string)
   * @param moduleKey the revoked module
   * @param revokedAt when the revoke took effect
   */
  public static GenericRecord revoked(String companyId, String moduleKey, Instant revokedAt) {
    GenericRecord record = new GenericData.Record(REVOKED_SCHEMA);
    record.put("company_id", companyId);
    record.put("module_key", moduleKey);
    record.put("revoked_at", revokedAt.toEpochMilli());
    return record;
  }

  private static Schema parse(String resource) {
    try (InputStream in =
        EntitlementEventSchema.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + resource);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + resource, e);
    }
  }
}
