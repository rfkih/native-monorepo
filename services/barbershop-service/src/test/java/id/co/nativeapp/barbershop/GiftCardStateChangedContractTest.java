package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.barbershop.loyaltyref.messaging.GiftCardStateChangedConsumerSchema;
import id.co.nativeapp.barbershop.loyaltyref.messaging.GiftCardStateChangedEvent;
import id.co.nativeapp.events.AvroSerde;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Consumer-driven contract test for the {@code GiftCardStateChanged} event (ADR 0027, Phase 4) —
 * ported from carwash-service's {@code GiftCardStateChangedContractTest}. loyalty-service is the
 * producer, barbershop-service the consumer.
 */
class GiftCardStateChangedContractTest {

  private static final String CARD_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String COMPANY_ID = "11111111-1111-1111-1111-111111111111";

  private static final String PRODUCER_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "GiftCardStateChanged",
        "namespace": "id.co.nativeapp.events.loyalty",
        "doc": "Emitted by loyalty-service whenever a gift card's state or balance changes.",
        "fields": [
          {"name": "gift_card_id", "type": "string"},
          {"name": "company_id", "type": "string"},
          {"name": "state", "type": "string"},
          {"name": "balance_minor", "type": "long"},
          {"name": "currency", "type": "string"},
          {"name": "balance_seq", "type": "long"},
          {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
        ]
      }
      """;

  @Test
  void schemaParsesFromClasspathWithExpectedShape() {
    Schema schema = GiftCardStateChangedConsumerSchema.schema();

    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.loyalty.GiftCardStateChanged");
    assertThat(schema.getField("gift_card_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("state")).isNotNull();
    assertThat(schema.getField("balance_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
    assertThat(schema.getField("balance_seq")).isNotNull();
    assertThat(schema.getField("occurred_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = GiftCardStateChangedConsumerSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("gift_card_id", CARD_ID);
    record.put("company_id", COMPANY_ID);
    record.put("state", "DEPLETED");
    record.put("balance_minor", 0L);
    record.put("currency", "IDR");
    record.put("balance_seq", 4L);
    record.put("occurred_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("gift_card_id").toString()).isEqualTo(CARD_ID);
    assertThat(decoded.get("state").toString()).isEqualTo("DEPLETED");
    assertThat(decoded.get("balance_minor")).isEqualTo(0L);
  }

  @Test
  void consumerCopyIsBackwardCompatibleWithProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = GiftCardStateChangedConsumerSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void producerBytesDecodeUnderConsumerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = GiftCardStateChangedConsumerSchema.schema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("gift_card_id", CARD_ID);
    produced.put("company_id", COMPANY_ID);
    produced.put("state", "ACTIVE");
    produced.put("balance_minor", 75_000L);
    produced.put("currency", "IDR");
    produced.put("balance_seq", 1L);
    produced.put("occurred_at", 1_750_000_000_000L);

    byte[] wire = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wire, producer, consumer);
    assertThat(decoded.get("state").toString()).isEqualTo("ACTIVE");
    assertThat(decoded.get("balance_minor")).isEqualTo(75_000L);
  }

  @Test
  void decodeProducesTheCorrectEvent() {
    Schema schema = GiftCardStateChangedConsumerSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("gift_card_id", CARD_ID);
    record.put("company_id", COMPANY_ID);
    record.put("state", "ACTIVE");
    record.put("balance_minor", 30_000L);
    record.put("currency", "IDR");
    record.put("balance_seq", 2L);
    record.put("occurred_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    UUID eventId = UUID.randomUUID();
    GiftCardStateChangedEvent event = GiftCardStateChangedConsumerSchema.decode(eventId, bytes);

    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.giftCardId()).isEqualTo(UUID.fromString(CARD_ID));
    assertThat(event.companyId()).isEqualTo(COMPANY_ID);
    assertThat(event.state()).isEqualTo("ACTIVE");
    assertThat(event.balanceMinor()).isEqualTo(30_000L);
    assertThat(event.currency()).isEqualTo("IDR");
    assertThat(event.balanceSeq()).isEqualTo(2L);
  }

  @Test
  void addingRequiredFieldBreaksBackwardCompatibility() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema broken =
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
                    {"name": "expires_at", "type": "long"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, broken)).isFalse();
  }
}
