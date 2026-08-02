package id.co.nativeapp.employee.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.payroll.messaging.PayrollPostedSchema;
import id.co.nativeapp.events.AvroSerde;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code PayrollPosted} PRODUCER contract triad (rule 7): the schema parses with the expected
 * shape (company totals + frozen rule_versions + the illustrative flag), a record round-trips
 * through {@link AvroSerde} (incl. the {@code rule_versions} array + the {@code timestamp-millis}
 * logical type), and an additive change stays backward-compatible while a new required field breaks
 * it. The event carries NO PII (rule 6) — no per-person amounts.
 */
class PayrollPostedContractTest {

  @Test
  void avscParsesWithCompanyTotalsAndNoPerPersonFields() {
    Schema schema = PayrollPostedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.employee.PayrollPosted");
    assertThat(schema.getField("gross_total_minor")).isNotNull();
    assertThat(schema.getField("net_total_minor")).isNotNull();
    assertThat(schema.getField("uses_illustrative_rules")).isNotNull();
    // run_seq is on the wire (the supersession signal finance keys on), added backward-compatibly.
    assertThat(schema.getField("run_seq").schema().getType()).isEqualTo(Schema.Type.INT);
    assertThat(schema.getField("run_seq").hasDefaultValue()).isTrue();
    // run_type rides the wire (ADR 0032, Track P phase P4), added backward-compatibly.
    assertThat(schema.getField("run_type").schema().getType()).isEqualTo(Schema.Type.STRING);
    assertThat(schema.getField("run_type").hasDefaultValue()).isTrue();
    assertThat(schema.getField("rule_versions").schema().getType()).isEqualTo(Schema.Type.ARRAY);
    assertThat(schema.getField("posted_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
    // NO PII / per-person fields.
    assertThat(schema.getField("employee_id")).isNull();
    assertThat(schema.getField("nik")).isNull();
    assertThat(schema.getField("salary")).isNull();
    assertThat(schema.getField("amount_minor")).isNull();
  }

  @Test
  void recordRoundTripsThroughAvroSerde() {
    Schema schema = PayrollPostedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("payroll_run_id", "55555555-5555-5555-5555-555555555555");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("period", "2026-06");
    record.put("base_currency", "IDR");
    record.put("gross_total_minor", 20_000_000L);
    record.put("employee_deduction_total_minor", 2_201_667L);
    record.put("employer_contribution_total_minor", 400_000L);
    record.put("net_total_minor", 17_798_333L);
    Schema rvElement = schema.getField("rule_versions").schema().getElementType();
    GenericRecord rv = new GenericData.Record(rvElement);
    rv.put("rule_key", "PPH21_PROGRESSIVE");
    rv.put("rule_version", "ILLUSTRATIVE-2026.1");
    record.put("rule_versions", List.of(rv));
    record.put("run_seq", 2); // a corrected re-run carries run_seq=2 on the wire
    record.put("run_type", "REGULAR");
    record.put("uses_illustrative_rules", true);
    record.put("posted_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("net_total_minor")).isEqualTo(17_798_333L);
    assertThat(decoded.get("uses_illustrative_rules")).isEqualTo(true);
    // run_seq=2 round-trips as 2 (not the default 1) so finance can supersede the prior run.
    assertThat(decoded.get("run_seq")).isEqualTo(2);
    assertThat(decoded.get("run_type").toString()).isEqualTo("REGULAR");
    assertThat(((List<?>) decoded.get("rule_versions"))).hasSize(1);
  }

  @Test
  void toRecordStampsRunTypeRegularByDefault() {
    // The 3-arg PayrollRun constructor defaults to REGULAR (Track P Phase P8, ADR 0035).
    id.co.nativeapp.employee.payroll.domain.PayrollRun run =
        new id.co.nativeapp.employee.payroll.domain.PayrollRun("2026-06", 1, "IDR");
    run.markPosted(java.time.Instant.parse("2026-06-30T12:00:00Z"));
    GenericRecord record = PayrollPostedSchema.toRecord(run, List.of());
    assertThat(record.get("run_type").toString()).isEqualTo("REGULAR");
  }

  @Test
  void toRecordStampsRunTypeThrWhenTheRunIsThr() {
    // Track P Phase P8 (ADR 0035): toRecord() carries the run's REAL run_type off payroll_run.
    id.co.nativeapp.employee.payroll.domain.PayrollRun run =
        new id.co.nativeapp.employee.payroll.domain.PayrollRun(
            "2026-06", 1, "IDR", id.co.nativeapp.employee.payroll.domain.RunType.THR);
    run.markPosted(java.time.Instant.parse("2026-06-30T12:00:00Z"));
    GenericRecord record = PayrollPostedSchema.toRecord(run, List.of());
    assertThat(record.get("run_type").toString()).isEqualTo("THR");
  }

  @Test
  void preRunSeqBytesReadAsTheDefaultFirstRunUnderTheCurrentSchema() {
    // An already-shipped producer with no run_seq: its bytes must still read under the current
    // schema, defaulting run_seq to 1 (the first run) — proves the add is backward-compatible.
    Schema preRunSeq = new Schema.Parser().parse(PRE_RUN_SEQ_SCHEMA_JSON);
    assertThat(AvroSerde.isBackwardCompatible(preRunSeq, PayrollPostedSchema.schema())).isTrue();

    GenericRecord old = new GenericData.Record(preRunSeq);
    old.put("payroll_run_id", "55555555-5555-5555-5555-555555555555");
    old.put("company_id", "11111111-1111-1111-1111-111111111111");
    old.put("period", "2026-06");
    old.put("base_currency", "IDR");
    old.put("gross_total_minor", 20_000_000L);
    old.put("employee_deduction_total_minor", 2_201_667L);
    old.put("employer_contribution_total_minor", 400_000L);
    old.put("net_total_minor", 17_798_333L);
    old.put("rule_versions", List.of());
    old.put("uses_illustrative_rules", false);
    old.put("posted_at", 1_750_000_000_000L);

    GenericRecord decoded =
        AvroSerde.deserialize(AvroSerde.serialize(old), preRunSeq, PayrollPostedSchema.schema());
    assertThat(decoded.get("run_seq")).isEqualTo(1);
  }

  @Test
  void preRunTypeBytesReadAsTheDefaultRegularRunTypeUnderTheCurrentSchema() {
    // An already-shipped producer with no run_type: its bytes must still read under the current
    // schema, defaulting run_type to REGULAR (ADR 0032, Track P phase P4).
    Schema preRunType = new Schema.Parser().parse(PRE_RUN_TYPE_SCHEMA_JSON);
    assertThat(AvroSerde.isBackwardCompatible(preRunType, PayrollPostedSchema.schema())).isTrue();

    GenericRecord old = new GenericData.Record(preRunType);
    old.put("payroll_run_id", "55555555-5555-5555-5555-555555555555");
    old.put("company_id", "11111111-1111-1111-1111-111111111111");
    old.put("period", "2026-06");
    old.put("base_currency", "IDR");
    old.put("gross_total_minor", 20_000_000L);
    old.put("employee_deduction_total_minor", 2_201_667L);
    old.put("employer_contribution_total_minor", 400_000L);
    old.put("net_total_minor", 17_798_333L);
    old.put("rule_versions", List.of());
    old.put("run_seq", 1);
    old.put("uses_illustrative_rules", false);
    old.put("posted_at", 1_750_000_000_000L);

    GenericRecord decoded =
        AvroSerde.deserialize(AvroSerde.serialize(old), preRunType, PayrollPostedSchema.schema());
    assertThat(decoded.get("run_type").toString()).isEqualTo("REGULAR");
  }

  /**
   * The producer's PRE-{@code run_type} {@code PayrollPosted} schema (has {@code run_seq} but no
   * {@code run_type}) — the CURRENT ADR 0032 addition (Track P phase P4).
   */
  private static final String PRE_RUN_TYPE_SCHEMA_JSON =
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
          {"name": "run_seq", "type": "int", "default": 1},
          {"name": "uses_illustrative_rules", "type": "boolean"},
          {"name": "posted_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
        ]
      }
      """;

  /** The producer's PRE-run_seq {@code PayrollPosted} schema (no {@code run_seq}). */
  private static final String PRE_RUN_SEQ_SCHEMA_JSON =
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
  void addingAnOptionalFieldStaysBackwardCompatibleAndARequiredFieldBreaks() {
    Schema current = PayrollPostedSchema.schema();
    Schema evolved =
        new Schema.Parser()
            .parse(
                current
                    .toString()
                    .replace(
                        "\"fields\":[",
                        "\"fields\":[{\"name\":\"note\",\"type\":[\"null\",\"string\"],\"default\":null},"));
    assertThat(AvroSerde.isBackwardCompatible(current, evolved)).isTrue();

    Schema broken =
        new Schema.Parser()
            .parse(
                current
                    .toString()
                    .replace(
                        "\"fields\":[",
                        "\"fields\":[{\"name\":\"mandatory\",\"type\":\"string\"},"));
    assertThat(AvroSerde.isBackwardCompatible(current, broken)).isFalse();
  }
}
