package id.co.nativeapp.loyalty;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.loyalty.ingest.dto.GiftCardSoldFact;
import id.co.nativeapp.loyalty.ingest.messaging.GiftCardSoldConsumerSchema;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code GiftCardSold} CONSUMER-side contract test (rule 7, HR-7 triad) — loyalty-service is
 * the FIRST consumer to build production decode machinery against this schema (the catalog notes
 * "no vertical write path populates these fields yet" for the producer side, and "loyalty-service
 * once it is scaffolded" for the consumer side — this wave IS that scaffolding).
 */
class GiftCardSoldConsumerContractTest {

  private static final Schema PRODUCER_SCHEMA =
      new Schema.Parser()
          .parse(
              """
              {
                "type": "record",
                "name": "GiftCardSold",
                "namespace": "id.co.nativeapp.events.restaurant",
                "fields": [
                  {"name": "gift_card_sale_id", "type": "string"},
                  {"name": "gift_card_id", "type": "string"},
                  {"name": "company_id", "type": "string"},
                  {"name": "business_id", "type": "string"},
                  {"name": "amount_minor", "type": "long"},
                  {"name": "currency", "type": "string"},
                  {"name": "tender_type", "type": ["null", "string"], "default": null},
                  {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
                ]
              }
              """);

  @Test
  void consumerCopyParsesWithTheExpectedShape() {
    Schema schema = GiftCardSoldConsumerSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.restaurant.GiftCardSold");
    assertThat(schema.getField("gift_card_sale_id")).isNotNull();
    assertThat(schema.getField("gift_card_id")).isNotNull();
    assertThat(AvroSerde.isBackwardCompatible(PRODUCER_SCHEMA, schema)).isTrue();
    assertThat(AvroSerde.isBackwardCompatible(schema, PRODUCER_SCHEMA)).isTrue();
  }

  @Test
  void decodesAGiftCardSoldRecord() {
    Schema schema = GiftCardSoldConsumerSchema.schema();
    UUID giftCardId = UUID.randomUUID();
    UUID businessId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();

    GenericRecord record = new GenericData.Record(schema);
    record.put("gift_card_sale_id", UUID.randomUUID().toString());
    record.put("gift_card_id", giftCardId.toString());
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("business_id", businessId.toString());
    record.put("amount_minor", 100_000L);
    record.put("currency", "IDR");
    record.put("tender_type", "CASH");
    record.put("occurred_at", Instant.now().toEpochMilli());

    byte[] bytes = AvroSerde.serialize(record);
    GiftCardSoldFact fact = GiftCardSoldConsumerSchema.decode(eventId, bytes);

    assertThat(fact.eventId()).isEqualTo(eventId);
    assertThat(fact.giftCardId()).isEqualTo(giftCardId);
    assertThat(fact.businessId()).isEqualTo(businessId);
    assertThat(fact.amountMinor()).isEqualTo(100_000L);
    assertThat(fact.currency()).isEqualTo("IDR");
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema v1 = GiftCardSoldConsumerSchema.schema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "GiftCardSold",
                  "namespace": "id.co.nativeapp.events.restaurant",
                  "fields": [
                    {"name": "gift_card_sale_id", "type": "string"},
                    {"name": "gift_card_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "business_id", "type": "string"},
                    {"name": "amount_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "tender_type", "type": ["null", "string"], "default": null},
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "purchaser_ref", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }
}
