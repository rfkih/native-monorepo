package id.co.nativeapp.org.group.messaging;

import id.co.nativeapp.org.group.domain.ConsolidationGroup;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code GroupDefined} Avro schema from the classpath ({@code avro/GroupDefined.avsc})
 * and builds {@link GenericRecord}s from it — no Avro code-generation plugin (same approach as
 * {@link id.co.nativeapp.org.company.messaging.CompanyCreatedSchema}). The schema is the single
 * source of truth registered in {@code docs/EVENT-CATALOG.md}; this class parses it once and
 * projects a {@link ConsolidationGroup} onto it.
 *
 * <p>Field shape: {@code group_id}, {@code lead_company_id}, {@code reporting_currency}, {@code
 * name} (all strings; {@code reporting_currency} is an ISO-4217 code, never a monetary amount).
 */
public final class GroupDefinedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/GroupDefined.avsc";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "GroupDefined";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "consolidation_group";

  private static final Schema SCHEMA = parse();

  private GroupDefinedSchema() {
    // static holder
  }

  /** The parsed reader/writer schema for {@code GroupDefined}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code GroupDefined} {@link GenericRecord} from a persisted consolidation group.
   *
   * @param group the persisted consolidation-group aggregate
   */
  public static GenericRecord toRecord(ConsolidationGroup group) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("group_id", group.getId().toString());
    record.put("lead_company_id", group.getLeadCompanyId().toString());
    record.put("reporting_currency", group.getReportingCurrency());
    record.put("name", group.getName());
    return record;
  }

  private static Schema parse() {
    try (InputStream in = GroupDefinedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
