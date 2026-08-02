package id.co.nativeapp.employee.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.payroll.messaging.LaborCostAllocatedSchema;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code LaborCostAllocated} PRODUCER contract triad (rule 7): parse + shape (outlet/GL bucket,
 * NO employee_id so no individual salary is derivable — design decision vs §5), AvroSerde
 * round-trip, and backward-compat add-optional / break-on-required.
 */
class LaborCostAllocatedContractTest {

  @Test
  void avscParsesAsAnOutletGlBucketWithNoEmployeeId() {
    Schema schema = LaborCostAllocatedSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.employee.LaborCostAllocated");
    assertThat(schema.getField("outlet_id")).isNotNull();
    assertThat(schema.getField("gl_account")).isNotNull();
    assertThat(schema.getField("amount_minor")).isNotNull();
    assertThat(schema.getField("uses_illustrative_rules")).isNotNull();
    // run_seq rides the wire (the supersession signal finance keys on), added backward-compatibly.
    assertThat(schema.getField("run_seq").schema().getType()).isEqualTo(Schema.Type.INT);
    assertThat(schema.getField("run_seq").hasDefaultValue()).isTrue();
    // run_type rides the wire (ADR 0032, Track P phase P4), added backward-compatibly.
    assertThat(schema.getField("run_type").schema().getType()).isEqualTo(Schema.Type.STRING);
    assertThat(schema.getField("run_type").hasDefaultValue()).isTrue();
    // The UNALLOCATED-suspense marker rides the wire with a default (backward-compatible add).
    assertThat(schema.getField("unallocated")).isNotNull();
    assertThat(schema.getField("unallocated").hasDefaultValue()).isTrue();
    // employee_id is DROPPED to avoid per-person salary exposure (rule 6 / open risk).
    assertThat(schema.getField("employee_id")).isNull();
  }

  @Test
  void unallocatedSuspenseBucketIsMarkedOnTheEvent() {
    GenericRecord record =
        LaborCostAllocatedSchema.toRecord(
            UUID.fromString("55555555-5555-5555-5555-555555555555"),
            "11111111-1111-1111-1111-111111111111",
            "2026-06",
            new UUID(0L, 0L),
            "9999-UNALLOCATED-LABOR",
            Money.ofMinor(5_000_000L, "IDR"),
            1,
            false,
            true,
            Instant.ofEpochMilli(1_750_000_000_000L));

    GenericRecord decoded =
        AvroSerde.deserialize(AvroSerde.serialize(record), LaborCostAllocatedSchema.schema());
    assertThat(decoded.get("unallocated")).isEqualTo(true);
    assertThat(decoded.get("gl_account").toString()).isEqualTo("9999-UNALLOCATED-LABOR");
  }

