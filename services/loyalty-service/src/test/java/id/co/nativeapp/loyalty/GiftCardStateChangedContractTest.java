package id.co.nativeapp.loyalty;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.loyalty.ledger.messaging.GiftCardStateChangedSchema;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code GiftCardStateChanged} PRODUCER-copy contract test (ADR 0027, HR-7 triad): parse +
 * shape, round-trip through {@code libs/events AvroSerde}, and the back-compat gate.
 */
class GiftCardStateChangedContractTest {

  @Test
  void avscParsesWithTheExpectedShape() {
    Schema schema = GiftCardStateChangedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.loyalty.GiftCardStateChanged");
    assertThat(schema.getField("gift_card_id")).isNotNull();
    assertThat(schema.getField("state")).isNotNull();
    assertThat(schema.getField("balance_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
    assertThat(schema.getField("balance_seq").schema().getType()).isEqualTo(Schema.Type.LONG);
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    UUID cardId = UUID.randomUUID();
    GenericRecord record =
        GiftCardStateChangedSchema.toRecord(
            cardId, "11111111-1111-1111-1111-111111111111", "DEPLETED", 0L, "IDR", 2L, Instant.now());

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, GiftCardStateChangedSchema.schema());

    assertThat(decoded.get("gift_card_id").toString()).isEqualTo(cardId.toString());
    assertThat(decoded.get("state").toString()).isEqualTo("DEPLETED");
    assertThat(decoded.get("balance_minor")).isEqualTo(0L);
    assertThat(decoded.get("balance_seq")).isEqualTo(2L);
  }

  @Test
  void addingAnOptionalFieldWithDefaultIsBackwardCompatible() {
    Schema v1 = GiftCardStateChangedSchema.schema();
    Schema v2 =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "GiftCardStateChanged",
                  "namespace": "id.co.nativeapp.events.loyalty",
                  "fields": [
                    {"name": "gift_card_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "state", "type": "string"},
                    {"name": "balance_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "balance_seq", "type": "long"},
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "note", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, v2)).isTrue();
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema v1 = GiftCardStateChangedSchema.schema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "GiftCardStateChanged",
                  "namespace": "id.co.nativeapp.events.loyalty",
                  "fields": [
                    {"name": "gift_card_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "state", "type": "string"},
                    {"name": "balance_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "balance_seq", "type": "long"},
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "issuer", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }
}
