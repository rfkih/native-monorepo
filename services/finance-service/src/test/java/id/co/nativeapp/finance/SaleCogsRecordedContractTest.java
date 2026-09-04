package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.inventory.messaging.SaleCogsRecordedSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code SaleCogsRecorded} CONSUMER contract test (rule-7 triad, no Spring context; ADR 0067
 * Phase 0). Proves finance's reader view parses from the classpath, stays backward-compatible with
 * the producer schema (embedded verbatim from docs/EVENT-CATALOG.md), decodes bytes a producer
 * wrote, and that an incompatible break is rejected.
 */
class SaleCogsRecordedContractTest {

  /** The producer's registered schema, copied verbatim from the catalog (drift detector). */
  private static final String PRODUCER_SCHEMA_JSON =
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
          {"name": "currency", "type": "string"}
        ]
      }
      """;

  @Test
  void consumerViewParsesFromClasspath() {
    Schema schema = SaleCogsRecordedSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.restaurant.SaleCogsRecorded");
    assertThat(schema.getField("sale_id")).isNotNull();
    assertThat(schema.getField("business_id")).isNotNull();
    assertThat(schema.getField("cogs_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("occurred_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void consumerViewIsBackwardCompatibleWithTheProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    assertThat(AvroSerde.isBackwardCompatible(producer, SaleCogsRecordedSchema.schema())).isTrue();
  }

  @Test
  void bytesWrittenWithTheProducerSchemaDecodeUnderTheConsumerView() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = SaleCogsRecordedSchema.schema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("sale_id", "55555555-5555-5555-5555-555555555555");
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("business_id", "22222222-2222-2222-2222-222222222222");
    produced.put("occurred_at", 1_786_000_000_000L);
    produced.put("cogs_minor", 32_500L); // money is integer minor units, never a float
    produced.put("currency", "IDR");

    GenericRecord decoded =
        AvroSerde.deserialize(AvroSerde.serialize(produced), producer, consumer);

    assertThat(decoded.get("cogs_minor")).isEqualTo(32_500L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("business_id").toString())
        .isEqualTo("22222222-2222-2222-2222-222222222222");
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
    assertThat(AvroSerde.isBackwardCompatible(producer, incompatible)).isFalse();
  }
}
