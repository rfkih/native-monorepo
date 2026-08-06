package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.stocktake.messaging.StocktakeCompletedEvent;
import id.co.nativeapp.finance.stocktake.messaging.StocktakeCompletedSchema;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Contract test for {@code StocktakeCompleted} (ADR 0038 phase 3) — consumer side, no Spring
 * context. Proves the single-source {@code .avsc} (libs/contracts) parses on finance's classpath,
 * producer-shaped bytes decode into {@link StocktakeCompletedEvent}, {@link
 * StocktakeCompletedEvent#assertValid()} accepts a valid event and rejects the two dangerous
 * figures (the poison/DLT path), and schema evolution stays backward-compatible in both directions
 * (CLAUDE.md rule 7).
 */
class StocktakeCompletedContractTest {

  private static final UUID EVENT_ID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000002");

  private static GenericRecord producerRecord(long shrinkageMinor, String currency) {
    GenericRecord record = new GenericData.Record(StocktakeCompletedSchema.schema());
    record.put("stocktake_id", "aaaaaaaa-1111-2222-3333-444444444444");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("business_id", "22222222-2222-2222-2222-222222222222");
    record.put("counted_at", 1_750_000_000_000L);
    record.put("shrinkage_minor", shrinkageMinor);
    record.put("currency", currency);
    return record;
  }

  @Test
  void producerBytesDecodeAndValidLossPasses() {
    byte[] bytes = AvroSerde.serialize(producerRecord(75_000L, "IDR"));
    GenericRecord decoded = AvroSerde.deserialize(bytes, StocktakeCompletedSchema.schema());
    StocktakeCompletedEvent event = StocktakeCompletedEvent.from(EVENT_ID, decoded);

    event.assertValid(); // must not throw
    assertThat(event.companyId()).isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(event.shrinkageMinor()).isEqualTo(75_000L);
    assertThat(event.currency()).isEqualTo("IDR");
  }

  @Test
  void negativeShrinkageIsAValidGain() {
    byte[] bytes = AvroSerde.serialize(producerRecord(-40_000L, "IDR"));
    StocktakeCompletedEvent event =
        StocktakeCompletedEvent.from(
            EVENT_ID, AvroSerde.deserialize(bytes, StocktakeCompletedSchema.schema()));
    event.assertValid(); // must not throw — a signed gain is valid
    assertThat(event.shrinkageMinor()).isEqualTo(-40_000L);
  }

  @Test
  void blankCurrencyIsPoison() {
    byte[] bytes = AvroSerde.serialize(producerRecord(1_000L, ""));
    StocktakeCompletedEvent event =
        StocktakeCompletedEvent.from(
            EVENT_ID, AvroSerde.deserialize(bytes, StocktakeCompletedSchema.schema()));
    assertThatThrownBy(event::assertValid)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("blank currency");
  }

  @Test
  void longMinValueShrinkageIsPoison() {
    byte[] bytes = AvroSerde.serialize(producerRecord(Long.MIN_VALUE, "IDR"));
    StocktakeCompletedEvent event =
        StocktakeCompletedEvent.from(
            EVENT_ID, AvroSerde.deserialize(bytes, StocktakeCompletedSchema.schema()));
    assertThatThrownBy(event::assertValid)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Long.MIN_VALUE");
  }

  @Test
  void evolutionStaysBackwardCompatibleBothDirections() {
    Schema v1 = StocktakeCompletedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(v1, v1)).isTrue();
    // v2 = v1 + trailing optional field: a v2 reader decodes v1 bytes (default), and a v1 reader
    // decodes v2 bytes (unknown trailing field dropped) — rule 7's both-direction gate.
    Schema v2 =
        new Schema.Parser()
            .parse(
                v1.toString()
                    .replaceFirst(
                        "\\]\\}$",
                        ",{\"name\":\"note\",\"type\":[\"null\",\"string\"],"
                            + "\"default\":null}]}"));
    assertThat(AvroSerde.isBackwardCompatible(v1, v2)).isTrue();
    assertThat(AvroSerde.isBackwardCompatible(v2, v1)).isTrue();
  }
}
