package id.co.nativeapp.notification;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.notification.notification.ConsolidationClosedSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Test (d) — the {@code ConsolidationClosed} CONSUMER contract test (no Spring context needed).
 *
 * <p>notification owns its own consumer copy of the {@code ConsolidationClosed} schema ({@code
 * src/main/resources/avro/ConsolidationClosed.avsc}). This proves that copy (the rule-7 triad):
 *
 * <ul>
 *   <li>parses from the classpath and has the expected shape (full name + fields);
 *   <li>round-trips a {@link GenericRecord} through {@code libs/events} {@link AvroSerde}
 *       (serialize -> deserialize), the exact path the listener decodes off the wire; and
 *   <li>stays BACKWARD-COMPATIBLE with the PRODUCER schema (embedded verbatim from
 *       docs/EVENT-CATALOG.md) — a notification reader on its own copy can read bytes finance
 *       wrote, and a deliberate incompatible break (a new required field with no default) is
 *       rejected.
 * </ul>
 */
class ConsolidationClosedContractTest {

  /** finance's registered {@code ConsolidationClosed} schema, copied verbatim from the catalog. */
  private static final String PRODUCER_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "ConsolidationClosed",
        "namespace": "id.co.nativeapp.events.finance",
        "fields": [
          {"name": "company_id", "type": "string"},
          {"name": "group_id", "type": ["null", "string"], "default": null},
          {"name": "period", "type": "string"}
        ]
      }
      """;

  @Test
  void consumerAvscParsesFromClasspath() {
    Schema schema = ConsolidationClosedSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.finance.ConsolidationClosed");
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("group_id")).isNotNull();
    assertThat(schema.getField("group_id").schema().getType()).isEqualTo(Schema.Type.UNION);
    assertThat(schema.getField("period")).isNotNull();
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = ConsolidationClosedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("group_id", null);
    record.put("period", "2026-06");

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("company_id").toString())
        .isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(decoded.get("group_id")).isNull();
    assertThat(decoded.get("period").toString()).isEqualTo("2026-06");
  }

  @Test
  void consumerCopyIsBackwardCompatibleWithTheProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = ConsolidationClosedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void bytesWrittenWithTheProducerSchemaDecodeUnderTheConsumerCopy() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = ConsolidationClosedSchema.schema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("group_id", "77777777-7777-7777-7777-777777777777");
    produced.put("period", "2026-05");

    byte[] wireBytes = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wireBytes, producer, consumer);

    assertThat(decoded.get("group_id").toString())
        .isEqualTo("77777777-7777-7777-7777-777777777777");
    assertThat(decoded.get("period").toString()).isEqualTo("2026-05");
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
                  "name": "ConsolidationClosed",
                  "namespace": "id.co.nativeapp.events.finance",
                  "fields": [
                    {"name": "company_id", "type": "string"},
                    {"name": "group_id", "type": ["null", "string"], "default": null},
                    {"name": "period", "type": "string"},
                    {"name": "closed_by", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, incompatible)).isFalse();
  }
}
