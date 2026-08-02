package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.empexpense.messaging.ExpenseClaimApprovedSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code ExpenseClaimApproved} CONSUMER contract test (rule-7 triad, no Spring context; ADR
 * 0030 E0). Proves finance's reader view parses from the classpath, stays backward-compatible with
 * the producer schema (embedded verbatim from docs/EVENT-CATALOG.md), decodes bytes a producer
 * wrote, and that an incompatible break is rejected.
 */
class ExpenseClaimApprovedContractTest {

  /** The producer's registered schema, copied verbatim from the catalog (drift detector). */
  private static final String PRODUCER_SCHEMA_JSON =
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
          {"name": "approved_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
        ]
      }
      """;

  @Test
  void consumerViewParsesFromClasspath() {
    Schema schema = ExpenseClaimApprovedSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.employee.ExpenseClaimApproved");
    assertThat(schema.getField("claim_id")).isNotNull();
    assertThat(schema.getField("org_unit_id")).isNotNull();
    assertThat(schema.getField("employee_id")).isNotNull();
    assertThat(schema.getField("amount_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("gl_hint")).isNotNull();
    assertThat(schema.getField("approved_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void consumerViewIsBackwardCompatibleWithTheProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    assertThat(AvroSerde.isBackwardCompatible(producer, ExpenseClaimApprovedSchema.schema()))
        .isTrue();
  }

  @Test
  void bytesWrittenWithTheProducerSchemaDecodeUnderTheConsumerView() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = ExpenseClaimApprovedSchema.schema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("claim_id", "55555555-5555-5555-5555-555555555555");
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("org_unit_id", "22222222-2222-2222-2222-222222222222");
    produced.put("employee_id", "33333333-3333-3333-3333-333333333333");
    produced.put("amount_minor", 250_000L); // money is integer minor units, never a float
    produced.put("currency", "IDR");
    produced.put("gl_hint", "supplies");
    produced.put("expense_date", 20_670);
    produced.put("approved_at", 1_786_000_000_000L);

    GenericRecord decoded =
        AvroSerde.deserialize(AvroSerde.serialize(produced), producer, consumer);

    assertThat(decoded.get("amount_minor")).isEqualTo(250_000L);
    assertThat(decoded.get("gl_hint").toString()).isEqualTo("supplies");
    assertThat(decoded.get("org_unit_id").toString())
        .isEqualTo("22222222-2222-2222-2222-222222222222");
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
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
    assertThat(AvroSerde.isBackwardCompatible(producer, incompatible)).isFalse();
  }
}
