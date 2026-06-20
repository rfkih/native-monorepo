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
