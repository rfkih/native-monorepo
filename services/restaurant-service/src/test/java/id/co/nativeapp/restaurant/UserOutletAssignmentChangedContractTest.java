package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.outletref.messaging.UserOutletAssignmentChangedSchemas;
import id.co.nativeapp.restaurant.outletref.messaging.UserOutletAssignmentEvent;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Consumer-driven contract test for the {@code UserOutletAssignmentChanged} event (Phase 5).
 *
 * <p>Verifies the rule-7 triad (CLAUDE.md rule 7 / docs/EVENT-CATALOG.md):
 *
 * <ol>
 *   <li>The consumer schema parses from the classpath with the expected shape and full name.
 *   <li>A {@link GenericRecord} round-trips through {@link AvroSerde} — the exact path the
 *       {@link id.co.nativeapp.restaurant.outletref.messaging.UserOutletAssignmentListener} takes
 *       off the wire.
 *   <li>The consumer schema is BACKWARD-COMPATIBLE with the producer schema (docs/EVENT-CATALOG.md
 *       anchor): a restaurant reader can decode bytes the org-service producer wrote.
 *   <li>Producer bytes decoded under the consumer schema produce the correct {@link
 *       UserOutletAssignmentEvent} record via {@link UserOutletAssignmentChangedSchemas#decode}.
 *   <li>Adding a required field to the consumer schema (without a default) is correctly rejected as
 *       a breaking change — the gate that prevents a consumer from silently hardening the contract.
 * </ol>
 *
 * <p>No Spring context, no database, no Kafka — just Avro schema + serde logic. Runs as a fast
 * unit test in every CI pass.
 */
class UserOutletAssignmentChangedContractTest {

  private static final String COMPANY_ID = "11111111-1111-1111-1111-111111111111";
  private static final String ORG_UNIT_ID = "22222222-2222-2222-2222-222222222222";
  private static final String USER_ID = "kc-user-cashier-01";
  private static final String ASSIGNMENT_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

  /**
   * The producer's schema as registered in docs/EVENT-CATALOG.md — this is the contract anchor.
   * Field names/types/order must match the catalog schema exactly ({@code doc} strings may be
   * abbreviated here — Avro ignores them for compatibility).
   */
  private static final String PRODUCER_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "UserOutletAssignmentChanged",
        "namespace": "id.co.nativeapp.events.org",
        "doc": "Emitted by org-service whenever a user→outlet assignment changes state. Carries the POST-CHANGE state; consumers upsert idempotently.",
        "fields": [
          {"name": "assignment_id", "type": "string", "doc": "UUID of the assignment row."},
          {"name": "user_id",       "type": "string", "doc": "Keycloak subject id of the user."},
          {"name": "company_id",    "type": "string", "doc": "Tenant UUID."},
          {"name": "org_unit_id",   "type": "string", "doc": "The outlet (org_unit) UUID."},
          {"name": "change_kind",   "type": "string", "doc": "ASSIGNED | UNASSIGNED"},
          {"name": "effective_from","type": {"type": "int", "logicalType": "date"}, "doc": "Inclusive start date (epoch day)."},
          {"name": "effective_to",  "type": {"type": "int", "logicalType": "date"}, "doc": "Inclusive end date (epoch day); 9999-12-31 sentinel for open-ended."}
        ]
      }
      """;

  // -----------------------------------------------------------------------
  // 1. Schema shape
  // -----------------------------------------------------------------------

  @Test
  void schemaParsesFromClasspathWithExpectedShape() {
    Schema schema = UserOutletAssignmentChangedSchemas.schema();

    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.org.UserOutletAssignmentChanged");
    assertThat(schema.getField("assignment_id")).isNotNull();
    assertThat(schema.getField("user_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("org_unit_id")).isNotNull();
    assertThat(schema.getField("change_kind")).isNotNull();
    assertThat(schema.getField("effective_from")).isNotNull();
    assertThat(schema.getField("effective_to")).isNotNull();
    // effective_from / effective_to are int with date logical type
    assertThat(schema.getField("effective_from").schema().getType()).isEqualTo(Schema.Type.INT);
    assertThat(schema.getField("effective_to").schema().getType()).isEqualTo(Schema.Type.INT);
  }

  // -----------------------------------------------------------------------
  // 2. Round-trip through AvroSerde (ASSIGNED)
  // -----------------------------------------------------------------------

  @Test
  void assignedEventRoundTripsThroughAvroSerde() {
    Schema schema = UserOutletAssignmentChangedSchemas.schema();
    int from = (int) LocalDate.of(2026, 7, 27).toEpochDay();
    int to = (int) LocalDate.of(9999, 12, 31).toEpochDay();

    GenericRecord record = new GenericData.Record(schema);
    record.put("assignment_id", ASSIGNMENT_ID);
    record.put("user_id", USER_ID);
    record.put("company_id", COMPANY_ID);
    record.put("org_unit_id", ORG_UNIT_ID);
    record.put("change_kind", "ASSIGNED");
    record.put("effective_from", from);
    record.put("effective_to", to);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("change_kind").toString()).isEqualTo("ASSIGNED");
    assertThat(decoded.get("user_id").toString()).isEqualTo(USER_ID);
    assertThat(decoded.get("org_unit_id").toString()).isEqualTo(ORG_UNIT_ID);
    assertThat(decoded.get("effective_from")).isEqualTo(from);
    assertThat(decoded.get("effective_to")).isEqualTo(to);
  }

  @Test
  void unassignedEventRoundTripsThroughAvroSerde() {
    Schema schema = UserOutletAssignmentChangedSchemas.schema();
    int from = (int) LocalDate.of(2026, 7, 1).toEpochDay();
    int to = (int) LocalDate.of(2026, 7, 26).toEpochDay(); // closed-ended

    GenericRecord record = new GenericData.Record(schema);
    record.put("assignment_id", ASSIGNMENT_ID);
    record.put("user_id", USER_ID);
    record.put("company_id", COMPANY_ID);
    record.put("org_unit_id", ORG_UNIT_ID);
    record.put("change_kind", "UNASSIGNED");
    record.put("effective_from", from);
    record.put("effective_to", to);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("change_kind").toString()).isEqualTo("UNASSIGNED");
    assertThat(decoded.get("effective_to")).isEqualTo(to);
  }

  // -----------------------------------------------------------------------
  // 3. Backward compatibility with the producer schema
  // -----------------------------------------------------------------------

  @Test
  void consumerCopyIsBackwardCompatibleWithProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = UserOutletAssignmentChangedSchemas.schema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void producerBytesDecodeUnderConsumerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = UserOutletAssignmentChangedSchemas.schema();

    int from = (int) LocalDate.of(2026, 7, 27).toEpochDay();
    int to = (int) LocalDate.of(9999, 12, 31).toEpochDay();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("assignment_id", ASSIGNMENT_ID);
    produced.put("user_id", USER_ID);
    produced.put("company_id", COMPANY_ID);
    produced.put("org_unit_id", ORG_UNIT_ID);
    produced.put("change_kind", "ASSIGNED");
    produced.put("effective_from", from);
    produced.put("effective_to", to);

    byte[] wire = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wire, producer, consumer);
    assertThat(decoded.get("change_kind").toString()).isEqualTo("ASSIGNED");
    assertThat(decoded.get("effective_from")).isEqualTo(from);
    assertThat(decoded.get("effective_to")).isEqualTo(to);
  }

  // -----------------------------------------------------------------------
  // 4. decode() produces the correct UserOutletAssignmentEvent
  // -----------------------------------------------------------------------

  @Test
  void decodeProducesCorrectAssignedEvent() {
    Schema schema = UserOutletAssignmentChangedSchemas.schema();
    int from = (int) LocalDate.of(2026, 7, 27).toEpochDay();
    int to = (int) LocalDate.of(9999, 12, 31).toEpochDay();

    GenericRecord record = new GenericData.Record(schema);
    record.put("assignment_id", ASSIGNMENT_ID);
    record.put("user_id", USER_ID);
    record.put("company_id", COMPANY_ID);
    record.put("org_unit_id", ORG_UNIT_ID);
    record.put("change_kind", "ASSIGNED");
    record.put("effective_from", from);
    record.put("effective_to", to);

    byte[] bytes = AvroSerde.serialize(record);
    UUID eventId = UUID.randomUUID();
    UserOutletAssignmentEvent event = UserOutletAssignmentChangedSchemas.decode(eventId, bytes);

    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.assignmentId()).isEqualTo(UUID.fromString(ASSIGNMENT_ID));
    assertThat(event.userId()).isEqualTo(USER_ID);
    assertThat(event.companyId()).isEqualTo(COMPANY_ID);
    assertThat(event.orgUnitId()).isEqualTo(UUID.fromString(ORG_UNIT_ID));
    assertThat(event.changeKind()).isEqualTo("ASSIGNED");
    assertThat(event.effectiveFrom()).isEqualTo(from);
    assertThat(event.effectiveTo()).isEqualTo(to);
    assertThat(event.isActive()).isTrue();
  }

  @Test
  void decodeProducesCorrectUnassignedEvent() {
    Schema schema = UserOutletAssignmentChangedSchemas.schema();
    int from = (int) LocalDate.of(2026, 7, 1).toEpochDay();
    int to = (int) LocalDate.of(2026, 7, 26).toEpochDay();

    GenericRecord record = new GenericData.Record(schema);
    record.put("assignment_id", ASSIGNMENT_ID);
    record.put("user_id", USER_ID);
    record.put("company_id", COMPANY_ID);
    record.put("org_unit_id", ORG_UNIT_ID);
    record.put("change_kind", "UNASSIGNED");
    record.put("effective_from", from);
    record.put("effective_to", to);

    byte[] bytes = AvroSerde.serialize(record);
    UUID eventId = UUID.randomUUID();
    UserOutletAssignmentEvent event = UserOutletAssignmentChangedSchemas.decode(eventId, bytes);

    assertThat(event.isActive()).isFalse();
    assertThat(event.changeKind()).isEqualTo("UNASSIGNED");
  }

  // -----------------------------------------------------------------------
  // 5. Adding a required field without a default breaks backward compatibility
  // -----------------------------------------------------------------------

  @Test
  void addingRequiredFieldBreaksBackwardCompatibility() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    // A consumer schema with a new REQUIRED field (no default) — a consumer cannot read old bytes.
    Schema broken =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "UserOutletAssignmentChanged",
                  "namespace": "id.co.nativeapp.events.org",
                  "fields": [
                    {"name": "assignment_id", "type": "string"},
                    {"name": "user_id",       "type": "string"},
                    {"name": "company_id",    "type": "string"},
                    {"name": "org_unit_id",   "type": "string"},
                    {"name": "change_kind",   "type": "string"},
                    {"name": "effective_from","type": {"type": "int", "logicalType": "date"}},
                    {"name": "effective_to",  "type": {"type": "int", "logicalType": "date"}},
                    {"name": "reason_code",   "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, broken)).isFalse();
  }
}
