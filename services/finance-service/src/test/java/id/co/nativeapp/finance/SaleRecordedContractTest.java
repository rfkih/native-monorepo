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
 *
 * <p>ADR 0027 (Phase 4, loyalty + gift cards): asserts the five trailing optional fields ({@code
 * loyalty_member_id}, {@code loyalty_redeemed_points}, {@code loyalty_redeemed_minor}, {@code
 * gift_card_id}, {@code gift_card_redeemed_minor}) round-trip, and proves the evolution pair in
 * both directions — a Phase 4 finance reader decodes pre-Phase-4 producer bytes (old-writer /
 * new-reader), and a pre-Phase-4 finance reader (not yet upgraded) still decodes Phase 4 producer
 * bytes, dropping the fields it doesn't know (new-writer / old-reader).
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

  /**
   * The PRE-Phase-4 producer/consumer shape (13 fields, through {@code uses_illustrative_rules}) —
   * the schema every service ran before ADR 0027 (loyalty + gift cards) added the five trailing
   * optional fields ({@code loyalty_member_id}, {@code loyalty_redeemed_points}, {@code
   * loyalty_redeemed_minor}, {@code gift_card_id}, {@code gift_card_redeemed_minor}).
   */
  private static final String PRE_PHASE4_SCHEMA_JSON =
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
          {"name": "subtotal_minor", "type": ["null", "long"], "default": null},
          {"name": "discount_minor", "type": ["null", "long"], "default": null},
          {"name": "service_charge_minor", "type": ["null", "long"], "default": null},
          {"name": "tax_minor", "type": ["null", "long"], "default": null},
          {"name": "tax_rule_version", "type": ["null", "string"], "default": null},
          {"name": "uses_illustrative_rules", "type": ["null", "boolean"], "default": null}
        ]
      }
      """;

  @Test
  void schemaCarriesTheFivePhase4LoyaltyAndGiftCardFields() {
    Schema schema = SaleRecordedSchema.schema();
    assertThat(schema.getField("loyalty_member_id").hasDefaultValue()).isTrue();
    assertThat(schema.getField("loyalty_redeemed_points").hasDefaultValue()).isTrue();
    assertThat(schema.getField("loyalty_redeemed_minor").hasDefaultValue()).isTrue();
    assertThat(schema.getField("gift_card_id").hasDefaultValue()).isTrue();
    assertThat(schema.getField("gift_card_redeemed_minor").hasDefaultValue()).isTrue();
  }

  @Test
  void consumerCopyIsBackwardCompatibleWithThePrePhase4ProducerSchema() {
    // OLD-WRITER / NEW-READER: finance on the current (Phase 4) schema must be able to read bytes
    // written by an already-deployed pre-Phase-4 vertical producer (the rolling-deploy window
    // before every vertical picks up ADR 0027).
    Schema producer = new Schema.Parser().parse(PRE_PHASE4_SCHEMA_JSON);
    Schema consumer = SaleRecordedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void bytesWrittenWithPrePhase4ProducerSchemaDecodeUnderCurrentConsumerWithNullPhase4Fields() {
    Schema producer = new Schema.Parser().parse(PRE_PHASE4_SCHEMA_JSON);
    Schema consumer = SaleRecordedSchema.schema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("sale_id", "33333333-3333-3333-3333-333333333333");
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("business_id", "22222222-2222-2222-2222-222222222222");
    produced.put("amount_minor", 2_000_000L);
    produced.put("currency", "IDR");
    produced.put("occurred_at", 1_750_000_000_000L);
    produced.put("tender_type", "CASH");

    byte[] wireBytes = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wireBytes, producer, consumer);

    assertThat(decoded.get("amount_minor")).isEqualTo(2_000_000L);
    // The five Phase 4 fields default to null when absent from pre-Phase-4 producer bytes.
    assertThat(decoded.get("loyalty_member_id")).isNull();
    assertThat(decoded.get("loyalty_redeemed_points")).isNull();
    assertThat(decoded.get("loyalty_redeemed_minor")).isNull();
    assertThat(decoded.get("gift_card_id")).isNull();
    assertThat(decoded.get("gift_card_redeemed_minor")).isNull();
  }

  @Test
  void newProducerBytesWithPhase4FieldsDecodeUnderAnOldPrePhase4ReaderSchema() {
    // NEW-WRITER / OLD-READER: an already-deployed pre-Phase-4 finance consumer (not yet upgraded)
    // must still be able to project bytes written by an upgraded (Phase 4) vertical producer — the
    // five new fields are simply dropped by Avro schema resolution, not an error.
    Schema newProducer = SaleRecordedSchema.schema();
    Schema oldConsumer = new Schema.Parser().parse(PRE_PHASE4_SCHEMA_JSON);

    GenericRecord produced = new GenericData.Record(newProducer);
    produced.put("sale_id", "33333333-3333-3333-3333-333333333333");
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("business_id", "22222222-2222-2222-2222-222222222222");
    produced.put("amount_minor", 77_700_00L);
    produced.put("currency", "IDR");
    produced.put("occurred_at", 1_750_000_000_000L);
    produced.put("tender_type", "CASH");
    produced.put("subtotal_minor", 70_000_00L);
    produced.put("discount_minor", 0L);
    produced.put("service_charge_minor", 0L);
    produced.put("tax_minor", 7_700_00L);
    produced.put("tax_rule_version", "ILLUSTRATIVE-2026.1");
    produced.put("uses_illustrative_rules", true);
    produced.put("loyalty_member_id", "44444444-4444-4444-4444-444444444444");
    produced.put("loyalty_redeemed_points", 500L);
    produced.put("loyalty_redeemed_minor", 5_000_00L);
    produced.put("gift_card_id", "55555555-5555-5555-5555-555555555555");
    produced.put("gift_card_redeemed_minor", 10_000_00L);

    byte[] wireBytes = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wireBytes, newProducer, oldConsumer);

    assertThat(decoded.get("amount_minor")).isEqualTo(77_700_00L);
    assertThat(decoded.get("tax_minor")).isEqualTo(7_700_00L);
    // The old reader schema simply has no such fields.
    assertThat(decoded.getSchema().getField("loyalty_member_id")).isNull();
    assertThat(decoded.getSchema().getField("gift_card_id")).isNull();
  }

  /**
   * The PRE-Phase-B producer/consumer shape (18 fields, through {@code gift_card_redeemed_minor}) —
   * the schema every service ran before ADR 0036 (Phase B) appended the trailing {@code channel}
   * field. Mirrors {@link #PRE_PHASE4_SCHEMA_JSON}'s role for the Phase 4 fields.
   */
  private static final String PRE_CHANNEL_SCHEMA_JSON =
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
          {"name": "subtotal_minor", "type": ["null", "long"], "default": null},
          {"name": "discount_minor", "type": ["null", "long"], "default": null},
          {"name": "service_charge_minor", "type": ["null", "long"], "default": null},
          {"name": "tax_minor", "type": ["null", "long"], "default": null},
          {"name": "tax_rule_version", "type": ["null", "string"], "default": null},
          {"name": "uses_illustrative_rules", "type": ["null", "boolean"], "default": null},
          {"name": "loyalty_member_id", "type": ["null", "string"], "default": null},
          {"name": "loyalty_redeemed_points", "type": ["null", "long"], "default": null},
          {"name": "loyalty_redeemed_minor", "type": ["null", "long"], "default": null},
          {"name": "gift_card_id", "type": ["null", "string"], "default": null},
          {"name": "gift_card_redeemed_minor", "type": ["null", "long"], "default": null}
        ]
      }
      """;

  @Test
  void schemaCarriesTheChannelField() {
    // ADR 0036 (Phase B): channel — ["null","string"] with default null, appended LAST.
    Schema schema = SaleRecordedSchema.schema();
    assertThat(schema.getField("channel")).isNotNull();
    assertThat(schema.getField("channel").hasDefaultValue()).isTrue();
  }

  @Test
  void consumerCopyIsBackwardCompatibleWithThePreChannelProducerSchema() {
    // OLD-WRITER / NEW-READER: finance on the current (Phase B) schema must be able to read bytes
    // written by an already-deployed pre-Phase-B vertical producer.
    Schema producer = new Schema.Parser().parse(PRE_CHANNEL_SCHEMA_JSON);
    Schema consumer = SaleRecordedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void bytesWrittenWithPreChannelProducerSchemaDecodeUnderCurrentConsumerWithNullChannel() {
    // Backward-compat proof, mirroring the Phase 4 gift-card-fields pattern above: a record
    // written against the PRE-Phase-B schema (channel absent entirely) must decode with
    // channel == null under the current consumer schema.
    Schema producer = new Schema.Parser().parse(PRE_CHANNEL_SCHEMA_JSON);
    Schema consumer = SaleRecordedSchema.schema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("sale_id", "33333333-3333-3333-3333-333333333333");
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("business_id", "22222222-2222-2222-2222-222222222222");
    produced.put("amount_minor", 2_000_000L);
    produced.put("currency", "IDR");
    produced.put("occurred_at", 1_750_000_000_000L);
    produced.put("tender_type", "CASH");

    byte[] wireBytes = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wireBytes, producer, consumer);

    assertThat(decoded.get("amount_minor")).isEqualTo(2_000_000L);
    // channel defaults to null when absent from the old producer bytes.
    assertThat(decoded.get("channel")).isNull();
  }

  @Test
  void newProducerBytesWithChannelDecodeUnderAnOldPreChannelReaderSchema() {
    // NEW-WRITER / OLD-READER: an already-deployed pre-Phase-B finance consumer (not yet upgraded)
    // must still be able to project bytes written by an upgraded (Phase B) vertical producer — the
    // channel field is simply dropped by Avro schema resolution, not an error.
    Schema newProducer = SaleRecordedSchema.schema();
    Schema oldConsumer = new Schema.Parser().parse(PRE_CHANNEL_SCHEMA_JSON);

    GenericRecord produced = new GenericData.Record(newProducer);
    produced.put("sale_id", "33333333-3333-3333-3333-333333333333");
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("business_id", "22222222-2222-2222-2222-222222222222");
    produced.put("amount_minor", 88_800_00L);
    produced.put("currency", "IDR");
    produced.put("occurred_at", 1_750_000_000_000L);
    produced.put("tender_type", "QRIS");
    produced.put("subtotal_minor", 88_800_00L);
    produced.put("discount_minor", 0L);
    produced.put("service_charge_minor", 0L);
    produced.put("tax_minor", 0L);
    produced.put("tax_rule_version", null);
    produced.put("uses_illustrative_rules", false);
    produced.put("loyalty_member_id", null);
    produced.put("loyalty_redeemed_points", null);
    produced.put("loyalty_redeemed_minor", null);
    produced.put("gift_card_id", null);
    produced.put("gift_card_redeemed_minor", null);
    // A future ONLINE-tender producer (Phase B2) would set a real channel here; this wave always
    // sends explicit null, but the schema round-trips a real value too (proves the wire is ready).
    produced.put("channel", "GOFOOD");

    byte[] wireBytes = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wireBytes, newProducer, oldConsumer);

    assertThat(decoded.get("amount_minor")).isEqualTo(88_800_00L);
    // The old reader schema simply has no such field.
    assertThat(decoded.getSchema().getField("channel")).isNull();
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
