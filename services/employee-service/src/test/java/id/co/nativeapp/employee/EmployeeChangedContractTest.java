package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.employee.messaging.EmployeeChangedSchema;
import id.co.nativeapp.events.AvroSerde;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code EmployeeChanged} PRODUCER contract triad (rule 7 / ENGINEERING-STANDARDS §3.2): the
 * schema parses from the classpath with the expected shape, a {@link GenericRecord} round-trips
 * through {@code libs/events} {@link AvroSerde}, and a real (additive) change stays
 * backward-compatible while a deliberate break (a new required field with no default) is rejected.
 *
 * <p>Also pins that the event carries <strong>NO PII</strong> (rule 6) — only employee_id /
 * company_id / status; there is no name, NIK, or bank-account field on the schema.
 */
class EmployeeChangedContractTest {

  @Test
  void avscParsesFromClasspathWithTheExpectedShape() {
    Schema schema = EmployeeChangedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.employee.EmployeeChanged");
    assertThat(schema.getField("employee_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("status")).isNotNull();
  }

  @Test
  void carriesNoPiiFields() {
    Schema schema = EmployeeChangedSchema.schema();
    // The event must never carry the name, NIK, or bank account (rule 6).
    assertThat(schema.getField("full_name")).isNull();
    assertThat(schema.getField("name")).isNull();
    assertThat(schema.getField("nik")).isNull();
    assertThat(schema.getField("bank_account")).isNull();
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = EmployeeChangedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("employee_id", "44444444-4444-4444-4444-444444444444");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("status", "ACTIVE");

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("employee_id").toString())
        .isEqualTo("44444444-4444-4444-4444-444444444444");
    assertThat(decoded.get("status").toString()).isEqualTo("ACTIVE");
  }

  @Test
  void addingAnOptionalFieldStaysBackwardCompatible() {
    Schema current = EmployeeChangedSchema.schema();
    Schema evolved =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "EmployeeChanged",
                  "namespace": "id.co.nativeapp.events.employee",
                  "fields": [
                    {"name": "employee_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "status", "type": "string"},
                    {"name": "display_name", "type": ["null","string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(current, evolved)).isTrue();
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema current = EmployeeChangedSchema.schema();
    Schema broken =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "EmployeeChanged",
                  "namespace": "id.co.nativeapp.events.employee",
                  "fields": [
                    {"name": "employee_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "status", "type": "string"},
                    {"name": "department_id", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(current, broken)).isFalse();
  }
}
