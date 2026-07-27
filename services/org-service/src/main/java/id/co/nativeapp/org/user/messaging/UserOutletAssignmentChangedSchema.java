package id.co.nativeapp.org.user.messaging;

import id.co.nativeapp.org.user.domain.UserOutletAssignment;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code UserOutletAssignmentChanged} Avro schema from the classpath ({@code
 * avro/UserOutletAssignmentChanged.avsc}, provided by {@code libs/contracts} — ADR 0003) and builds
 * {@link GenericRecord}s from it — no Avro code-generation plugin (same approach as {@link
 * id.co.nativeapp.org.company.messaging.OrgUnitCreatedSchema}).
 *
 * <p>The schema is the single source of truth registered in {@code docs/EVENT-CATALOG.md}; this
 * class parses it once at class-load time and projects a {@link UserOutletAssignment} + a {@code
 * change_kind} string onto it.
 *
 * <p><strong>Effective dates as Avro {@code date} ints (epoch day).</strong> {@code effective_from}
 * and {@code effective_to} are stored as {@code int} (Avro {@code date} logical type — days since
 * epoch) matching the {@code AssignmentChanged} and {@code GroupMembershipChanged} precedents in the
 * event catalog. Consumers read them as epoch-day ints.
 */
public final class UserOutletAssignmentChangedSchema {

  /** Classpath location of the {@code .avsc} (from {@code libs/contracts}; also in the catalog). */
  public static final String RESOURCE = "avro/UserOutletAssignmentChanged.avsc";

  /** The event name as it appears in the outbox {@code event_type} column and on the Kafka topic. */
  public static final String EVENT_TYPE = "UserOutletAssignmentChanged";

  /** The producing aggregate kind (outbox {@code aggregate_type}). */
  public static final String AGGREGATE_TYPE = "user_outlet_assignment";

  /**
   * {@code change_kind} value emitted when a row was CREATED or REOPENED (active → true).
   *
   * <p>Consumers map this to {@code active = true} in their local read model.
   */
  public static final String KIND_ASSIGNED = "ASSIGNED";

  /**
   * {@code change_kind} value emitted when a row was CLOSED (active → false).
   *
   * <p>Consumers map this to {@code active = false} in their local read model.
   */
  public static final String KIND_UNASSIGNED = "UNASSIGNED";

  private static final Schema SCHEMA = parse();

  private UserOutletAssignmentChangedSchema() {
    // static holder
  }

  /** The parsed reader/writer schema for {@code UserOutletAssignmentChanged}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds an {@code ASSIGNED} {@link GenericRecord} from a persisted assignment row that was
   * <em>created</em> or <em>reopened</em>. {@code effective_to} is the far-future sentinel ({@link
   * UserOutletAssignment#OPEN_ENDED}).
   *
   * @param assignment the persisted domain object (active = true after the write)
   */
  public static GenericRecord toAssigned(UserOutletAssignment assignment) {
    return toRecord(assignment, KIND_ASSIGNED, assignment.getEffectiveTo());
  }

  /**
   * Builds an {@code UNASSIGNED} {@link GenericRecord} from a persisted assignment row that was
   * <em>closed</em>. {@code effective_to} carries today's date (the closing date, already stamped on
   * the domain object by {@link UserOutletAssignment#close(java.time.LocalDate)}).
   *
   * @param assignment the persisted domain object (active = false after the write)
   */
  public static GenericRecord toUnassigned(UserOutletAssignment assignment) {
    return toRecord(assignment, KIND_UNASSIGNED, assignment.getEffectiveTo());
  }

  // -------------------------------------------------------------------------

  private static GenericRecord toRecord(
      UserOutletAssignment assignment, String changeKind, LocalDate effectiveTo) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("assignment_id", assignment.getId().toString());
    record.put("user_id", assignment.getUserId());
    record.put("company_id", assignment.getCompanyId());
    record.put("org_unit_id", assignment.getOrgUnitId().toString());
    record.put("change_kind", changeKind);
    // Avro date logical type is stored as epoch-day int.
    record.put("effective_from", toEpochDay(assignment.getEffectiveFrom()));
    record.put("effective_to", toEpochDay(effectiveTo));
    return record;
  }

  private static int toEpochDay(LocalDate date) {
    // LocalDate.toEpochDay() is a long; Avro date is an int (enough for centuries around epoch).
    return Math.toIntExact(date.toEpochDay());
  }

  private static Schema parse() {
    try (InputStream in =
        UserOutletAssignmentChangedSchema.class
            .getClassLoader()
            .getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
