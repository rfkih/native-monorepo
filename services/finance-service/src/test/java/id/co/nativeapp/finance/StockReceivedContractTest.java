package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.inventory.messaging.StockReceivedSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code StockReceived} CONSUMER contract test (rule-7 triad, no Spring context; ADR 0067 Phase
 * 0). Proves finance's reader view parses from the classpath, stays backward-compatible with the
 * producer schema (embedded verbatim from docs/EVENT-CATALOG.md), decodes bytes a producer wrote,
 * and that an incompatible break is rejected.
 */
class StockReceivedContractTest {

  /** The producer's registered schema, copied verbatim from the catalog (drift detector). */
  private static final String PRODUCER_SCHEMA_JSON =
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
          {"name": "received_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
        ]
      }
      """;

  @Test
  void consumerViewParsesFromClasspath() {
    Schema schema = StockReceivedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.restaurant.StockReceived");
    assertThat(schema.getField("receipt_id")).isNotNull();
    assertThat(schema.getField("ingredient_id")).isNotNull();
    assertThat(schema.getField("qty").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("value_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("received_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void consumerViewIsBackwardCompatibleWithTheProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    assertThat(AvroSerde.isBackwardCompatible(producer, StockReceivedSchema.schema())).isTrue();
  }

  @Test
  void bytesWrittenWithTheProducerSchemaDecodeUnderTheConsumerView() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = StockReceivedSchema.schema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("receipt_id", "55555555-5555-5555-5555-555555555555");
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("business_id", "22222222-2222-2222-2222-222222222222");
    produced.put("ingredient_id", "33333333-3333-3333-3333-333333333333");
    produced.put("qty", 50L);
    produced.put("value_minor", 750_000L); // money is integer minor units, never a float
    produced.put("currency", "IDR");
    produced.put("received_at", 1_786_000_000_000L);

    GenericRecord decoded =
        AvroSerde.deserialize(AvroSerde.serialize(produced), producer, consumer);

    assertThat(decoded.get("value_minor")).isEqualTo(750_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("ingredient_id").toString())
        .isEqualTo("33333333-3333-3333-3333-333333333333");
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
    assertThat(AvroSerde.isBackwardCompatible(producer, incompatible)).isFalse();
  }
}
