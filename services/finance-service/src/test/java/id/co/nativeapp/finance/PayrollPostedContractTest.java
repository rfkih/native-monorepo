package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.labor.PayrollPostedSchema;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code PayrollPosted} CONSUMER contract test (#23, no Spring context).
 *
 * <p>Proves the rule-7 triad for finance's consumer copy ({@code
 * src/main/resources/avro/PayrollPosted.avsc}): parse + shape, {@code AvroSerde} round-trip,
 * backward-compatibility with the PRE-{@code run_seq} producer schema (the consumer's {@code
 * default 1} reads those older bytes), and rejection of a new required field without a default.
 */
class PayrollPostedContractTest {

  /** The producer's PRE-run_seq {@code PayrollPosted} schema (no {@code run_seq}). */
  private static final String PRODUCER_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "PayrollPosted",
        "namespace": "id.co.nativeapp.events.employee",
        "fields": [
          {"name": "payroll_run_id", "type": "string"},
          {"name": "company_id", "type": "string"},
          {"name": "period", "type": "string"},
          {"name": "base_currency", "type": "string"},
          {"name": "gross_total_minor", "type": "long"},
          {"name": "employee_deduction_total_minor", "type": "long"},
          {"name": "employer_contribution_total_minor", "type": "long"},
          {"name": "net_total_minor", "type": "long"},
          {"name": "rule_versions", "type": {"type": "array", "items": {"type": "record", "name": "RuleVersion", "fields": [
            {"name": "rule_key", "type": "string"},
            {"name": "rule_version", "type": "string"}
          ]}}},
          {"name": "uses_illustrative_rules", "type": "boolean"},
          {"name": "posted_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
        ]
      }
      """;

  @Test
  void consumerAvscParsesFromClasspath() {
    Schema schema = PayrollPostedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.employee.PayrollPosted");
    assertThat(schema.getField("payroll_run_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("period")).isNotNull();
    assertThat(schema.getField("base_currency")).isNotNull();
    assertThat(schema.getField("gross_total_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("employer_contribution_total_minor").schema().getType())
        .isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("rule_versions").schema().getType()).isEqualTo(Schema.Type.ARRAY);
    assertThat(schema.getField("run_seq").schema().getType()).isEqualTo(Schema.Type.INT);
    assertThat(schema.getField("uses_illustrative_rules")).isNotNull();
    assertThat(schema.getField("posted_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = PayrollPostedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("payroll_run_id", "33333333-3333-3333-3333-333333333333");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("period", "2026-06");
    record.put("base_currency", "IDR");
    record.put("gross_total_minor", 40_000_000L); // integer minor units, never a float
    record.put("employee_deduction_total_minor", 4_403_334L);
    record.put("employer_contribution_total_minor", 800_000L);
    record.put("net_total_minor", 35_596_666L);
    record.put("rule_versions", List.of());
    record.put("run_seq", 1);
    record.put("uses_illustrative_rules", true);
    record.put("posted_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("gross_total_minor")).isEqualTo(40_000_000L);
    assertThat(decoded.get("employer_contribution_total_minor")).isEqualTo(800_000L);
    assertThat(decoded.get("run_seq")).isEqualTo(1);
    assertThat(decoded.get("uses_illustrative_rules")).isEqualTo(true);
  }

  @Test
  void consumerCopyIsBackwardCompatibleWithThePreRunSeqProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = PayrollPostedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
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
                  "name": "PayrollPosted",
                  "namespace": "id.co.nativeapp.events.employee",
                  "fields": [
                    {"name": "payroll_run_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "period", "type": "string"},
                    {"name": "base_currency", "type": "string"},
                    {"name": "gross_total_minor", "type": "long"},
                    {"name": "employee_deduction_total_minor", "type": "long"},
                    {"name": "employer_contribution_total_minor", "type": "long"},
                    {"name": "net_total_minor", "type": "long"},
                    {"name": "rule_versions", "type": {"type": "array", "items": {"type": "record", "name": "RuleVersion", "fields": [
                      {"name": "rule_key", "type": "string"},
                      {"name": "rule_version", "type": "string"}
                    ]}}},
                    {"name": "uses_illustrative_rules", "type": "boolean"},
                    {"name": "posted_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "approver_id", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, incompatible)).isFalse();
  }
}
