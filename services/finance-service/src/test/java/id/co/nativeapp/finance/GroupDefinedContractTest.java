package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.group.messaging.GroupDefinedSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code GroupDefined} CONSUMER contract test (P3d SEAM 1, no Spring context needed).
 *
 * <p>finance owns its own consumer copy of the {@code GroupDefined} schema ({@code
 * src/main/resources/avro/GroupDefined.avsc}). This proves that copy (the rule-7 triad): it parses
 * from the classpath with the expected shape, round-trips a {@link GenericRecord} through {@code
 * libs/events} {@link AvroSerde} (the exact path the listener decodes off the wire), and stays
 * BACKWARD-COMPATIBLE with the PRODUCER schema (embedded verbatim from docs/EVENT-CATALOG.md) — a
 * finance reader on its own copy can read bytes a producer wrote, and a new required field with no
 * default is rejected.
 */
class GroupDefinedContractTest {

  /** The producer's registered {@code GroupDefined} schema, copied verbatim from the catalog. */
  private static final String PRODUCER_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "GroupDefined",
        "namespace": "id.co.nativeapp.events.org",
        "fields": [
          {"name": "group_id", "type": "string"},
          {"name": "lead_company_id", "type": "string"},
          {"name": "reporting_currency", "type": "string"},
          {"name": "name", "type": "string"}
        ]
      }
      """;

  @Test
  void consumerAvscParsesFromClasspath() {
    Schema schema = GroupDefinedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.org.GroupDefined");
    assertThat(schema.getField("group_id")).isNotNull();
    assertThat(schema.getField("lead_company_id")).isNotNull();
    assertThat(schema.getField("reporting_currency")).isNotNull();
    assertThat(schema.getField("name")).isNotNull();
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = GroupDefinedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("group_id", "11111111-1111-1111-1111-111111111111");
    record.put("lead_company_id", "22222222-2222-2222-2222-222222222222");
    record.put("reporting_currency", "IDR");
    record.put("name", "Indo Group");

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("reporting_currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("lead_company_id").toString())
        .isEqualTo("22222222-2222-2222-2222-222222222222");
  }

  @Test
  void consumerCopyIsBackwardCompatibleWithTheProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = GroupDefinedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void bytesWrittenWithTheProducerSchemaDecodeUnderTheConsumerCopy() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = GroupDefinedSchema.schema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("group_id", "11111111-1111-1111-1111-111111111111");
    produced.put("lead_company_id", "22222222-2222-2222-2222-222222222222");
    produced.put("reporting_currency", "USD");
    produced.put("name", "G");

    byte[] wireBytes = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wireBytes, producer, consumer);
    assertThat(decoded.get("reporting_currency").toString()).isEqualTo("USD");
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
                  "name": "GroupDefined",
                  "namespace": "id.co.nativeapp.events.org",
                  "fields": [
                    {"name": "group_id", "type": "string"},
                    {"name": "lead_company_id", "type": "string"},
                    {"name": "reporting_currency", "type": "string"},
                    {"name": "name", "type": "string"},
                    {"name": "owner_id", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, incompatible)).isFalse();
  }
}
