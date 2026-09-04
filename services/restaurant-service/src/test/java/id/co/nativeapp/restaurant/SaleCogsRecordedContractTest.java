package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.recipe.messaging.SaleCogsRecordedSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code SaleCogsRecorded} PRODUCER contract test (rule-7 triad, no Spring context; ADR 0067
 * Phase 0 — contracts-first). Asserts the registered schema parses with the documented shape,
 * round-trips through {@code libs/events} {@link AvroSerde} (the exact outbox wire path an eventual
 * {@code IngredientDepletionWriter} will use, Phase C), and that schema evolution stays gated to
 * backward-compatible-only (rule 7): a required field without a default breaks, an optional field
 * with a default is accepted.
 */
class SaleCogsRecordedContractTest {

  @Test
  void schemaParsesFromClasspathWithTheDocumentedShape() {
    Schema schema = SaleCogsRecordedSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.restaurant.SaleCogsRecorded");
    assertThat(schema.getField("sale_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("business_id")).isNotNull();
    assertThat(schema.getField("occurred_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
    // cogs_minor is a required long — minor units, never a float (rule 8).
    assertThat(schema.getField("cogs_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = SaleCogsRecordedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("sale_id", "55555555-5555-5555-5555-555555555555");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("business_id", "22222222-2222-2222-2222-222222222222");
    record.put("occurred_at", 1_786_000_000_000L);
    record.put("cogs_minor", 32_500L); // money is integer minor units, never a float
    record.put("currency", "IDR");

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("sale_id").toString()).isEqualTo("55555555-5555-5555-5555-555555555555");
    assertThat(decoded.get("business_id").toString())
        .isEqualTo("22222222-2222-2222-2222-222222222222");
    assertThat(decoded.get("occurred_at")).isEqualTo(1_786_000_000_000L);
    assertThat(decoded.get("cogs_minor")).isEqualTo(32_500L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema registered = SaleCogsRecordedSchema.schema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "SaleCogsRecorded",
                  "namespace": "id.co.nativeapp.events.restaurant",
                  "fields": [
                    {"name": "sale_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "business_id", "type": "string"},
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "cogs_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "recipe_version", "type": "long"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(registered, incompatible)).isFalse();
  }

  @Test
  void addingAnOptionalFieldWithADefaultStaysBackwardCompatible() {
    Schema registered = SaleCogsRecordedSchema.schema();
    Schema evolved =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "SaleCogsRecorded",
                  "namespace": "id.co.nativeapp.events.restaurant",
                  "fields": [
                    {"name": "sale_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "business_id", "type": "string"},
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "cogs_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "note", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(registered, evolved)).isTrue();
  }
}
