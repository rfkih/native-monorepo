package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.giftcard.messaging.GiftCardSoldSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Producer-side contract test for the {@code GiftCardSold} event (ADR 0027, Phase 4) — mirrors
 * {@code SaleRecordedContractTest}'s per-service pattern (restaurant-service is one of THREE
 * producers sharing the one stable {@code id.co.nativeapp.events.restaurant} namespace, EVENT-
 * CATALOG.md "namespace decision").
 *
 * <p>Proves the {@code GiftCardSold.avsc} on the classpath parses with the catalog shape, that a
 * {@link GenericRecord} built from it round-trips through {@code libs/events} {@link AvroSerde},
 * and the backward-compatibility gate accepts the schema against itself while rejecting a breaking
 * change (a new required field with no default) — CLAUDE.md rule 7.
 */
class GiftCardSoldContractTest {

  @Test
  void avscParsesFromClasspathWithExpectedShape() {
    Schema schema = GiftCardSoldSchema.schema();

    // House-style precedent (SaleRecorded/SaleVoided/...): namespace stays "restaurant" — the
    // historic producer-of-record — regardless of which vertical actually emits it.
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.restaurant.GiftCardSold");
    assertThat(schema.getField("gift_card_sale_id")).isNotNull();
    assertThat(schema.getField("gift_card_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("business_id")).isNotNull();
    assertThat(schema.getField("amount_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
    assertThat(schema.getField("tender_type").schema().getType()).isEqualTo(Schema.Type.UNION);
    assertThat(schema.getField("tender_type").hasDefaultValue()).isTrue();
    assertThat(schema.getField("occurred_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = GiftCardSoldSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("gift_card_sale_id", "33333333-3333-3333-3333-333333333333");
    record.put("gift_card_id", "44444444-4444-4444-4444-444444444444");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("business_id", "22222222-2222-2222-2222-222222222222");
    record.put("amount_minor", 100_000L); // money is integer minor units, never a float
    record.put("currency", "IDR");
    record.put("tender_type", "CASH");
    record.put("occurred_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("gift_card_sale_id").toString())
        .isEqualTo("33333333-3333-3333-3333-333333333333");
    assertThat(decoded.get("gift_card_id").toString())
        .isEqualTo("44444444-4444-4444-4444-444444444444");
    assertThat(decoded.get("amount_minor")).isEqualTo(100_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("tender_type").toString()).isEqualTo("CASH");
  }

  @Test
  void tenderTypeNullRoundTrips() {
    Schema schema = GiftCardSoldSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("gift_card_sale_id", "33333333-3333-3333-3333-333333333333");
    record.put("gift_card_id", "44444444-4444-4444-4444-444444444444");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("business_id", "22222222-2222-2222-2222-222222222222");
    record.put("amount_minor", 100_000L);
    record.put("currency", "IDR");
    record.put("tender_type", null); // legacy / unspecified tender
    record.put("occurred_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("tender_type")).isNull();
  }

  @Test
  void schemaIsBackwardCompatibleWithItself() {
    Schema schema = GiftCardSoldSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(schema, schema)).isTrue();
  }

  @Test
  void addingAnOptionalFieldWithDefaultIsBackwardCompatible() {
    Schema v1 = GiftCardSoldSchema.schema();
    Schema v2 =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "GiftCardSold",
                  "namespace": "id.co.nativeapp.events.restaurant",
                  "fields": [
                    {"name": "gift_card_sale_id", "type": "string"},
                    {"name": "gift_card_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "business_id", "type": "string"},
                    {"name": "amount_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "tender_type", "type": ["null", "string"], "default": null},
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "channel", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, v2)).isTrue();
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema v1 = GiftCardSoldSchema.schema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "GiftCardSold",
                  "namespace": "id.co.nativeapp.events.restaurant",
                  "fields": [
                    {"name": "gift_card_sale_id", "type": "string"},
                    {"name": "gift_card_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "business_id", "type": "string"},
                    {"name": "amount_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "tender_type", "type": ["null", "string"], "default": null},
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "cashier_id", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }
}