  @Test
  void recordRoundTripsThroughAvroSerde() {
    GenericRecord record =
        LaborCostAllocatedSchema.toRecord(
            UUID.fromString("55555555-5555-5555-5555-555555555555"),
            "11111111-1111-1111-1111-111111111111",
            "2026-06",
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            "5100-SALARY",
            Money.ofMinor(20_400_000L, "IDR"),
            2, // a corrected re-run carries run_seq=2 on the wire
            true,
            false,
            Instant.ofEpochMilli(1_750_000_000_000L));

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, LaborCostAllocatedSchema.schema());
    assertThat(decoded.get("amount_minor")).isEqualTo(20_400_000L);
    assertThat(decoded.get("gl_account").toString()).isEqualTo("5100-SALARY");
    // run_seq=2 round-trips as 2 (not the default 1) so finance can supersede the prior run.
    assertThat(decoded.get("run_seq")).isEqualTo(2);
    // toRecord() stamps run_type REGULAR unconditionally (ADR 0032; THR lands at Track P phase P8).
    assertThat(decoded.get("run_type").toString()).isEqualTo("REGULAR");
    assertThat(decoded.get("uses_illustrative_rules")).isEqualTo(true);
    assertThat(decoded.get("unallocated")).isEqualTo(false);
  }

  @Test
  void preRunSeqBytesReadAsTheDefaultFirstRunUnderTheCurrentSchema() {
    // An already-shipped producer with no run_seq: its bytes still read under the current schema,
    // defaulting run_seq to 1 (the first run) — proves the add is backward-compatible.
    Schema preRunSeq = new Schema.Parser().parse(PRE_RUN_SEQ_SCHEMA_JSON);
    assertThat(AvroSerde.isBackwardCompatible(preRunSeq, LaborCostAllocatedSchema.schema()))
        .isTrue();

    GenericRecord old = new GenericData.Record(preRunSeq);
    old.put("payroll_run_id", "55555555-5555-5555-5555-555555555555");
    old.put("company_id", "11111111-1111-1111-1111-111111111111");
    old.put("period", "2026-06");
    old.put("outlet_id", "22222222-2222-2222-2222-222222222222");
    old.put("gl_account", "5100-SALARY");
    old.put("amount_minor", 900_000L);
    old.put("currency", "IDR");
    old.put("uses_illustrative_rules", false);
    old.put("unallocated", false);
    old.put("occurred_at", 1_750_000_000_000L);

    GenericRecord decoded =
        AvroSerde.deserialize(
            AvroSerde.serialize(old), preRunSeq, LaborCostAllocatedSchema.schema());
    assertThat(decoded.get("run_seq")).isEqualTo(1);
  }

  @Test
  void preRunTypeBytesReadAsTheDefaultRegularRunTypeUnderTheCurrentSchema() {
    // An already-shipped producer with no run_type: its bytes still read under the current schema,
    // defaulting run_type to REGULAR (ADR 0032, Track P phase P4) — the run_seq idiom applied to
    // the new field.
    Schema preRunType = new Schema.Parser().parse(PRE_RUN_TYPE_SCHEMA_JSON);
    assertThat(AvroSerde.isBackwardCompatible(preRunType, LaborCostAllocatedSchema.schema()))
        .isTrue();

    GenericRecord old = new GenericData.Record(preRunType);
    old.put("payroll_run_id", "55555555-5555-5555-5555-555555555555");
    old.put("company_id", "11111111-1111-1111-1111-111111111111");
    old.put("period", "2026-06");
    old.put("outlet_id", "22222222-2222-2222-2222-222222222222");
    old.put("gl_account", "5100-SALARY");
    old.put("amount_minor", 900_000L);
    old.put("currency", "IDR");
    old.put("run_seq", 1);
    old.put("uses_illustrative_rules", false);
    old.put("unallocated", false);
    old.put("occurred_at", 1_750_000_000_000L);

    GenericRecord decoded =
        AvroSerde.deserialize(
            AvroSerde.serialize(old), preRunType, LaborCostAllocatedSchema.schema());
    assertThat(decoded.get("run_type").toString()).isEqualTo("REGULAR");
  }

  /**
   * The producer's PRE-{@code run_type} {@code LaborCostAllocated} schema (has {@code run_seq} but
   * no {@code run_type}) — the CURRENT ADR 0032 addition.
   */
  private static final String PRE_RUN_TYPE_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "LaborCostAllocated",
        "namespace": "id.co.nativeapp.events.employee",
        "fields": [
          {"name": "payroll_run_id", "type": "string"},
          {"name": "company_id", "type": "string"},
          {"name": "period", "type": "string"},
          {"name": "outlet_id", "type": "string"},
          {"name": "gl_account", "type": "string"},
          {"name": "amount_minor", "type": "long"},
          {"name": "currency", "type": "string"},
          {"name": "run_seq", "type": "int", "default": 1},
          {"name": "uses_illustrative_rules", "type": "boolean"},
          {"name": "unallocated", "type": "boolean", "default": false},
          {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
        ]
      }
      """;

  /** The producer's PRE-run_seq {@code LaborCostAllocated} schema (no {@code run_seq}). */
  private static final String PRE_RUN_SEQ_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "LaborCostAllocated",
        "namespace": "id.co.nativeapp.events.employee",
        "fields": [
          {"name": "payroll_run_id", "type": "string"},
          {"name": "company_id", "type": "string"},
          {"name": "period", "type": "string"},
          {"name": "outlet_id", "type": "string"},
          {"name": "gl_account", "type": "string"},
          {"name": "amount_minor", "type": "long"},
          {"name": "currency", "type": "string"},
          {"name": "uses_illustrative_rules", "type": "boolean"},
          {"name": "unallocated", "type": "boolean", "default": false},
          {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
        ]
      }
      """;

  @Test
  void addingAnOptionalFieldStaysBackwardCompatibleAndARequiredFieldBreaks() {
    Schema current = LaborCostAllocatedSchema.schema();
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
