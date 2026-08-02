package id.co.nativeapp.employee.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.payroll.domain.PayrollRun;
import id.co.nativeapp.employee.payroll.messaging.PayrollLiabilitiesPostedSchema;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code PayrollLiabilitiesPosted} PRODUCER contract triad (rule 7; ADR 0032, Track P phase
 * P4): the schema parses with the documented shape (run identity + run_type default REGULAR +
 * employer cost total + liability buckets + the illustrative flag — NO PII), {@link
 * PayrollLiabilitiesPostedSchema#toRecord} builds a record that round-trips through {@code
 * libs/events} {@link AvroSerde}, and an incompatible evolution (a required field without a
 * default) is rejected while an optional add stays compatible.
 */
class PayrollLiabilitiesPostedContractTest {

  @Test
  void schemaParsesFromClasspathWithTheDocumentedShape() {
    Schema schema = PayrollLiabilitiesPostedSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.employee.PayrollLiabilitiesPosted");
    assertThat(schema.getField("payroll_run_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("period")).isNotNull();
    assertThat(schema.getField("run_seq").schema().getType()).isEqualTo(Schema.Type.INT);
    assertThat(schema.getField("run_seq").hasDefaultValue()).isTrue();
    assertThat(schema.getField("run_type").schema().getType()).isEqualTo(Schema.Type.STRING);
    assertThat(schema.getField("run_type").hasDefaultValue()).isTrue();
    assertThat(schema.getField("base_currency")).isNotNull();
    assertThat(schema.getField("employer_cost_total_minor").schema().getType())
        .isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("liabilities").schema().getType()).isEqualTo(Schema.Type.ARRAY);
    Schema bucketSchema = schema.getField("liabilities").schema().getElementType();
    assertThat(bucketSchema.getField("liability_role")).isNotNull();
    assertThat(bucketSchema.getField("amount_minor").schema().getType())
        .isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("uses_illustrative_rules")).isNotNull();
    assertThat(schema.getField("posted_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
    // NO PII / per-person fields.
    assertThat(schema.getField("employee_id")).isNull();
    assertThat(schema.getField("nik")).isNull();
  }

  @Test
  void toRecordBuildsARoundTrippableRecordFromAPostedRunAndItsBuckets() {
    PayrollRun run = new PayrollRun("2026-06", 2, "IDR");
    run.setCompanyId("11111111-1111-1111-1111-111111111111");
    run.markPosted(Instant.parse("2026-06-30T12:00:00Z"));

    List<PayrollLiabilitiesPostedSchema.LiabilityBucket> buckets =
        List.of(
            new PayrollLiabilitiesPostedSchema.LiabilityBucket(
                "NET_WAGES_PAYABLE", Money.ofMinor(17_798_333L, "IDR")),
            new PayrollLiabilitiesPostedSchema.LiabilityBucket(
                "PPH21_PAYABLE", Money.ofMinor(2_101_667L, "IDR")),
            new PayrollLiabilitiesPostedSchema.LiabilityBucket(
                "BPJS_KES_PAYABLE", Money.ofMinor(500_000L, "IDR")));

    GenericRecord record =
        PayrollLiabilitiesPostedSchema.toRecord(run, Money.ofMinor(20_400_000L, "IDR"), buckets);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, PayrollLiabilitiesPostedSchema.schema());

    assertThat(decoded.get("payroll_run_id").toString()).isEqualTo(run.getId().toString());
    assertThat(decoded.get("period").toString()).isEqualTo("2026-06");
    assertThat(decoded.get("run_seq")).isEqualTo(2);
    // The 3-arg PayrollRun constructor defaults to REGULAR (Track P Phase P8, ADR 0035).
    assertThat(decoded.get("run_type").toString()).isEqualTo("REGULAR");
    assertThat(decoded.get("employer_cost_total_minor")).isEqualTo(20_400_000L);
    @SuppressWarnings("unchecked")
    List<GenericRecord> decodedBuckets = (List<GenericRecord>) decoded.get("liabilities");
    assertThat(decodedBuckets).hasSize(3);
    assertThat(decodedBuckets.get(0).get("liability_role").toString())
        .isEqualTo("NET_WAGES_PAYABLE");
    assertThat(decodedBuckets.get(0).get("amount_minor")).isEqualTo(17_798_333L);
    assertThat(decodedBuckets.get(1).get("liability_role").toString()).isEqualTo("PPH21_PAYABLE");
    assertThat(decodedBuckets.get(1).get("amount_minor")).isEqualTo(2_101_667L);
    assertThat(decoded.get("uses_illustrative_rules")).isEqualTo(false);
  }

  @Test
  void toRecordStampsRunTypeThrWhenTheRunIsThr() {
    // Track P Phase P8 (ADR 0035): toRecord() carries the run's REAL run_type off payroll_run.
    PayrollRun run =
        new PayrollRun("2026-06", 1, "IDR", id.co.nativeapp.employee.payroll.domain.RunType.THR);
    run.setCompanyId("11111111-1111-1111-1111-111111111111");
    run.markPosted(Instant.parse("2026-06-30T12:00:00Z"));
    List<PayrollLiabilitiesPostedSchema.LiabilityBucket> buckets =
        List.of(
            new PayrollLiabilitiesPostedSchema.LiabilityBucket(
                "NET_WAGES_PAYABLE", Money.ofMinor(1_000_000L, "IDR")));

    GenericRecord record =
        PayrollLiabilitiesPostedSchema.toRecord(run, Money.ofMinor(1_000_000L, "IDR"), buckets);
    GenericRecord decoded =
        AvroSerde.deserialize(AvroSerde.serialize(record), PayrollLiabilitiesPostedSchema.schema());
    assertThat(decoded.get("run_type").toString()).isEqualTo("THR");
  }

  @Test
  void toRecordRoundTripsANegativeBucketAmount() {
    // The December Art-17 true-up refund month (ADR 0031 Track P phase P3) can drive PPH21_PAYABLE
    // negative; Avro's signed long carries the sign exactly.
    PayrollRun run = new PayrollRun("2026-12", 1, "IDR");
    run.setCompanyId("11111111-1111-1111-1111-111111111111");
    run.markPosted(Instant.parse("2026-12-31T12:00:00Z"));
    List<PayrollLiabilitiesPostedSchema.LiabilityBucket> buckets =
        List.of(
            new PayrollLiabilitiesPostedSchema.LiabilityBucket(
                "PPH21_PAYABLE", Money.ofMinor(-62_665_700L, "IDR")));

    GenericRecord record =
        PayrollLiabilitiesPostedSchema.toRecord(run, Money.ofMinor(5_527_000L, "IDR"), buckets);
    GenericRecord decoded =
        AvroSerde.deserialize(AvroSerde.serialize(record), PayrollLiabilitiesPostedSchema.schema());

    @SuppressWarnings("unchecked")
    List<GenericRecord> decodedBuckets = (List<GenericRecord>) decoded.get("liabilities");
    assertThat(decodedBuckets.get(0).get("amount_minor")).isEqualTo(-62_665_700L);
  }

  /**
   * The registered schema verbatim, MINUS its {@code posted_at} field — stands in for "the
   * registered schema" as the OLD/writer side of the two compatibility checks below. A literal copy
   * (not a {@code toString().replace(...)} on the registered schema) because the registered schema
   * embeds a NESTED record ({@code PayrollLiabilityBucket}) with its OWN {@code "fields":[} — a
   * blind string replace would corrupt the nested record too.
   */
  private static final String REGISTERED_SCHEMA_JSON =
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
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema registered = new Schema.Parser().parse(REGISTERED_SCHEMA_JSON);
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
    assertThat(AvroSerde.isBackwardCompatible(registered, incompatible)).isFalse();
  }

  @Test
  void addingAnOptionalFieldWithADefaultStaysBackwardCompatible() {
    Schema registered = new Schema.Parser().parse(REGISTERED_SCHEMA_JSON);
    Schema evolved =
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
                    {"name": "note", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(registered, evolved)).isTrue();
  }
}
