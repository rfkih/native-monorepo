package id.co.nativeapp.org.company;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code OrgUnitCreated} Avro schema from the classpath ({@code
 * avro/OrgUnitCreated.avsc}) and builds {@link GenericRecord}s from it — no Avro code-generation
 * plugin (same approach as {@link CompanyCreatedSchema}). The schema is the single source of truth
 * registered in {@code docs/EVENT-CATALOG.md}; this class parses it once and projects an {@link
 * OrgUnit} onto it.
 *
 * <p>Field shape matches ARCHITECTURE.md §5: {@code org_unit_id}, {@code type}, {@code parent_id},
 * {@code company_id} (plus {@code legal_employer_id} and {@code name}). {@code parent_id} is a
 * nullable union, since a top-level node has no parent.
 */
public final class OrgUnitCreatedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/OrgUnitCreated.avsc";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "OrgUnitCreated";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "org_unit";

  private static final Schema SCHEMA = parse();

  private OrgUnitCreatedSchema() {
    // static holder
  }

  /** The parsed reader/writer schema for {@code OrgUnitCreated}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds an {@code OrgUnitCreated} {@link GenericRecord} from a persisted org unit.
   *
   * @param orgUnit the persisted org-unit aggregate
   */
  public static GenericRecord toRecord(OrgUnit orgUnit) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("org_unit_id", orgUnit.getId().toString());
    record.put("company_id", orgUnit.getCompanyId());
    record.put("type", orgUnit.getType().name());
    record.put(
        "parent_id", orgUnit.getParentId() == null ? null : orgUnit.getParentId().toString());
    record.put("legal_employer_id", orgUnit.getLegalEmployerId().toString());
    record.put("name", orgUnit.getName());
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        OrgUnitCreatedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
