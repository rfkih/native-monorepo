package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.empexpense.messaging.ExpenseClaimVoidedSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code ExpenseClaimVoided} CONSUMER contract test (rule-7 triad, no Spring context; ADR 0030
 * E0). The contra resolves the mapping effective at the ORIGINAL {@code approved_at} and posts into
 * the period of {@code voided_at} — both instants must survive the wire.
 */
class ExpenseClaimVoidedContractTest {

  /** The producer's registered schema, copied verbatim from the catalog (drift detector). */
  private static final String PRODUCER_SCHEMA_JSON =
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
          {"name": "voided_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
        ]
      }
      """;

  @Test
  void consumerViewParsesFromClasspath() {
    Schema schema = ExpenseClaimVoidedSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.employee.ExpenseClaimVoided");
    assertThat(schema.getField("amount_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("approved_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
    assertThat(schema.getField("voided_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void consumerViewIsBackwardCompatibleWithTheProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    assertThat(AvroSerde.isBackwardCompatible(producer, ExpenseClaimVoidedSchema.schema()))
        .isTrue();
  }

  @Test
  void bytesWrittenWithTheProducerSchemaDecodeUnderTheConsumerView() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = ExpenseClaimVoidedSchema.schema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("claim_id", "55555555-5555-5555-5555-555555555555");
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("org_unit_id", "22222222-2222-2222-2222-222222222222");
    produced.put("employee_id", "33333333-3333-3333-3333-333333333333");
    produced.put("amount_minor", 250_000L);
    produced.put("currency", "IDR");
    produced.put("gl_hint", "supplies");
    produced.put("approved_at", 1_786_000_000_000L);
    produced.put("voided_at", 1_786_100_000_000L);

    GenericRecord decoded =
        AvroSerde.deserialize(AvroSerde.serialize(produced), producer, consumer);

    assertThat(decoded.get("amount_minor")).isEqualTo(250_000L);
    assertThat(decoded.get("approved_at")).isEqualTo(1_786_000_000_000L);
    assertThat(decoded.get("voided_at")).isEqualTo(1_786_100_000_000L);
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
    assertThat(AvroSerde.isBackwardCompatible(producer, incompatible)).isFalse();
  }
}
