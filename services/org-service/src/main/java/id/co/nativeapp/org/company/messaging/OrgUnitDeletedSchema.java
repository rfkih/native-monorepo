package id.co.nativeapp.org.company.messaging;

import id.co.nativeapp.org.company.domain.OrgUnit;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code OrgUnitDeleted} Avro schema from the classpath ({@code
 * avro/OrgUnitDeleted.avsc}, shared via {@code libs/contracts} — ADR 0003) and builds {@link
 * GenericRecord}s from it — no Avro code-generation plugin (same approach as {@link
 * OrgUnitCreatedSchema} / {@link OrgUnitChangedSchema}).
 *
 * <p>{@code OrgUnitDeleted} is the TERMINAL event for an org-unit aggregate: it is emitted from the
 * same transaction as the row delete ({@code OrgUnitWriter#delete}, and the ADR 0070 flattening
 * reconciler), and no further event ever follows for that {@code org_unit_id}. Consumers purge
 * their cached ref row rather than upserting one — closing the "deleted units linger as inert refs"
 * gap ADR 0018 recorded as a follow-up.
 *
 * <p>The record is built from the aggregate <em>before</em> it is removed, so the state it carries
 * ({@code type}, {@code parent_id}) is the node's last known state. Field shape matches
 * ARCHITECTURE.md §5: {@code org_unit_id}, {@code type}, {@code parent_id}, {@code company_id}.
 */
public final class OrgUnitDeletedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/OrgUnitDeleted.avsc";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "OrgUnitDeleted";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "org_unit";

  private static final Schema SCHEMA = parse();

  private OrgUnitDeletedSchema() {
    // static holder
  }

  /** The parsed reader/writer schema for {@code OrgUnitDeleted}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds an {@code OrgUnitDeleted} {@link GenericRecord} from the org unit being removed.
   *
   * @param orgUnit the org-unit aggregate as it stood immediately before deletion
   */
  public static GenericRecord toRecord(OrgUnit orgUnit) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("org_unit_id", orgUnit.getId().toString());
    record.put("company_id", orgUnit.getCompanyId());
    record.put("type", orgUnit.getType().name());
    record.put(
        "parent_id", orgUnit.getParentId() == null ? null : orgUnit.getParentId().toString());
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        OrgUnitDeletedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
