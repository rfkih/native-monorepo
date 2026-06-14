package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.carwash.staff.StaffEventSchemas;
import id.co.nativeapp.events.AvroSerde;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

/**
 * Test (f) — the CONSUMER-copy contract test for {@code EmployeeChanged} / {@code
 * AssignmentChanged} (no Spring context; rule 7).
 *
 * <p>Each consumer copy parses with the expected full name + the §5 key fields, stays mutually
 * backward-compatible with the employee-service PRODUCER schema (inlined from
 * docs/EVENT-CATALOG.md), and the back-compat gate rejects a new required field with no default. It
 * also pins that NO PII field is present on either consumer copy (rule 6).
 */
class StaffConsumerContractTest {

  private static final Schema PRODUCER_EMPLOYEE =
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
                  {"name": "status", "type": "string"}
                ]
              }
              """);

  private static final Schema PRODUCER_ASSIGNMENT =
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
                  {"name": "reporting_to", "type": ["null", "string"], "default": null},
                  {"name": "role", "type": "string"},
                  {"name": "effective_from", "type": {"type": "int", "logicalType": "date"}},
                  {"name": "effective_to", "type": {"type": "int", "logicalType": "date"}}
                ]
              }
              """);

  @Test
  void employeeChangedConsumerCopyParsesMatchesProducerAndCarriesNoPii() {
    Schema schema = StaffEventSchemas.employeeSchema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.employee.EmployeeChanged");
    assertThat(schema.getField("employee_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("status")).isNotNull();
    // NO PII (rule 6): the event never carries name / NIK / bank account.
    assertThat(schema.getField("full_name")).isNull();
    assertThat(schema.getField("nik")).isNull();
    assertThat(schema.getField("bank_account")).isNull();
    assertThat(AvroSerde.isBackwardCompatible(PRODUCER_EMPLOYEE, schema)).isTrue();
    assertThat(AvroSerde.isBackwardCompatible(schema, PRODUCER_EMPLOYEE)).isTrue();
  }

  @Test
  void assignmentChangedConsumerCopyParsesAndMatchesTheProducer() {
    Schema schema = StaffEventSchemas.assignmentSchema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.employee.AssignmentChanged");
    assertThat(schema.getField("employee_id")).isNotNull();
    assertThat(schema.getField("org_unit_id")).isNotNull();
    assertThat(schema.getField("effective_to").schema().getLogicalType().getName())
        .isEqualTo("date");
    assertThat(AvroSerde.isBackwardCompatible(PRODUCER_ASSIGNMENT, schema)).isTrue();
    assertThat(AvroSerde.isBackwardCompatible(schema, PRODUCER_ASSIGNMENT)).isTrue();
  }

  @Test
  void employeeChangedRejectsANewRequiredFieldWithoutDefault() {
    Schema v1 = StaffEventSchemas.employeeSchema();
    Schema incompatible =
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
                    {"name": "department", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }
}
