package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Test (d) — the {@code SaleRecorded} CONSUMER contract test (no Spring context needed).
 *
 * <p>finance owns its own consumer view of the {@code SaleRecorded} schema (sourced from {@code
 * libs/contracts avro/SaleRecorded.avsc}). This proves that view:
 *
 * <ul>
 *   <li>parses from the classpath and has the expected shape (full name + fields);
 *   <li>round-trips a {@link GenericRecord} through {@code libs/events} {@link AvroSerde}
 *       (serialize → deserialize), the exact path the listener decodes off the wire; and
 *   <li>stays BACKWARD-COMPATIBLE with the original producer schema (the 6-field shape without
 *       {@code tender_type}) — a finance reader on its current schema can read bytes an old
 *       producer (carwash, legacy) wrote, and a deliberate incompatible break (a new required field
 *       with no default) is rejected.
 * </ul>
 *
 * <p>ADR 0006 slice 2: asserts the optional {@code tender_type} field round-trips and that old
 * records (tender_type absent) decode with {@code null} as the default.
 */
class SaleRecordedContractTest {

  /**
   * The ORIGINAL producer schema WITHOUT {@code tender_type} — simulates a carwash / legacy
   * producer record on the wire. The consumer must decode it (backward-compat invariant: new reader
   * reads old bytes, CLAUDE.md rule 7).
   */
  private static final String ORIGINAL_PRODUCER_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "SaleRecorded",
        "namespace": "id.co.nativeapp.events.restaurant",
        "fields": [
          {"name": "sale_id", "type": "string"},
          {"name": "company_id", "type": "string"},
          {"name": "business_id", "type": "string"},
          {"name": "amount_minor", "type": "long"},
          {"name": "currency", "type": "string"},
          {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
        ]
      }
      """;

  @Test
  void consumerAvscParsesFromClasspath() {
    Schema schema = SaleRecordedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.restaurant.SaleRecorded");
    assertThat(schema.getField("sale_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("business_id")).isNotNull();
    assertThat(schema.getField("amount_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
    assertThat(schema.getField("occurred_at").schema().getLogicalType()).isNotNull();
    assertThat(schema.getField("occurred_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
    // ADR 0006 slice 2: tender_type optional field (["null","string"], default null).
    assertThat(schema.getField("tender_type")).isNotNull();
    assertThat(schema.getField("tender_type").schema().getType()).isEqualTo(Schema.Type.UNION);
    assertThat(schema.getField("tender_type").hasDefaultValue()).isTrue();
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = SaleRecordedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("sale_id", "33333333-3333-3333-3333-333333333333");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("business_id", "22222222-2222-2222-2222-222222222222");
    record.put("amount_minor", 1_500_000L); // money is integer minor units, never a float
    record.put("currency", "IDR");
    record.put("occurred_at", 1_750_000_000_000L);
    record.put("tender_type", "QRIS"); // ADR 0006 slice 2

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("sale_id").toString()).isEqualTo("33333333-3333-3333-3333-333333333333");
    assertThat(decoded.get("amount_minor")).isEqualTo(1_500_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("occurred_at")).isEqualTo(1_750_000_000_000L);
    assertThat(decoded.get("tender_type").toString()).isEqualTo("QRIS");
  }

  @Test
  void tenderTypeNullRoundTrips() {
    Schema schema = SaleRecordedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("sale_id", "33333333-3333-3333-3333-333333333333");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("business_id", "22222222-2222-2222-2222-222222222222");
    record.put("amount_minor", 1_500_000L);
    record.put("currency", "IDR");
    record.put("occurred_at", 1_750_000_000_000L);
    record.put("tender_type", null);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("tender_type")).isNull();
  }

  @Test
  void consumerCopyIsBackwardCompatibleWithTheOriginalProducerSchema() {
    Schema producer = new Schema.Parser().parse(ORIGINAL_PRODUCER_SCHEMA_JSON);
    Schema consumer = SaleRecordedSchema.schema();
    // A finance reader on its consumer copy must read bytes an old producer (carwash) wrote.
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void bytesWrittenWithOldProducerSchemaDecodeUnderCurrentConsumer() {
    Schema producer = new Schema.Parser().parse(ORIGINAL_PRODUCER_SCHEMA_JSON);
    Schema consumer = SaleRecordedSchema.schema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("sale_id", "33333333-3333-3333-3333-333333333333");
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("business_id", "22222222-2222-2222-2222-222222222222");
    produced.put("amount_minor", 2_000_000L);
    produced.put("currency", "IDR");
    produced.put("occurred_at", 1_750_000_000_000L);

    byte[] wireBytes = AvroSerde.serialize(produced);
    // Project old-producer bytes onto the consumer schema (Avro schema resolution).
    GenericRecord decoded = AvroSerde.deserialize(wireBytes, producer, consumer);

    assertThat(decoded.get("amount_minor")).isEqualTo(2_000_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    // tender_type defaults to null when absent from the old producer bytes.
    assertThat(decoded.get("tender_type")).isNull();
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema producer = new Schema.Parser().parse(ORIGINAL_PRODUCER_SCHEMA_JSON);
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "SaleRecorded",
                  "namespace": "id.co.nativeapp.events.restaurant",
                  "fields": [
                    {"name": "sale_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "business_id", "type": "string"},
                    {"name": "amount_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "tender_type", "type": ["null", "string"], "default": null},
                    {"name": "cashier_id", "type": "string"}
                  ]
                }
                """);
    // A reader on the new schema cannot read producer data — no value for cashier_id.
    assertThat(AvroSerde.isBackwardCompatible(producer, incompatible)).isFalse();
  }
}
