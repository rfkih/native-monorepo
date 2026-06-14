package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.carwash.metric.MetricPublishedSchema;
import id.co.nativeapp.events.AvroSerde;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Test (f) — the contract triad for the PRODUCED {@code MetricPublished} event (no Spring context
 * needed; CLAUDE.md rule 7 / ENGINEERING-STANDARDS §3.2).
 *
 * <p>Proves the {@code MetricPublished.avsc} on the classpath (a) parses with the expected shape
 * (full name + the §5 key fields metric_key/period/grain/subject_id/value/source_business_id), (b)
 * round-trips a {@link GenericRecord} through {@code libs/events AvroSerde} (the exact path the
 * outbox serializes on), and (c) backward-compatibility: the schema is compatible with itself and
 * with an added-optional-field variant, while a new required field with no default is rejected.
 */
class MetricPublishedContractTest {

  @Test
  void avscParsesWithTheExpectedShape() {
    Schema schema = MetricPublishedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.carwash.MetricPublished");
    assertThat(schema.getField("metric_key")).isNotNull();
    assertThat(schema.getField("period")).isNotNull();
    assertThat(schema.getField("grain")).isNotNull();
    assertThat(schema.getField("subject_id")).isNotNull();
    assertThat(schema.getField("value").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("source_business_id")).isNotNull();
  }

  @Test
  void roundTripsThroughAvroSerde() {
    GenericRecord record =
        MetricPublishedSchema.toRecord(
            "wash_count",
            "2026-06-14",
            "outlet",
            "22222222-2222-2222-2222-222222222222",
            1L,
            "22222222-2222-2222-2222-222222222222");
    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, MetricPublishedSchema.schema());
    assertThat(decoded.get("metric_key").toString()).isEqualTo("wash_count");
    assertThat(decoded.get("grain").toString()).isEqualTo("outlet");
    assertThat(decoded.get("value")).isEqualTo(1L);
    assertThat(decoded.get("source_business_id").toString())
        .isEqualTo("22222222-2222-2222-2222-222222222222");
  }

  @Test
  void isBackwardCompatibleWithItselfAndAnAddedOptionalField() {
    Schema v1 = MetricPublishedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(v1, v1)).isTrue();
    Schema v2 =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "MetricPublished",
                  "namespace": "id.co.nativeapp.events.carwash",
                  "fields": [
                    {"name": "metric_key", "type": "string"},
                    {"name": "period", "type": "string"},
                    {"name": "grain", "type": "string"},
                    {"name": "subject_id", "type": "string"},
                    {"name": "value", "type": "long"},
                    {"name": "source_business_id", "type": "string"},
                    {"name": "unit", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, v2)).isTrue();
  }

  @Test
  void rejectsANewRequiredFieldWithoutDefault() {
    Schema v1 = MetricPublishedSchema.schema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "MetricPublished",
                  "namespace": "id.co.nativeapp.events.carwash",
                  "fields": [
                    {"name": "metric_key", "type": "string"},
                    {"name": "period", "type": "string"},
                    {"name": "grain", "type": "string"},
                    {"name": "subject_id", "type": "string"},
                    {"name": "value", "type": "long"},
                    {"name": "source_business_id", "type": "string"},
                    {"name": "currency", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }
}
