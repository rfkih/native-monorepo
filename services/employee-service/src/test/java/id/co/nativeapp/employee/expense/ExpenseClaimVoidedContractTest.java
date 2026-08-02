package id.co.nativeapp.employee.expense;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.expense.messaging.ExpenseClaimVoidedSchema;
import id.co.nativeapp.events.AvroSerde;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code ExpenseClaimVoided} PRODUCER contract test (rule-7 triad, no Spring context; ADR 0030
 * E0). The void contra carries BOTH instants: {@code approved_at} (mapping resolved as-of the
 * original approval) and {@code voided_at} (the period the contra posts into).
 */
class ExpenseClaimVoidedContractTest {

  @Test
  void schemaParsesFromClasspathWithTheDocumentedShape() {
    Schema schema = ExpenseClaimVoidedSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.employee.ExpenseClaimVoided");
    assertThat(schema.getField("claim_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("org_unit_id")).isNotNull();
    assertThat(schema.getField("employee_id")).isNotNull();
    assertThat(schema.getField("amount_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
    assertThat(schema.getField("gl_hint")).isNotNull();
    assertThat(schema.getField("approved_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
    assertThat(schema.getField("voided_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = ExpenseClaimVoidedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("claim_id", "55555555-5555-5555-5555-555555555555");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("org_unit_id", "22222222-2222-2222-2222-222222222222");
    record.put("employee_id", "33333333-3333-3333-3333-333333333333");
    record.put("amount_minor", 250_000L);
    record.put("currency", "IDR");
    record.put("gl_hint", "supplies");
    record.put("approved_at", 1_786_000_000_000L);
    record.put("voided_at", 1_786_100_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("amount_minor")).isEqualTo(250_000L);
    assertThat(decoded.get("approved_at")).isEqualTo(1_786_000_000_000L);
    assertThat(decoded.get("voided_at")).isEqualTo(1_786_100_000_000L);
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema registered = ExpenseClaimVoidedSchema.schema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "ExpenseClaimVoided",
                  "namespace": "id.co.nativeapp.events.employee",
                  "fields": [
                    {"name": "claim_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "org_unit_id", "type": "string"},
                    {"name": "employee_id", "type": "string"},
                    {"name": "amount_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "gl_hint", "type": "string"},
                    {"name": "approved_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "voided_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "void_reason", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(registered, incompatible)).isFalse();
  }
}
