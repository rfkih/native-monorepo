package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.inventory.messaging.StockReceivedSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code StockReceived} PRODUCER contract test (rule-7 triad, no Spring context; ADR 0067 Phase
 * 0 — contracts-first). Asserts the registered schema parses with the documented shape, round-trips
 * through {@code libs/events} {@link AvroSerde} (the exact outbox wire path an eventual {@code
 * IngredientWriter.addStock} priced-receive branch will use, Phase B), and that schema evolution
 * stays gated to backward-compatible-only (rule 7): a required field without a default breaks, an
 * optional field with a default is accepted.
 */
class StockReceivedContractTest {

  @Test
  void schemaParsesFromClasspathWithTheDocumentedShape() {
    Schema schema = StockReceivedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.restaurant.StockReceived");
    assertThat(schema.getField("receipt_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("business_id")).isNotNull();
    assertThat(schema.getField("ingredient_id")).isNotNull();
    assertThat(schema.getField("qty").schema().getType()).isEqualTo(Schema.Type.LONG);
    // value_minor is a required long — minor units, never a float (rule 8).
    assertThat(schema.getField("value_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
    assertThat(schema.getField("received_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = StockReceivedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("receipt_id", "55555555-5555-5555-5555-555555555555");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("business_id", "22222222-2222-2222-2222-222222222222");
    record.put("ingredient_id", "33333333-3333-3333-3333-333333333333");
    record.put("qty", 50L);
    record.put("value_minor", 750_000L); // money is integer minor units, never a float
    record.put("currency", "IDR");
    record.put("received_at", 1_786_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("receipt_id").toString())
        .isEqualTo("55555555-5555-5555-5555-555555555555");
    assertThat(decoded.get("ingredient_id").toString())
        .isEqualTo("33333333-3333-3333-3333-333333333333");
    assertThat(decoded.get("qty")).isEqualTo(50L);
    assertThat(decoded.get("value_minor")).isEqualTo(750_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("received_at")).isEqualTo(1_786_000_000_000L);
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema registered = StockReceivedSchema.schema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "StockReceived",
                  "namespace": "id.co.nativeapp.events.restaurant",
                  "fields": [
                    {"name": "receipt_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "business_id", "type": "string"},
                    {"name": "ingredient_id", "type": "string"},
                    {"name": "qty", "type": "long"},
                    {"name": "value_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "received_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "vendor_id", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(registered, incompatible)).isFalse();
  }

  @Test
  void addingAnOptionalFieldWithADefaultStaysBackwardCompatible() {
    Schema registered = StockReceivedSchema.schema();
    Schema evolved =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "StockReceived",
                  "namespace": "id.co.nativeapp.events.restaurant",
                  "fields": [
                    {"name": "receipt_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "business_id", "type": "string"},
                    {"name": "ingredient_id", "type": "string"},
                    {"name": "qty", "type": "long"},
                    {"name": "value_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "received_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "vendor_note", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(registered, evolved)).isTrue();
  }
}
