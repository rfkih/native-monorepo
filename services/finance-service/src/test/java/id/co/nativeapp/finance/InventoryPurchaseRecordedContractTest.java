package id.co.nativeapp.finance;

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
 * The {@code InventoryPurchaseRecorded} PRODUCER contract test (ADR 0072 P0, no Spring context).
 *
 * <p>The schema lives in {@code libs/contracts} ({@code avro/InventoryPurchaseRecorded.avsc}) — the
 * single source of truth for finance (producer) and restaurant (consumer). This proves the rule-7
 * triad before either side ships code: the {@code .avsc} parses from the classpath with the
 * expected shape, a {@link GenericRecord} round-trips through {@code libs/events} {@link AvroSerde}
 * (the exact bytes the outbox will carry), and the classpath schema stays BACKWARD-COMPATIBLE with
 * the registered producer schema (embedded verbatim from docs/EVENT-CATALOG.md), while a new
 * required field with no default is rejected.
 */
class InventoryPurchaseRecordedContractTest {

  static final String RESOURCE = "avro/InventoryPurchaseRecorded.avsc";

  /** The registered producer schema, copied verbatim from the catalog. */
  static final String PRODUCER_SCHEMA_JSON =
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

  static Schema classpathSchema() {
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
  void avscParsesFromClasspathWithExpectedShape() {
    Schema schema = classpathSchema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.finance.InventoryPurchaseRecorded");
    assertThat(schema.getField("purchase_id")).isNotNull();
    assertThat(schema.getField("source")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("currency")).isNotNull();
    assertThat(schema.getField("occurred_at")).isNotNull();
    assertThat(schema.getField("lines")).isNotNull();
    assertThat(schema.getField("lines").schema().getType()).isEqualTo(Schema.Type.ARRAY);
    Schema line = schema.getField("lines").schema().getElementType();
    assertThat(line.getField("line_id")).isNotNull();
    assertThat(line.getField("ingredient_id")).isNotNull();
    assertThat(line.getField("qty_base")).isNotNull();
    assertThat(line.getField("value_minor")).isNotNull();
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = classpathSchema();
    Schema lineSchema = schema.getField("lines").schema().getElementType();

    UUID purchaseId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID ingredientId = UUID.randomUUID();
    GenericRecord line = new GenericData.Record(lineSchema);
    line.put("line_id", lineId.toString());
    line.put("ingredient_id", ingredientId.toString());
    line.put("qty_base", 2_500L);
    line.put("value_minor", 150_000L);
    GenericRecord event = new GenericData.Record(schema);
    event.put("purchase_id", purchaseId.toString());
    event.put("source", "EXPENSE");
    event.put("company_id", "11111111-1111-1111-1111-111111111111");
    event.put("currency", "IDR");
    event.put("occurred_at", 1_756_857_600_000L);
    event.put("lines", List.of(line));

    byte[] bytes = AvroSerde.serialize(event);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("purchase_id").toString()).isEqualTo(purchaseId.toString());
    assertThat(decoded.get("source").toString()).isEqualTo("EXPENSE");
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("occurred_at")).isEqualTo(1_756_857_600_000L);
    @SuppressWarnings("unchecked")
    List<GenericRecord> lines = (List<GenericRecord>) decoded.get("lines");
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).get("line_id").toString()).isEqualTo(lineId.toString());
    assertThat(lines.get(0).get("ingredient_id").toString()).isEqualTo(ingredientId.toString());
    assertThat(lines.get(0).get("qty_base")).isEqualTo(2_500L);
    assertThat(lines.get(0).get("value_minor")).isEqualTo(150_000L);
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
