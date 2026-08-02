package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.labor.messaging.PayrollLiabilitiesPostedEvent;
import id.co.nativeapp.finance.labor.messaging.PayrollLiabilitiesPostedSchema;
import java.util.List;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code PayrollLiabilitiesPosted} CONSUMER contract test (#32 / ADR 0032, Track P phase P4, no
 * Spring context). Proves finance's reader view parses from the classpath, decodes a
 * producer-shaped record into a {@link PayrollLiabilitiesPostedEvent} (including a negative bucket
 * amount and the run_type default), and rejects an incompatible required-field addition.
 */
class PayrollLiabilitiesPostedContractTest {

  /** The producer's registered schema, copied verbatim from the catalog (drift detector). */
  private static final String PRODUCER_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "PayrollLiabilitiesPosted",
        "namespace": "id.co.nativeapp.events.employee",
        "fields": [
          {"name": "payroll_run_id", "type": "string"},
          {"name": "company_id", "type": "string"},
          {"name": "period", "type": "string"},
          {"name": "run_seq", "type": "int", "default": 1},
          {"name": "run_type", "type": "string", "default": "REGULAR"},
          {"name": "base_currency", "type": "string"},
          {"name": "employer_cost_total_minor", "type": "long"},
          {"name": "liabilities", "type": {"type": "array", "items": {"type": "record", "name": "PayrollLiabilityBucket", "fields": [
            {"name": "liability_role", "type": "string"},
            {"name": "amount_minor", "type": "long"}
          ]}}, "default": []},
          {"name": "uses_illustrative_rules", "type": "boolean"},
          {"name": "posted_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
        ]
      }
      """;

  @Test
  void consumerViewParsesFromClasspath() {
    Schema schema = PayrollLiabilitiesPostedSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.employee.PayrollLiabilitiesPosted");
    assertThat(schema.getField("payroll_run_id")).isNotNull();
    assertThat(schema.getField("run_seq").hasDefaultValue()).isTrue();
    assertThat(schema.getField("run_type").hasDefaultValue()).isTrue();
    assertThat(schema.getField("employer_cost_total_minor").schema().getType())
        .isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("liabilities").schema().getType()).isEqualTo(Schema.Type.ARRAY);
  }

  @Test
  void consumerViewIsBackwardCompatibleWithTheProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    assertThat(AvroSerde.isBackwardCompatible(producer, PayrollLiabilitiesPostedSchema.schema()))
        .isTrue();
  }

  @Test
  void decodeReadsRunIdentityAndTheLiabilityBucketsIncludingANegativeAmount() {
    Schema schema = PayrollLiabilitiesPostedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    UUID runId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    record.put("payroll_run_id", runId.toString());
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("period", "2026-12");
    record.put("run_seq", 1);
    record.put("run_type", "REGULAR");
    record.put("base_currency", "IDR");
    record.put("employer_cost_total_minor", 5_527_000L);

    Schema bucketSchema = schema.getField("liabilities").schema().getElementType();
    GenericRecord netWages = new GenericData.Record(bucketSchema);
    netWages.put("liability_role", "NET_WAGES_PAYABLE");
    netWages.put("amount_minor", 67_465_700L);
    GenericRecord pph21 = new GenericData.Record(bucketSchema);
    pph21.put("liability_role", "PPH21_PAYABLE");
    pph21.put("amount_minor", -62_665_700L); // the December Art-17 refund month (ADR 0031)
    record.put("liabilities", List.of(netWages, pph21));

    record.put("uses_illustrative_rules", false);
    record.put("posted_at", 1_786_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    UUID eventId = UUID.randomUUID();
    PayrollLiabilitiesPostedEvent decoded = PayrollLiabilitiesPostedSchema.decode(eventId, bytes);

    assertThat(decoded.eventId()).isEqualTo(eventId);
    assertThat(decoded.payrollRunId()).isEqualTo(runId);
    assertThat(decoded.runSeq()).isEqualTo(1);
    assertThat(decoded.runType()).isEqualTo("REGULAR");
    assertThat(decoded.period()).isEqualTo("2026-12");
    assertThat(decoded.employerCostTotal().amountMinor()).isEqualTo(5_527_000L);
    assertThat(decoded.employerCostTotal().currency().getCurrencyCode()).isEqualTo("IDR");
    assertThat(decoded.liabilities()).hasSize(2);
    assertThat(decoded.liabilities().get(0).liabilityRole()).isEqualTo("NET_WAGES_PAYABLE");
    assertThat(decoded.liabilities().get(0).amount().amountMinor()).isEqualTo(67_465_700L);
    assertThat(decoded.liabilities().get(1).liabilityRole()).isEqualTo("PPH21_PAYABLE");
    assertThat(decoded.liabilities().get(1).amount().amountMinor()).isEqualTo(-62_665_700L);
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
                  "name": "PayrollLiabilitiesPosted",
                  "namespace": "id.co.nativeapp.events.employee",
                  "fields": [
                    {"name": "payroll_run_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "period", "type": "string"},
                    {"name": "run_seq", "type": "int", "default": 1},
                    {"name": "run_type", "type": "string", "default": "REGULAR"},
                    {"name": "base_currency", "type": "string"},
                    {"name": "employer_cost_total_minor", "type": "long"},
                    {"name": "liabilities", "type": {"type": "array", "items": {"type": "record", "name": "PayrollLiabilityBucket", "fields": [
                      {"name": "liability_role", "type": "string"},
                      {"name": "amount_minor", "type": "long"}
                    ]}}, "default": []},
                    {"name": "uses_illustrative_rules", "type": "boolean"},
                    {"name": "posted_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "approver_id", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, incompatible)).isFalse();
  }
}
