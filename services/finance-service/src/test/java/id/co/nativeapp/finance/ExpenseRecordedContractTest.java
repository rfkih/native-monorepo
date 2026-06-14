package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.expense.ExpenseRecordedSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Test (f) — the {@code ExpenseRecorded} CONSUMER contract test (no Spring context needed).
 *
 * <p>finance owns its own consumer copy of the {@code ExpenseRecorded} schema ({@code
 * src/main/resources/avro/ExpenseRecorded.avsc}). This proves that copy (the rule-7 triad):
 *
 * <ul>
 *   <li>parses from the classpath and has the expected shape (full name + fields);
 *   <li>round-trips a {@link GenericRecord} through {@code libs/events} {@link AvroSerde}
 *       (serialize -> deserialize), the exact path the listener decodes off the wire; and
 *   <li>stays BACKWARD-COMPATIBLE with the PRODUCER schema (embedded verbatim from
 *       docs/EVENT-CATALOG.md) — a finance reader on its own copy can read bytes a producer wrote,
 *       and a deliberate incompatible break (a new required field with no default) is rejected.
 * </ul>
 */
class ExpenseRecordedContractTest {

  /** The producer's registered {@code ExpenseRecorded} schema, copied verbatim from the catalog. */
  private static final String PRODUCER_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "ExpenseRecorded",
        "namespace": "id.co.nativeapp.events.restaurant",
        "fields": [
          {"name": "expense_id", "type": "string"},
          {"name": "company_id", "type": "string"},
          {"name": "business_id", "type": "string"},
          {"name": "amount_minor", "type": "long"},
          {"name": "currency", "type": "string"},
          {"name": "gl_hint", "type": "string"},
          {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
        ]
      }
      """;

  @Test
  void consumerAvscParsesFromClasspath() {
    Schema schema = ExpenseRecordedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.restaurant.ExpenseRecorded");
    assertThat(schema.getField("expense_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("business_id")).isNotNull();
    assertThat(schema.getField("amount_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
    assertThat(schema.getField("gl_hint")).isNotNull();
    assertThat(schema.getField("occurred_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = ExpenseRecordedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("expense_id", "44444444-4444-4444-4444-444444444444");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("business_id", "22222222-2222-2222-2222-222222222222");
    record.put("amount_minor", 750_000L); // money is integer minor units, never a float
    record.put("currency", "IDR");
    record.put("gl_hint", "supplies");
    record.put("occurred_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("amount_minor")).isEqualTo(750_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("gl_hint").toString()).isEqualTo("supplies");
    assertThat(decoded.get("occurred_at")).isEqualTo(1_750_000_000_000L);
  }

  @Test
  void consumerCopyIsBackwardCompatibleWithTheProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = ExpenseRecordedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void bytesWrittenWithTheProducerSchemaDecodeUnderTheConsumerCopy() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = ExpenseRecordedSchema.schema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("expense_id", "44444444-4444-4444-4444-444444444444");
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("business_id", "22222222-2222-2222-2222-222222222222");
    produced.put("amount_minor", 900_000L);
    produced.put("currency", "IDR");
    produced.put("gl_hint", "cogs");
    produced.put("occurred_at", 1_750_000_000_000L);

    byte[] wireBytes = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wireBytes, producer, consumer);

    assertThat(decoded.get("amount_minor")).isEqualTo(900_000L);
    assertThat(decoded.get("gl_hint").toString()).isEqualTo("cogs");
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
                  "name": "ExpenseRecorded",
                  "namespace": "id.co.nativeapp.events.restaurant",
                  "fields": [
                    {"name": "expense_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "business_id", "type": "string"},
                    {"name": "amount_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "gl_hint", "type": "string"},
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "vendor_id", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, incompatible)).isFalse();
  }
}
