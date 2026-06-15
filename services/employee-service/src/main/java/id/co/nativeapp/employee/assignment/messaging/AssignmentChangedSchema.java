package id.co.nativeapp.employee.assignment.messaging;

import id.co.nativeapp.employee.assignment.domain.Assignment;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code AssignmentChanged} Avro schema from the classpath ({@code
 * avro/AssignmentChanged.avsc}) and builds {@link GenericRecord}s from it — no Avro code-generation
 * plugin (the same approach org-service uses for its events). The schema is the single source of
 * truth registered in {@code docs/EVENT-CATALOG.md}; this class parses it once and projects an
 * {@link Assignment} onto it.
 *
 * <p>Field shape matches ARCHITECTURE.md §5 / EVENT-CATALOG: {@code employee_id}, {@code
 * org_unit_id}, {@code reporting_to}, {@code effective_from}/{@code effective_to} (+ {@code
 * assignment_id}, {@code company_id}, {@code role}). {@code reporting_to} is a nullable union (an
 * assignment may have no manager); the effective dates are Avro {@code date} logical-type ints
 * (epoch day). <strong>NO PII</strong> is on the event (rule 6).
 */
public final class AssignmentChangedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/AssignmentChanged.avsc";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "AssignmentChanged";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "assignment";

  private AssignmentChangedSchema() {
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

  /** The parsed reader/writer schema for {@code AssignmentChanged}. */
  public static Schema schema() {
    return Holder.SCHEMA;
  }

  /**
   * Builds an {@code AssignmentChanged} {@link GenericRecord} from a persisted assignment.
   *
   * @param assignment the persisted assignment aggregate
   */
  public static GenericRecord toRecord(Assignment assignment) {
    GenericRecord record = new GenericData.Record(Holder.SCHEMA);
    record.put("assignment_id", assignment.getId().toString());
    record.put("employee_id", assignment.getEmployeeId().toString());
    record.put("company_id", assignment.getCompanyId());
    record.put("org_unit_id", assignment.getOrgUnitId().toString());
    record.put(
        "reporting_to",
        assignment.getReportingTo() == null ? null : assignment.getReportingTo().toString());
    record.put("role", assignment.getRole());
    // Avro `date` logical type is the epoch day as an int; the writer represents it as an int.
    record.put("effective_from", (int) assignment.getEffectiveFrom().toEpochDay());
    record.put("effective_to", (int) assignment.getEffectiveTo().toEpochDay());
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        AssignmentChangedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
