package id.co.nativeapp.employee.expense;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.expense.messaging.ExpenseClaimApprovedSchema;
import id.co.nativeapp.events.AvroSerde;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code ExpenseClaimApproved} PRODUCER contract test (rule-7 triad, no Spring context; ADR
 * 0030 E0 — contracts-first). Asserts the registered schema parses with the documented shape,
 * round-trips through {@code libs/events} {@link AvroSerde} (the exact outbox wire path), and that
 * an incompatible evolution (a required field without a default) is rejected.
 */
class ExpenseClaimApprovedContractTest {

  @Test
  void schemaParsesFromClasspathWithTheDocumentedShape() {
    Schema schema = ExpenseClaimApprovedSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.employee.ExpenseClaimApproved");
    assertThat(schema.getField("claim_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("org_unit_id")).isNotNull();
    assertThat(schema.getField("employee_id")).isNotNull();
    assertThat(schema.getField("amount_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
    assertThat(schema.getField("gl_hint")).isNotNull();
    assertThat(schema.getField("expense_date").schema().getLogicalType().getName())
        .isEqualTo("date");
    assertThat(schema.getField("approved_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = ExpenseClaimApprovedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("claim_id", "55555555-5555-5555-5555-555555555555");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("org_unit_id", "22222222-2222-2222-2222-222222222222");
    record.put("employee_id", "33333333-3333-3333-3333-333333333333");
    record.put("amount_minor", 250_000L); // money is integer minor units, never a float
    record.put("currency", "IDR");
    record.put("gl_hint", "supplies");
    record.put("expense_date", 20_670); // epoch days
    record.put("approved_at", 1_786_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("claim_id").toString())
        .isEqualTo("55555555-5555-5555-5555-555555555555");
    assertThat(decoded.get("amount_minor")).isEqualTo(250_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("gl_hint").toString()).isEqualTo("supplies");
    assertThat(decoded.get("expense_date")).isEqualTo(20_670);
    assertThat(decoded.get("approved_at")).isEqualTo(1_786_000_000_000L);
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema registered = ExpenseClaimApprovedSchema.schema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "ExpenseClaimApproved",
                  "namespace": "id.co.nativeapp.events.employee",
                  "fields": [
                    {"name": "claim_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "org_unit_id", "type": "string"},
                    {"name": "employee_id", "type": "string"},
                    {"name": "amount_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "gl_hint", "type": "string"},
                    {"name": "expense_date", "type": {"type": "int", "logicalType": "date"}},
                    {"name": "approved_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "approver_id", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(registered, incompatible)).isFalse();
  }

  @Test
  void addingAnOptionalFieldWithADefaultStaysBackwardCompatible() {
    Schema registered = ExpenseClaimApprovedSchema.schema();
    Schema evolved =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "ExpenseClaimApproved",
                  "namespace": "id.co.nativeapp.events.employee",
                  "fields": [
                    {"name": "claim_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "org_unit_id", "type": "string"},
                    {"name": "employee_id", "type": "string"},
                    {"name": "amount_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "gl_hint", "type": "string"},
                    {"name": "expense_date", "type": {"type": "int", "logicalType": "date"}},
                    {"name": "approved_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "approver_note", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(registered, evolved)).isTrue();
  }
}
