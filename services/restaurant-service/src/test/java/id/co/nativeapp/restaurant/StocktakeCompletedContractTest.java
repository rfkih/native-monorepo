package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.stocktake.messaging.StocktakeCompletedSchema;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Contract test for {@code StocktakeCompleted} (ADR 0038 phase 3) — producer side, no Spring
 * context. Proves the single-source {@code .avsc} parses, a record built by the schema holder
 * round-trips through {@code libs/events} {@link AvroSerde} with the shrinkage figure intact
 * (finance posts money from it), and the schema is self-compatible (rule 7).
 */
class StocktakeCompletedContractTest {

  @Test
  void avscParsesFromClasspath() {
    Schema schema = StocktakeCompletedSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.restaurant.StocktakeCompleted");
    assertThat(schema.getField("stocktake_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("business_id")).isNotNull();
    assertThat(schema.getField("counted_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
    // shrinkage_minor is a required long — minor units, never a float (rule 8).
    assertThat(schema.getField("shrinkage_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
  }

  @Test
  void recordRoundTripsThroughAvroSerdeWithShrinkageIntact() {
    UUID stocktakeId = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
    UUID businessId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    GenericRecord record =
        StocktakeCompletedSchema.toRecord(
            stocktakeId,
            "11111111-1111-1111-1111-111111111111",
            businessId,
            Instant.ofEpochMilli(1_750_000_000_000L),
            15_000L,
            "IDR");

    GenericRecord decoded =
        AvroSerde.deserialize(AvroSerde.serialize(record), StocktakeCompletedSchema.schema());

    assertThat(decoded.get("stocktake_id").toString()).isEqualTo(stocktakeId.toString());
    assertThat(decoded.get("business_id").toString()).isEqualTo(businessId.toString());
    assertThat(decoded.get("counted_at")).isEqualTo(1_750_000_000_000L);
    assertThat(decoded.get("shrinkage_minor")).isEqualTo(15_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
  }

  @Test
  void schemaIsSelfCompatible() {
    Schema v1 = StocktakeCompletedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(v1, v1)).isTrue();
  }
}
