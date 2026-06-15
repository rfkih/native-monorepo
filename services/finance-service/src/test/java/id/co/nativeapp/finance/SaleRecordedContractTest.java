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
 * <p>finance owns its own consumer copy of the {@code SaleRecorded} schema ({@code
 * src/main/resources/avro/SaleRecorded.avsc}). This proves that copy:
 *
 * <ul>
 *   <li>parses from the classpath and has the expected shape (full name + fields);
 *   <li>round-trips a {@link GenericRecord} through {@code libs/events} {@link AvroSerde}
 *       (serialize -> deserialize), the exact path the listener decodes off the wire; and
 *   <li>stays BACKWARD-COMPATIBLE with the PRODUCER schema (restaurant-service's, embedded below
 *       verbatim from docs/EVENT-CATALOG.md) — a finance reader on its own copy can read bytes a
 *       producer wrote, and a deliberate incompatible break (a new required field with no default)
 *       is rejected. CLAUDE.md rule 7: event schema changes are backward-compatible ONLY.
 * </ul>
 */
class SaleRecordedContractTest {

  /**
   * The producer's registered {@code SaleRecorded} schema, copied verbatim from
   * docs/EVENT-CATALOG.md (services/restaurant-service/src/main/resources/avro/SaleRecorded.avsc).
   * The contract test pins finance's consumer copy to it.
   */
  private static final String PRODUCER_SCHEMA_JSON =
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

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("sale_id").toString()).isEqualTo("33333333-3333-3333-3333-333333333333");
    assertThat(decoded.get("amount_minor")).isEqualTo(1_500_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("occurred_at")).isEqualTo(1_750_000_000_000L);
  }

  @Test
  void consumerCopyIsBackwardCompatibleWithTheProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = SaleRecordedSchema.schema();
    // A finance reader on its consumer copy must read bytes a producer wrote.
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void bytesWrittenWithTheProducerSchemaDecodeUnderTheConsumerCopy() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = SaleRecordedSchema.schema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("sale_id", "33333333-3333-3333-3333-333333333333");
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("business_id", "22222222-2222-2222-2222-222222222222");
    produced.put("amount_minor", 2_000_000L);
    produced.put("currency", "IDR");
    produced.put("occurred_at", 1_750_000_000_000L);

    byte[] wireBytes = AvroSerde.serialize(produced);
    // Project producer bytes onto the consumer copy (Avro schema resolution).
    GenericRecord decoded = AvroSerde.deserialize(wireBytes, producer, consumer);

    assertThat(decoded.get("amount_minor")).isEqualTo(2_000_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
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
                  "name": "SaleRecorded",
                  "namespace": "id.co.nativeapp.events.restaurant",
                  "fields": [
                    {"name": "sale_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "business_id", "type": "string"},
                    {"name": "amount_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "cashier_id", "type": "string"}
                  ]
                }
                """);
    // A reader on the new schema cannot read producer data — no value for cashier_id.
    assertThat(AvroSerde.isBackwardCompatible(producer, incompatible)).isFalse();
  }
}
