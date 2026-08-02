package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.reversal.messaging.SaleVoidedSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Consumer contract test for the {@code SaleVoided} schema (ADR 0006, slice 4).
 *
 * <p>Asserts the schema parses from the classpath, has the expected shape, round-trips through
 * {@link AvroSerde}, and is backward-compatible with an old producer that lacks {@code tender_type}
 * (the optional field defaults to {@code null} when absent from old producer bytes).
 */
class SaleVoidedContractTest {

  private static final String ORIGINAL_PRODUCER_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "SaleVoided",
        "namespace": "id.co.nativeapp.events.restaurant",
        "fields": [
          {"name": "void_id", "type": "string"},
          {"name": "sale_id", "type": "string"},
          {"name": "payment_id", "type": "string"},
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
    Schema schema = SaleVoidedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.restaurant.SaleVoided");
    assertThat(schema.getField("void_id")).isNotNull();
    assertThat(schema.getField("sale_id")).isNotNull();
    assertThat(schema.getField("payment_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("business_id")).isNotNull();
    assertThat(schema.getField("amount_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
    assertThat(schema.getField("occurred_at").schema().getLogicalType()).isNotNull();
    assertThat(schema.getField("tender_type")).isNotNull();
    assertThat(schema.getField("tender_type").schema().getType()).isEqualTo(Schema.Type.UNION);
    assertThat(schema.getField("tender_type").hasDefaultValue()).isTrue();
    // Phase B (ADR 0036): channel is additive — nullable union with a default (rule 7).
    assertThat(schema.getField("channel")).isNotNull();
    assertThat(schema.getField("channel").schema().getType()).isEqualTo(Schema.Type.UNION);
    assertThat(schema.getField("channel").hasDefaultValue()).isTrue();
  }

  @Test
  void genericRecordRoundTrips() {
    Schema schema = SaleVoidedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("void_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    record.put("sale_id", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    record.put("payment_id", "cccccccc-cccc-cccc-cccc-cccccccccccc");
    record.put("company_id", "dddddddd-dddd-dddd-dddd-dddddddddddd");
    record.put("business_id", "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    record.put("amount_minor", 30_000L);
    record.put("currency", "IDR");
    record.put("occurred_at", 1_750_000_000_000L);
    record.put("tender_type", "QRIS");
    record.put("channel", null);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("void_id").toString()).isEqualTo("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    assertThat(decoded.get("amount_minor")).isEqualTo(30_000L);
    assertThat(decoded.get("tender_type").toString()).isEqualTo("QRIS");
  }

  @Test
  void tenderTypeNullRoundTrips() {
    Schema schema = SaleVoidedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("void_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    record.put("sale_id", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    record.put("payment_id", "cccccccc-cccc-cccc-cccc-cccccccccccc");
    record.put("company_id", "dddddddd-dddd-dddd-dddd-dddddddddddd");
    record.put("business_id", "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    record.put("amount_minor", 30_000L);
    record.put("currency", "IDR");
    record.put("occurred_at", 1_750_000_000_000L);
    record.put("tender_type", null);
    record.put("channel", null);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("tender_type")).isNull();
  }

  @Test
  void backwardCompatibleWithOldProducerWithoutTenderType() {
    Schema producer = new Schema.Parser().parse(ORIGINAL_PRODUCER_SCHEMA_JSON);
    Schema consumer = SaleVoidedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }
}
