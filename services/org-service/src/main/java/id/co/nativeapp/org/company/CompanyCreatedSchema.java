package id.co.nativeapp.org.company;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code CompanyCreated} Avro schema from the classpath ({@code
 * avro/CompanyCreated.avsc}) and builds {@link GenericRecord}s from it — no Avro code-generation
 * plugin. The schema is the single source of truth registered in {@code docs/EVENT-CATALOG.md};
 * this class parses it once and projects a {@link Company} onto it.
 *
 * <p>The record is serialized to the outbox payload via {@code libs/events} {@link
 * id.co.nativeapp.events.AvroSerde AvroSerde}. Field shape matches ARCHITECTURE.md §5: {@code
 * company_id}, {@code legal_employer_id}, {@code base_currency}, {@code default_language} (all
 * strings).
 */
public final class CompanyCreatedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/CompanyCreated.avsc";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "CompanyCreated";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "company";

  private static final Schema SCHEMA = parse();

  private CompanyCreatedSchema() {
    // static holder
  }

  /** The parsed reader/writer schema for {@code CompanyCreated}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code CompanyCreated} {@link GenericRecord} from a persisted company.
   *
   * @param company the persisted company aggregate (its id is the new tenant id)
   */
  public static GenericRecord toRecord(Company company) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("company_id", company.getId().toString());
    record.put("legal_employer_id", company.getLegalEmployerId().toString());
    record.put("base_currency", company.getBaseCurrency());
    record.put("default_language", company.getDefaultLanguage());
    return record;
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
