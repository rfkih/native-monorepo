package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.carwash.wash.SaleRecordedSchema;
import id.co.nativeapp.events.AvroSerde;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Test (f) — the {@code SaleRecorded} PRODUCER-copy contract test (no Spring context needed; rule
 * 7).
 *
 * <p>carwash emits {@code SaleRecorded} on the SAME Avro contract finance already consumes (the
 * restaurant-service producer schema). This proves carwash's copy parses, has the expected shape
 * and full name, round-trips through {@code libs/events AvroSerde} (the exact path the outbox
 * serializes on), stays mutually backward-compatible with the producer (restaurant) schema — so
 * finance reads carwash washes through the very same consumer path — and that the back-compat gate
 * accepts an added-optional field while rejecting a new required field with no default.
 */
class SaleRecordedContractTest {

  /**
   * The restaurant-service producer schema (source of truth), inlined from docs/EVENT-CATALOG.md.
   */
  private static final Schema PRODUCER_SCHEMA =
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

  @Test
  void avscParsesWithTheExpectedShape() {
    Schema schema = SaleRecordedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.restaurant.SaleRecorded");
    assertThat(schema.getField("sale_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("business_id")).isNotNull();
    assertThat(schema.getField("amount_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
    assertThat(schema.getField("occurred_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = SaleRecordedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("sale_id", "33333333-3333-3333-3333-333333333333");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("business_id", "22222222-2222-2222-2222-222222222222"); // the carwash outlet
    record.put("amount_minor", 4_500_000L); // money is integer minor units, never a float
    record.put("currency", "IDR");
    record.put("occurred_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("sale_id").toString()).isEqualTo("33333333-3333-3333-3333-333333333333");
    assertThat(decoded.get("amount_minor")).isEqualTo(4_500_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
  }

  @Test
  void carwashCopyIsMutuallyBackwardCompatibleWithTheRestaurantProducerSchema() {
    Schema consumerCopy = SaleRecordedSchema.schema();
    // finance reads carwash's bytes with the producer schema, and vice versa — both directions must
    // be compatible so the SAME ledger path consolidates carwash + restaurant.
    assertThat(AvroSerde.isBackwardCompatible(PRODUCER_SCHEMA, consumerCopy)).isTrue();
    assertThat(AvroSerde.isBackwardCompatible(consumerCopy, PRODUCER_SCHEMA)).isTrue();
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
                    {"name": "channel", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
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
                    {"name": "attendant_id", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }
}
