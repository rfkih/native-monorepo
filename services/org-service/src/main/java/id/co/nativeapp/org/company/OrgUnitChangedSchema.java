package id.co.nativeapp.org.company;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code OrgUnitChanged} Avro schema from the classpath ({@code
 * avro/OrgUnitChanged.avsc}) and builds {@link GenericRecord}s from it — no Avro code-generation
 * plugin (same approach as {@link CompanyCreatedSchema}). The schema is the single source of truth
 * registered in {@code docs/EVENT-CATALOG.md}; this class parses it once and projects an {@link
 * OrgUnit} plus the {@link OrgUnitChangeKind} onto it.
 *
 * <p>Field shape matches ARCHITECTURE.md §5: {@code org_unit_id}, {@code type}, {@code parent_id},
 * {@code company_id} (plus {@code change_kind}, {@code name}, {@code active} for the new state).
 */
public final class OrgUnitChangedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/OrgUnitChanged.avsc";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "OrgUnitChanged";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "org_unit";

  private static final Schema SCHEMA = parse();

  private OrgUnitChangedSchema() {
    // static holder
  }

  /** The parsed reader/writer schema for {@code OrgUnitChanged}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds an {@code OrgUnitChanged} {@link GenericRecord} reflecting the org unit's new state
   * after the given kind of change.
   *
   * @param orgUnit the org-unit aggregate in its post-change state
   * @param changeKind what changed (rename / move / deactivate)
   */
  public static GenericRecord toRecord(OrgUnit orgUnit, OrgUnitChangeKind changeKind) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("org_unit_id", orgUnit.getId().toString());
    record.put("company_id", orgUnit.getCompanyId());
    record.put("type", orgUnit.getType().name());
    record.put(
        "parent_id", orgUnit.getParentId() == null ? null : orgUnit.getParentId().toString());
    record.put("change_kind", changeKind.name());
    record.put("name", orgUnit.getName());
    record.put("active", orgUnit.isActive());
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        OrgUnitChangedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
