package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.labor.messaging.LaborCostAllocatedSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code LaborCostAllocated} CONSUMER contract test (#23, no Spring context).
 *
 * <p>finance owns its own consumer copy of the schema ({@code
 * src/main/resources/avro/LaborCostAllocated.avsc}). This proves the rule-7 triad: it parses from
 * the classpath with the expected full name + fields; round-trips a {@link GenericRecord} through
 * {@code libs/events} {@link AvroSerde}; stays BACKWARD-COMPATIBLE with the producer schema
 * (including the PRE-{@code run_seq} producer that has no {@code run_seq} — the consumer's {@code
 * default 1} lets it read those bytes as the first run); and rejects an incompatible new required
 * field without a default.
 */
class LaborCostAllocatedContractTest {

  /**
   * The producer's PRE-run_seq {@code LaborCostAllocated} schema (no {@code run_seq}) — proves the
   * backward-compatible add: a consumer with {@code run_seq default 1} reads these older bytes.
   */
  private static final String PRODUCER_SCHEMA_JSON =
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
  void consumerAvscParsesFromClasspath() {
    Schema schema = LaborCostAllocatedSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.employee.LaborCostAllocated");
    assertThat(schema.getField("payroll_run_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("period")).isNotNull();
    assertThat(schema.getField("outlet_id")).isNotNull();
    assertThat(schema.getField("gl_account")).isNotNull();
    assertThat(schema.getField("amount_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
    assertThat(schema.getField("run_seq").schema().getType()).isEqualTo(Schema.Type.INT);
    // run_type rides the wire (ADR 0032, Track P phase P4), added backward-compatibly.
    assertThat(schema.getField("run_type").schema().getType()).isEqualTo(Schema.Type.STRING);
    assertThat(schema.getField("run_type").hasDefaultValue()).isTrue();
    assertThat(schema.getField("uses_illustrative_rules")).isNotNull();
    assertThat(schema.getField("unallocated")).isNotNull();
    assertThat(schema.getField("occurred_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = LaborCostAllocatedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("payroll_run_id", "33333333-3333-3333-3333-333333333333");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("period", "2026-06");
    record.put("outlet_id", "22222222-2222-2222-2222-222222222222");
    record.put("gl_account", "5100-SALARY");
    record.put("amount_minor", 20_400_000L); // money is integer minor units, never a float
    record.put("currency", "IDR");
    record.put("run_seq", 1);
    record.put("run_type", "REGULAR");
    record.put("uses_illustrative_rules", true);
    record.put("unallocated", false);
    record.put("occurred_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("amount_minor")).isEqualTo(20_400_000L);
    assertThat(decoded.get("gl_account").toString()).isEqualTo("5100-SALARY");
    assertThat(decoded.get("run_seq")).isEqualTo(1);
    assertThat(decoded.get("run_type").toString()).isEqualTo("REGULAR");
    assertThat(decoded.get("uses_illustrative_rules")).isEqualTo(true);
  }

  @Test
  void consumerCopyIsBackwardCompatibleWithThePreRunSeqProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = LaborCostAllocatedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void bytesWrittenWithThePreRunSeqProducerDecodeUnderTheConsumerCopyAsRunSeqOne() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = LaborCostAllocatedSchema.schema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("payroll_run_id", "33333333-3333-3333-3333-333333333333");
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("period", "2026-06");
    produced.put("outlet_id", "22222222-2222-2222-2222-222222222222");
    produced.put("gl_account", "5100-SALARY");
    produced.put("amount_minor", 900_000L);
    produced.put("currency", "IDR");
    produced.put("uses_illustrative_rules", false);
    produced.put("unallocated", false);
    produced.put("occurred_at", 1_750_000_000_000L);

    byte[] wireBytes = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wireBytes, producer, consumer);

    assertThat(decoded.get("amount_minor")).isEqualTo(900_000L);
    // The missing run_seq reads as the default first run.
    assertThat(decoded.get("run_seq")).isEqualTo(1);
  }

  /**
   * The producer's PRE-{@code run_type} {@code LaborCostAllocated} schema (has {@code run_seq} but
   * no {@code run_type}) — the CURRENT ADR 0032 addition. Proves the backward-compatible add: a
   * consumer with {@code run_type default REGULAR} reads these older bytes as REGULAR.
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

  @Test
  void preRunTypeBytesReadAsTheDefaultRegularRunTypeUnderTheCurrentSchema() {
    // An already-shipped producer with no run_type: its bytes must still read under the current
    // schema, defaulting run_type to REGULAR (ADR 0032, Track P phase P4) — the run_seq idiom
    // applied to the new field.
    Schema preRunType = new Schema.Parser().parse(PRE_RUN_TYPE_SCHEMA_JSON);
    assertThat(AvroSerde.isBackwardCompatible(preRunType, LaborCostAllocatedSchema.schema()))
        .isTrue();

    GenericRecord old = new GenericData.Record(preRunType);
    old.put("payroll_run_id", "33333333-3333-3333-3333-333333333333");
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

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema incompatible =
        new Schema.Parser()
            .parse(
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
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "employee_id", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, incompatible)).isFalse();
  }
}
