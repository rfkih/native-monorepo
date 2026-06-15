package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.assignment.messaging.AssignmentChangedSchema;
import id.co.nativeapp.events.AvroSerde;
import java.time.LocalDate;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code AssignmentChanged} PRODUCER contract triad (rule 7 / ENGINEERING-STANDARDS §3.2): the
 * schema parses with the expected shape (the EVENT-CATALOG key fields employee_id, org_unit_id,
 * reporting_to, effective_from/to), a {@link GenericRecord} round-trips through {@code libs/events}
 * {@link AvroSerde} (including the nullable {@code reporting_to} union and the {@code date}
 * logical-type fields), and an additive change stays backward-compatible while a deliberate break
 * is rejected. The event carries NO PII (rule 6).
 */
class AssignmentChangedContractTest {

  @Test
  void avscParsesFromClasspathWithTheExpectedShape() {
    Schema schema = AssignmentChangedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.employee.AssignmentChanged");
    assertThat(schema.getField("employee_id")).isNotNull();
    assertThat(schema.getField("org_unit_id")).isNotNull();
    assertThat(schema.getField("reporting_to")).isNotNull();
    assertThat(schema.getField("effective_from").schema().getLogicalType().getName())
        .isEqualTo("date");
    assertThat(schema.getField("effective_to").schema().getLogicalType().getName())
        .isEqualTo("date");
    // reporting_to is a nullable union (an assignment may have no manager).
    assertThat(schema.getField("reporting_to").schema().getType()).isEqualTo(Schema.Type.UNION);
  }

  @Test
  void carriesNoPiiFields() {
    Schema schema = AssignmentChangedSchema.schema();
    assertThat(schema.getField("nik")).isNull();
    assertThat(schema.getField("bank_account")).isNull();
    assertThat(schema.getField("full_name")).isNull();
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerdeIncludingNullManager() {
    Schema schema = AssignmentChangedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("assignment_id", "55555555-5555-5555-5555-555555555555");
    record.put("employee_id", "44444444-4444-4444-4444-444444444444");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("org_unit_id", "22222222-2222-2222-2222-222222222222");
    record.put("reporting_to", null); // no manager
    record.put("role", "cashier");
    record.put("effective_from", (int) LocalDate.of(2026, 6, 1).toEpochDay());
    record.put("effective_to", (int) LocalDate.of(9999, 12, 31).toEpochDay());

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("reporting_to")).isNull();
    assertThat(decoded.get("role").toString()).isEqualTo("cashier");
    assertThat(decoded.get("effective_from"))
        .isEqualTo((int) LocalDate.of(2026, 6, 1).toEpochDay());
  }

  @Test
  void addingAnOptionalFieldStaysBackwardCompatible() {
    Schema current = AssignmentChangedSchema.schema();
    Schema evolved =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "AssignmentChanged",
                  "namespace": "id.co.nativeapp.events.employee",
                  "fields": [
                    {"name": "assignment_id", "type": "string"},
                    {"name": "employee_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "org_unit_id", "type": "string"},
                    {"name": "reporting_to", "type": ["null","string"], "default": null},
                    {"name": "role", "type": "string"},
                    {"name": "effective_from", "type": {"type": "int", "logicalType": "date"}},
                    {"name": "effective_to", "type": {"type": "int", "logicalType": "date"}},
                    {"name": "change_kind", "type": ["null","string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(current, evolved)).isTrue();
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema current = AssignmentChangedSchema.schema();
    Schema broken =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "AssignmentChanged",
                  "namespace": "id.co.nativeapp.events.employee",
                  "fields": [
                    {"name": "assignment_id", "type": "string"},
                    {"name": "employee_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "org_unit_id", "type": "string"},
                    {"name": "reporting_to", "type": ["null","string"], "default": null},
                    {"name": "role", "type": "string"},
                    {"name": "effective_from", "type": {"type": "int", "logicalType": "date"}},
                    {"name": "effective_to", "type": {"type": "int", "logicalType": "date"}},
                    {"name": "cost_center", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(current, broken)).isFalse();
  }
}
