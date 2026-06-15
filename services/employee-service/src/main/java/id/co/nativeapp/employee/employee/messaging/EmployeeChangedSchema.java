package id.co.nativeapp.employee.employee.messaging;

import id.co.nativeapp.employee.employee.domain.Employee;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code EmployeeChanged} Avro schema from the classpath ({@code
 * avro/EmployeeChanged.avsc}) and builds {@link GenericRecord}s from it — no Avro code-generation
 * plugin (the same approach org-service uses for its events). The schema is the single source of
 * truth registered in {@code docs/EVENT-CATALOG.md}; this class parses it once and projects an
 * {@link Employee} onto it.
 *
 * <p>Field shape matches ARCHITECTURE.md §5 / EVENT-CATALOG: {@code employee_id}, {@code
 * company_id}, {@code status}. <strong>NO PII is carried</strong> — the event never includes the
 * name, NIK, or bank account (rule 6). A consumer that needs the person's identity reads its own
 * slice; it does not receive PII over the event.
 */
public final class EmployeeChangedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/EmployeeChanged.avsc";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "EmployeeChanged";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "employee";

  private EmployeeChangedSchema() {
    // static holder
  }

  /**
   * Lazy holder: the {@code .avsc} is parsed on FIRST use, not in a static field initializer. An
   * eager static initializer makes a parse failure surface as an {@code
   * ExceptionInInitializerError} /{@code NoClassDefFoundError} on the first class load — which,
   * under a partial test selection, depends on which class happens to touch the schema first and
   * flakes by execution order. The initialization-on-demand holder defers the parse to {@link
   * #schema()}/{@link #toRecord} and surfaces any failure as a normal, repeatable exception at the
   * call site.
   */
  private static final class Holder {
    private static final Schema SCHEMA = parse();

    private Holder() {}
  }

  /** The parsed reader/writer schema for {@code EmployeeChanged}. */
  public static Schema schema() {
    return Holder.SCHEMA;
  }

  /**
   * Builds an {@code EmployeeChanged} {@link GenericRecord} from a persisted employee. Only the
   * non-PII identity + status reach the event.
   *
   * @param employee the persisted employee aggregate
   */
  public static GenericRecord toRecord(Employee employee) {
    GenericRecord record = new GenericData.Record(Holder.SCHEMA);
    record.put("employee_id", employee.getId().toString());
    record.put("company_id", employee.getCompanyId());
    record.put("status", employee.getStatus().name());
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        EmployeeChangedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
