package id.co.nativeapp.barbershop.staff.messaging;

import id.co.nativeapp.barbershop.staff.dto.StaffProjectedEvent;
import id.co.nativeapp.barbershop.staff.dto.StaffProjectedEvent.Kind;
import id.co.nativeapp.events.AvroSerde;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads barbershop-service's CONSUMER copies of the {@code EmployeeChanged} / {@code
 * AssignmentChanged} Avro schemas from the classpath and decodes raw Avro bytes into the {@link
 * StaffProjectedEvent} the consumer applies — no Avro code-generation plugin, and no Confluent /
 * Schema Registry serde. barbershop-service owns its own consumer view of each contract (full name
 * {@code id.co.nativeapp.events.employee.EmployeeChanged} / {@code .AssignmentChanged}, matching
 * docs/EVENT-CATALOG.md); a contract test asserts these copies stay backward-compatible with the
 * producer schemas (rule 7). NO PII is read — the events carry none (rule 6).
 *
 * <p>The wire bytes are the employee-service outbox payload (raw Avro), so we deserialize with
 * {@code libs/events} {@link AvroSerde} against the parsed schema.
 *
 * <p>No local {@code .avsc} copy is added under this service's own {@code src/main/resources/avro/}
 * — the schema resolves from the shared {@code libs/contracts} classpath resource, as this service
 * already depends on {@code libs/contracts}.
 */
public final class StaffEventSchemas {

  /** Classpath location of the {@code EmployeeChanged} consumer-copy {@code .avsc}. */
  public static final String EMPLOYEE_RESOURCE = "avro/EmployeeChanged.avsc";

  /** Classpath location of the {@code AssignmentChanged} consumer-copy {@code .avsc}. */
  public static final String ASSIGNMENT_RESOURCE = "avro/AssignmentChanged.avsc";

  /** The {@code EmployeeChanged} Kafka topic (one topic per event type). */
  public static final String EMPLOYEE_TOPIC = "EmployeeChanged";

  /** The {@code AssignmentChanged} Kafka topic. */
  public static final String ASSIGNMENT_TOPIC = "AssignmentChanged";

  /** The employee status value treated as active in the staff read model. */
  private static final String ACTIVE_STATUS = "ACTIVE";

  private StaffEventSchemas() {
    // static holder
  }

  /**
   * Lazy holder: each {@code .avsc} is parsed on FIRST use, not in a static field initializer, so a
   * parse failure surfaces as a normal, repeatable exception at the call site.
   */
  private static final class Holder {
    private static final Schema EMPLOYEE_SCHEMA = parse(EMPLOYEE_RESOURCE);
    private static final Schema ASSIGNMENT_SCHEMA = parse(ASSIGNMENT_RESOURCE);

    private Holder() {}
  }

  /** The parsed reader schema for {@code EmployeeChanged} (barbershop's consumer copy). */
  public static Schema employeeSchema() {
    return Holder.EMPLOYEE_SCHEMA;
  }

  /** The parsed reader schema for {@code AssignmentChanged} (barbershop's consumer copy). */
  public static Schema assignmentSchema() {
    return Holder.ASSIGNMENT_SCHEMA;
  }

  /**
   * Decodes raw {@code EmployeeChanged} Avro bytes into a projected event: the employee's id +
   * active state. {@code org_unit_id} is not on this event, so it is {@code null} (the writer
   * retains the existing assignment).
   *
   * @param eventId the event's UUID — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static StaffProjectedEvent decodeEmployee(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, Holder.EMPLOYEE_SCHEMA);
    boolean active = ACTIVE_STATUS.equals(record.get("status").toString());
    return new StaffProjectedEvent(
        eventId,
        record.get("company_id").toString(),
        UUID.fromString(record.get("employee_id").toString()),
        Kind.EMPLOYEE,
        null,
        active);
  }

  /**
   * Decodes raw {@code AssignmentChanged} Avro bytes into a projected event: the employee's id +
   * the org unit they are assigned to. The active flag is not carried on an assignment event (the
   * writer retains the existing active value, defaulting a never-seen employee to active).
   *
   * @param eventId the event's UUID — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static StaffProjectedEvent decodeAssignment(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, Holder.ASSIGNMENT_SCHEMA);
    return new StaffProjectedEvent(
        eventId,
        record.get("company_id").toString(),
        UUID.fromString(record.get("employee_id").toString()),
        Kind.ASSIGNMENT,
        UUID.fromString(record.get("org_unit_id").toString()),
        true);
  }

  private static Schema parse(String resource) {
    try (InputStream in = StaffEventSchemas.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + resource);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + resource, e);
    }
  }
}
