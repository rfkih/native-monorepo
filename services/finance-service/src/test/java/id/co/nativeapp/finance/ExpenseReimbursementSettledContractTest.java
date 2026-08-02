package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.empexpense.messaging.ExpenseReimbursementSettledSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code ExpenseReimbursementSettled} CONSUMER contract test (rule-7 triad, no Spring context;
 * ADR 0030 E0). Finance settles ONCE per claim (the {@code employee_expense_settlement} guard) —
 * this test locks the wire shape both settlement kinds arrive in.
 */
class ExpenseReimbursementSettledContractTest {

  /** The producer's registered schema, copied verbatim from the catalog (drift detector). */
  private static final String PRODUCER_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "ExpenseReimbursementSettled",
        "namespace": "id.co.nativeapp.events.employee",
        "fields": [
          {"name": "claim_id", "type": "string"},
          {"name": "company_id", "type": "string"},
          {"name": "org_unit_id", "type": "string"},
          {"name": "employee_id", "type": "string"},
          {"name": "amount_minor", "type": "long"},
          {"name": "currency", "type": "string"},
          {"name": "settlement_kind", "type": "string"},
          {"name": "payroll_run_id", "type": ["null", "string"], "default": null},
          {"name": "run_seq", "type": ["null", "int"], "default": null},
          {"name": "settled_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
        ]
      }
      """;

  @Test
  void consumerViewParsesFromClasspath() {
    Schema schema = ExpenseReimbursementSettledSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.employee.ExpenseReimbursementSettled");
    assertThat(schema.getField("settlement_kind")).isNotNull();
    assertThat(schema.getField("payroll_run_id").hasDefaultValue()).isTrue();
    assertThat(schema.getField("run_seq").hasDefaultValue()).isTrue();
  }

  @Test
  void consumerViewIsBackwardCompatibleWithTheProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    assertThat(AvroSerde.isBackwardCompatible(producer, ExpenseReimbursementSettledSchema.schema()))
        .isTrue();
  }

  @Test
  void payrollSettlementBytesDecodeUnderTheConsumerView() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = ExpenseReimbursementSettledSchema.schema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("claim_id", "55555555-5555-5555-5555-555555555555");
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("org_unit_id", "22222222-2222-2222-2222-222222222222");
    produced.put("employee_id", "33333333-3333-3333-3333-333333333333");
    produced.put("amount_minor", 250_000L);
    produced.put("currency", "IDR");
    produced.put("settlement_kind", "PAYROLL");
    produced.put("payroll_run_id", "44444444-4444-4444-4444-444444444444");
    produced.put("run_seq", 2);
    produced.put("settled_at", 1_786_200_000_000L);

    GenericRecord decoded =
        AvroSerde.deserialize(AvroSerde.serialize(produced), producer, consumer);

    assertThat(decoded.get("settlement_kind").toString()).isEqualTo("PAYROLL");
    assertThat(decoded.get("payroll_run_id").toString())
        .isEqualTo("44444444-4444-4444-4444-444444444444");
    assertThat(decoded.get("run_seq")).isEqualTo(2);
    assertThat(decoded.get("amount_minor")).isEqualTo(250_000L);
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
                  "name": "ExpenseReimbursementSettled",
                  "namespace": "id.co.nativeapp.events.employee",
                  "fields": [
                    {"name": "claim_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "org_unit_id", "type": "string"},
                    {"name": "employee_id", "type": "string"},
                    {"name": "amount_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "settlement_kind", "type": "string"},
                    {"name": "payroll_run_id", "type": ["null", "string"], "default": null},
                    {"name": "run_seq", "type": ["null", "int"], "default": null},
                    {"name": "settled_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "bank_reference", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, incompatible)).isFalse();
  }
}
