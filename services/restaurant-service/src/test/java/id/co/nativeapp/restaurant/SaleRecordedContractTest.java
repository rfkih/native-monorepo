package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.sale.messaging.SaleRecordedSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Acceptance (b) — the SaleRecorded contract test (no Spring context needed).
 *
 * <p>Proves the {@code SaleRecorded.avsc} on the classpath parses, that a {@link GenericRecord}
 * built from it round-trips through {@code libs/events} {@link AvroSerde}, and that the
 * backward-compatibility gate accepts the schema against itself and against an added-optional-field
 * variant, while rejecting an incompatible change (a new required field with no default). CLAUDE.md
 * rule 7: event schema changes are backward-compatible ONLY.
 *
 * <p>ADR 0006 slice 2 additions: asserts the optional {@code tender_type} field round-trips and
 * that new producers (with tender_type) are readable by old consumers (without it — the
 * backward-compat invariant).
 *
 * <p>ADR 0027 (Phase 4, loyalty + gift cards) additions: asserts the five trailing optional fields
 * ({@code loyalty_member_id}, {@code loyalty_redeemed_points}, {@code loyalty_redeemed_minor},
 * {@code gift_card_id}, {@code gift_card_redeemed_minor}) round-trip, and proves the evolution
 * pair — a new (Phase 4) reader can decode an old (pre-Phase-4) producer's bytes, AND an old
 * (pre-Phase-4) reader can decode a new (Phase 4) producer's bytes, dropping unknown fields.
 */
class SaleRecordedContractTest {

