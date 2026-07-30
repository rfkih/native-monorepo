package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.carwash.loyaltyref.messaging.LoyaltyBalanceChangedConsumerSchema;
import id.co.nativeapp.carwash.loyaltyref.messaging.LoyaltyBalanceChangedEvent;
import id.co.nativeapp.events.AvroSerde;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Consumer-driven contract test for the {@code LoyaltyBalanceChanged} event (ADR 0027, Phase 4) —
 * ported from restaurant-service's {@code LoyaltyBalanceChangedContractTest}. loyalty-service is
 * the producer, carwash-service the consumer.
 *
 * <p>Verifies the rule-7 triad (CLAUDE.md rule 7 / docs/EVENT-CATALOG.md): schema shape, AvroSerde
 * round-trip, backward compatibility with the producer schema, {@code decode()} correctness, and
 * that a breaking (required, no-default) field addition is correctly rejected.
 */
class LoyaltyBalanceChangedContractTest {

  private static final String MEMBER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String COMPANY_ID = "11111111-1111-1111-1111-111111111111";

  private static final String PRODUCER_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "LoyaltyBalanceChanged",
        "namespace": "id.co.nativeapp.events.loyalty",
        "doc": "Emitted by loyalty-service whenever a member's points balance changes.",
        "fields": [
          {"name": "member_id", "type": "string"},
          {"name": "company_id", "type": "string"},
          {"name": "points_balance", "type": "long"},
          {"name": "balance_seq", "type": "long"},
          {"name": "reason", "type": "string"},
          {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
        ]
      }
      """;

  @Test
  void schemaParsesFromClasspathWithExpectedShape() {
    Schema schema = LoyaltyBalanceChangedConsumerSchema.schema();

    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.loyalty.LoyaltyBalanceChanged");
    assertThat(schema.getField("member_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("points_balance")).isNotNull();
    assertThat(schema.getField("balance_seq")).isNotNull();
    assertThat(schema.getField("reason")).isNotNull();
    assertThat(schema.getField("occurred_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = LoyaltyBalanceChangedConsumerSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("member_id", MEMBER_ID);
    record.put("company_id", COMPANY_ID);
    record.put("points_balance", 2_500L);
    record.put("balance_seq", 7L);
    record.put("reason", "REDEEMED");
    record.put("occurred_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("member_id").toString()).isEqualTo(MEMBER_ID);
    assertThat(decoded.get("points_balance")).isEqualTo(2_500L);
    assertThat(decoded.get("balance_seq")).isEqualTo(7L);
  }

  @Test
  void consumerCopyIsBackwardCompatibleWithProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = LoyaltyBalanceChangedConsumerSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void producerBytesDecodeUnderConsumerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = LoyaltyBalanceChangedConsumerSchema.schema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("member_id", MEMBER_ID);
    produced.put("company_id", COMPANY_ID);
    produced.put("points_balance", 900L);
    produced.put("balance_seq", 1L);
    produced.put("reason", "ENROLLED");
    produced.put("occurred_at", 1_750_000_000_000L);

    byte[] wire = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wire, producer, consumer);
    assertThat(decoded.get("member_id").toString()).isEqualTo(MEMBER_ID);
    assertThat(decoded.get("points_balance")).isEqualTo(900L);
  }

  @Test
  void decodeProducesTheCorrectEvent() {
    Schema schema = LoyaltyBalanceChangedConsumerSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("member_id", MEMBER_ID);
    record.put("company_id", COMPANY_ID);
    record.put("points_balance", 4_200L);
    record.put("balance_seq", 9L);
    record.put("reason", "ADJUSTED");
    record.put("occurred_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    UUID eventId = UUID.randomUUID();
    LoyaltyBalanceChangedEvent event = LoyaltyBalanceChangedConsumerSchema.decode(eventId, bytes);

    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.memberId()).isEqualTo(UUID.fromString(MEMBER_ID));
    assertThat(event.companyId()).isEqualTo(COMPANY_ID);
    assertThat(event.pointsBalance()).isEqualTo(4_200L);
    assertThat(event.balanceSeq()).isEqualTo(9L);
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
                  "name": "LoyaltyBalanceChanged",
                  "namespace": "id.co.nativeapp.events.loyalty",
                  "fields": [
                    {"name": "member_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "points_balance", "type": "long"},
                    {"name": "balance_seq", "type": "long"},
                    {"name": "reason", "type": "string"},
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "expires_at", "type": "long"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, broken)).isFalse();
  }
}
