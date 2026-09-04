package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code InventoryPurchaseRecorded} CONSUMER contract test (ADR 0072 P0, no Spring context).
 *
 * <p>restaurant-service reads the SAME {@code libs/contracts} {@code .avsc} the finance producer
 * writes with, so a produced event is decode-compatible by construction — this test pins that: the
 * schema parses from this service's classpath with the consumer-relied shape, bytes written with
 * the registered producer schema (embedded verbatim from docs/EVENT-CATALOG.md) decode under the
 * classpath copy, and a required-field addition is rejected (rule 7).
 */
class InventoryPurchaseRecordedContractTest {

  private static final String RESOURCE = "avro/InventoryPurchaseRecorded.avsc";

  /** The registered producer schema, copied verbatim from the catalog. */
  private static final String PRODUCER_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "InventoryPurchaseRecorded",
        "namespace": "id.co.nativeapp.events.finance",
        "fields": [
          {"name": "purchase_id", "type": "string"},
          {"name": "source", "type": "string"},
          {"name": "company_id", "type": "string"},
          {"name": "currency", "type": "string"},
          {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
          {
            "name": "lines",
            "type": {
              "type": "array",
              "items": {
                "type": "record",
                "name": "InventoryPurchaseLine",
                "fields": [
                  {"name": "line_id", "type": "string"},
                  {"name": "ingredient_id", "type": "string"},
                  {"name": "qty_base", "type": "long"},
                  {"name": "value_minor", "type": "long"}
                ]
              }
            }
          }
        ]
      }
      """;

  private static Schema classpathSchema() {
    try (InputStream in =
        InventoryPurchaseRecordedContractTest.class
            .getClassLoader()
            .getResourceAsStream(RESOURCE)) {
      assertThat(in).as("schema on classpath: %s", RESOURCE).isNotNull();
      return new Schema.Parser().parse(in);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Test
  void avscParsesFromClasspathWithTheConsumerReliedShape() {
    Schema schema = classpathSchema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.finance.InventoryPurchaseRecorded");
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("currency")).isNotNull();
    Schema line = schema.getField("lines").schema().getElementType();
    assertThat(line.getField("line_id")).isNotNull();
    assertThat(line.getField("ingredient_id")).isNotNull();
    assertThat(line.getField("qty_base")).isNotNull();
    assertThat(line.getField("value_minor")).isNotNull();
  }

  @Test
  void bytesWrittenWithTheProducerSchemaDecodeUnderTheClasspathCopy() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = classpathSchema();

    GenericRecord line =
        new GenericData.Record(producer.getField("lines").schema().getElementType());
    line.put("line_id", UUID.randomUUID().toString());
    line.put("ingredient_id", UUID.randomUUID().toString());
    line.put("qty_base", 1_000L);
    line.put("value_minor", 75_000L);
    GenericRecord produced = new GenericData.Record(producer);
    produced.put("purchase_id", UUID.randomUUID().toString());
    produced.put("source", "BILL");
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("currency", "IDR");
    produced.put("occurred_at", 1_756_857_600_000L);
    produced.put("lines", List.of(line));

    byte[] wireBytes = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wireBytes, producer, consumer);

    assertThat(decoded.get("source").toString()).isEqualTo("BILL");
    @SuppressWarnings("unchecked")
    List<GenericRecord> lines = (List<GenericRecord>) decoded.get("lines");
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).get("value_minor")).isEqualTo(75_000L);
  }

  @Test
  void classpathSchemaIsBackwardCompatibleWithTheRegisteredProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    assertThat(AvroSerde.isBackwardCompatible(producer, classpathSchema())).isTrue();
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema incompatible =
        new Schema.Parser()
            .parse(
                PRODUCER_SCHEMA_JSON.replace(
                    "{\"name\": \"currency\", \"type\": \"string\"},",
                    "{\"name\": \"currency\", \"type\": \"string\"},"
                        + " {\"name\": \"approver_id\", \"type\": \"string\"},"));
    assertThat(AvroSerde.isBackwardCompatible(producer, incompatible)).isFalse();
  }
}