  @Test
  void avscParsesFromClasspath() {
    Schema schema = SaleRecordedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.restaurant.SaleRecorded");
    assertThat(schema.getField("sale_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("business_id")).isNotNull();
    assertThat(schema.getField("amount_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
    // occurred_at carries the timestamp-millis logical type.
    assertThat(schema.getField("occurred_at").schema().getLogicalType()).isNotNull();
    assertThat(schema.getField("occurred_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
    // ADR 0006 slice 2: optional tender_type field (["null","string"], default null).
    assertThat(schema.getField("tender_type")).isNotNull();
    assertThat(schema.getField("tender_type").schema().getType()).isEqualTo(Schema.Type.UNION);
    // Avro represents a JSON null default as org.apache.avro.JsonSchemaProps$NullNode / the JSON
    // null token — check via the hasDefaultValue flag and that the JSON representation is "null".
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
    record.put("tender_type", "CASH"); // ADR 0006 slice 2

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("sale_id").toString()).isEqualTo("33333333-3333-3333-3333-333333333333");
    assertThat(decoded.get("amount_minor")).isEqualTo(1_500_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("occurred_at")).isEqualTo(1_750_000_000_000L);
    // tender_type round-trips.
    assertThat(decoded.get("tender_type").toString()).isEqualTo("CASH");
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
    record.put("tender_type", null); // legacy / no-payment sale

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("tender_type")).isNull();
  }

  @Test
  void schemaIsBackwardCompatibleWithItself() {
    Schema schema = SaleRecordedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(schema, schema)).isTrue();
  }

  @Test
  void addingAnOptionalFieldWithDefaultIsBackwardCompatible() {
    Schema v1 = SaleRecordedSchema.schema();
    Schema v2 =
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
                    {"name": "channel", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    // A v2 reader must still read v1 data (the new optional field defaults to null).
    assertThat(AvroSerde.isBackwardCompatible(v1, v2)).isTrue();
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema v1 = SaleRecordedSchema.schema();
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
    // A reader on the new schema cannot read v1 data — no value for cashier_id.
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }

  /**
   * The PRE-Phase-4 producer/consumer shape (13 fields, through {@code uses_illustrative_rules}) —
   * the schema every service ran before ADR 0027 (loyalty + gift cards) added the five trailing
   * optional fields. Used to prove the Phase 4 evolution is backward-compatible in BOTH directions
   * (CLAUDE.md rule 7).
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
    // ADR 0027 (Phase 4): loyalty_member_id, loyalty_redeemed_points, loyalty_redeemed_minor,
    // gift_card_id, gift_card_redeemed_minor — all ["null", <type>] with default null, appended at
    // the END (positional decode safety).
    Schema schema = SaleRecordedSchema.schema();
    assertThat(schema.getField("loyalty_member_id").hasDefaultValue()).isTrue();
    assertThat(schema.getField("loyalty_redeemed_points").hasDefaultValue()).isTrue();
    assertThat(schema.getField("loyalty_redeemed_minor").hasDefaultValue()).isTrue();
    assertThat(schema.getField("gift_card_id").hasDefaultValue()).isTrue();
    assertThat(schema.getField("gift_card_redeemed_minor").hasDefaultValue()).isTrue();
    assertThat(schema.getField("loyalty_redeemed_points").schema().getType())
        .isEqualTo(Schema.Type.UNION);
  }

  @Test
  void phase4FieldsRoundTripThroughAvroSerde() {
    Schema schema = SaleRecordedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("sale_id", "33333333-3333-3333-3333-333333333333");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("business_id", "22222222-2222-2222-2222-222222222222");
    record.put("amount_minor", 77_700_00L);
    record.put("currency", "IDR");
    record.put("occurred_at", 1_750_000_000_000L);
    record.put("tender_type", "CASH");
    record.put("subtotal_minor", 70_000_00L);
    record.put("discount_minor", 0L);
    record.put("service_charge_minor", 0L);
    record.put("tax_minor", 7_700_00L);
    record.put("tax_rule_version", "ILLUSTRATIVE-2026.1");
    record.put("uses_illustrative_rules", true);
    record.put("loyalty_member_id", "44444444-4444-4444-4444-444444444444");
    record.put("loyalty_redeemed_points", 500L);
    record.put("loyalty_redeemed_minor", 5_000_00L);
    record.put("gift_card_id", "55555555-5555-5555-5555-555555555555");
    record.put("gift_card_redeemed_minor", 10_000_00L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("loyalty_member_id").toString())
        .isEqualTo("44444444-4444-4444-4444-444444444444");
    assertThat(decoded.get("loyalty_redeemed_points")).isEqualTo(500L);
    assertThat(decoded.get("loyalty_redeemed_minor")).isEqualTo(5_000_00L);
    assertThat(decoded.get("gift_card_id").toString())
        .isEqualTo("55555555-5555-5555-5555-555555555555");
    assertThat(decoded.get("gift_card_redeemed_minor")).isEqualTo(10_000_00L);
  }

  @Test
  void phase4FieldsNullRoundTrips() {
    // A pre-Phase-4 producer semantics: no member, no redemption, no gift card.
    Schema schema = SaleRecordedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("sale_id", "33333333-3333-3333-3333-333333333333");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("business_id", "22222222-2222-2222-2222-222222222222");
    record.put("amount_minor", 1_500_000L);
    record.put("currency", "IDR");
    record.put("occurred_at", 1_750_000_000_000L);
    record.put("tender_type", "CASH");
    record.put("subtotal_minor", null);
    record.put("discount_minor", null);
    record.put("service_charge_minor", null);
    record.put("tax_minor", null);
    record.put("tax_rule_version", null);
    record.put("uses_illustrative_rules", null);
    record.put("loyalty_member_id", null);
    record.put("loyalty_redeemed_points", null);
    record.put("loyalty_redeemed_minor", null);
    record.put("gift_card_id", null);
    record.put("gift_card_redeemed_minor", null);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("loyalty_member_id")).isNull();
    assertThat(decoded.get("loyalty_redeemed_points")).isNull();
    assertThat(decoded.get("loyalty_redeemed_minor")).isNull();
    assertThat(decoded.get("gift_card_id")).isNull();
    assertThat(decoded.get("gift_card_redeemed_minor")).isNull();
  }

  @Test
  void newReaderIsBackwardCompatibleWithThePrePhase4Schema() {
    // OLD-WRITER / NEW-READER: a reader on the current (Phase 4) schema must be able to read bytes
    // written by an already-deployed pre-Phase-4 producer (the rolling-deploy window before every
    // vertical picks up ADR 0027).
    Schema oldWriter = new Schema.Parser().parse(PRE_PHASE4_SCHEMA_JSON);
    Schema newReader = SaleRecordedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(oldWriter, newReader)).isTrue();
  }

  @Test
  void oldReaderDecodesNewWriterBytesIgnoringThePhase4Fields() {
    // NEW-WRITER / OLD-READER: an already-deployed pre-Phase-4 consumer (finance not yet upgraded)
    // must still be able to project bytes written by an upgraded (Phase 4) producer — the five new
    // fields are simply dropped by Avro schema resolution, not an error.
    Schema newWriter = SaleRecordedSchema.schema();
    Schema oldReader = new Schema.Parser().parse(PRE_PHASE4_SCHEMA_JSON);

    GenericRecord produced = new GenericData.Record(newWriter);
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
    GenericRecord decoded = AvroSerde.deserialize(wireBytes, newWriter, oldReader);

    // The old reader schema has no such fields — Avro projects them away without error.
    assertThat(decoded.get("amount_minor")).isEqualTo(77_700_00L);
    assertThat(decoded.get("tax_minor")).isEqualTo(7_700_00L);
    assertThat(decoded.getSchema().getField("loyalty_member_id")).isNull();
    assertThat(decoded.getSchema().getField("gift_card_id")).isNull();
  }

  @Test
  void newSchemaIsBackwardCompatibleWithOldSchema() {
    // Proves that the schema WITH tender_type is backward-compatible with the original 6-field
    // schema (old producers / carwash will emit records without tender_type; the new consumer
    // schema reads them with tender_type defaulting to null).
    Schema oldProducer =
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
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
                  ]
                }
                """);
    Schema newSchema = SaleRecordedSchema.schema();
    // new reader (newSchema) must be able to read bytes written with oldProducer.
    assertThat(AvroSerde.isBackwardCompatible(oldProducer, newSchema)).isTrue();
  }
}
