package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.giftcard.messaging.GiftCardSoldEvent;
import id.co.nativeapp.finance.giftcard.messaging.GiftCardSoldSchema;
import id.co.nativeapp.money.Money;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Consumer contract test for the {@code GiftCardSold} schema (ADR 0027, Phase 4 of the POS-parity
 * program) — the "real contract tests land ... in the finance consumer copy's own test" follow-up
 * the event catalog's schema-only wave flagged (docs/EVENT-CATALOG.md), now that finance builds its
 * consumer machinery ({@code GiftCardSoldListener} / {@code GiftCardPostingWriter}).
 *
 * <p>Asserts the schema parses from the classpath, has the expected shape, round-trips through
 * {@link AvroSerde}, decodes into a {@link GiftCardSoldEvent} via {@link GiftCardSoldSchema#decode},
 * and stays backward-compatible with a producer schema that predates the optional {@code
 * tender_type} field (defaults to {@code null} when absent).
 */
class GiftCardSoldContractTest {

  private static final String ORIGINAL_PRODUCER_SCHEMA_JSON =
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
          {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
        ]
      }
      """;

  @Test
  void consumerAvscParsesFromClasspath() {
    Schema schema = GiftCardSoldSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.restaurant.GiftCardSold");
    assertThat(schema.getField("gift_card_sale_id")).isNotNull();
    assertThat(schema.getField("gift_card_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("business_id")).isNotNull();
    assertThat(schema.getField("amount_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
    assertThat(schema.getField("occurred_at").schema().getLogicalType()).isNotNull();
    assertThat(schema.getField("tender_type")).isNotNull();
    assertThat(schema.getField("tender_type").schema().getType()).isEqualTo(Schema.Type.UNION);
    assertThat(schema.getField("tender_type").hasDefaultValue()).isTrue();
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = GiftCardSoldSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("gift_card_sale_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    record.put("gift_card_id", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    record.put("company_id", "cccccccc-cccc-cccc-cccc-cccccccccccc");
    record.put("business_id", "dddddddd-dddd-dddd-dddd-dddddddddddd");
    record.put("amount_minor", 100_000L);
    record.put("currency", "IDR");
    record.put("occurred_at", 1_750_000_000_000L);
    record.put("tender_type", "QRIS");

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("gift_card_sale_id").toString())
        .isEqualTo("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    assertThat(decoded.get("amount_minor")).isEqualTo(100_000L);
    assertThat(decoded.get("tender_type").toString()).isEqualTo("QRIS");
  }

  @Test
  void tenderTypeNullRoundTrips() {
    Schema schema = GiftCardSoldSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("gift_card_sale_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    record.put("gift_card_id", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    record.put("company_id", "cccccccc-cccc-cccc-cccc-cccccccccccc");
    record.put("business_id", "dddddddd-dddd-dddd-dddd-dddddddddddd");
    record.put("amount_minor", 100_000L);
    record.put("currency", "IDR");
    record.put("occurred_at", 1_750_000_000_000L);
    record.put("tender_type", null);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("tender_type")).isNull();
  }

  @Test
  void decodeBuildsTheGiftCardSoldEventFromWireBytes() {
    Schema schema = GiftCardSoldSchema.schema();
    UUID giftCardSaleId = UUID.randomUUID();
    UUID giftCardId = UUID.randomUUID();
    UUID businessId = UUID.randomUUID();
    String companyId = UUID.randomUUID().toString();

    GenericRecord record = new GenericData.Record(schema);
    record.put("gift_card_sale_id", giftCardSaleId.toString());
    record.put("gift_card_id", giftCardId.toString());
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("amount_minor", 250_000L);
    record.put("currency", "IDR");
    record.put("occurred_at", 1_750_000_000_000L);
    record.put("tender_type", "CARD");

    byte[] bytes = AvroSerde.serialize(record);
    // The header-derived eventId is unused by decode (gift_card_sale_id comes from the payload,
    // mirroring SaleVoidedSchema/SaleRefundedSchema) — pass an arbitrary UUID to prove that.
    GiftCardSoldEvent event = GiftCardSoldSchema.decode(UUID.randomUUID(), bytes);

    assertThat(event.giftCardSaleId()).isEqualTo(giftCardSaleId);
    assertThat(event.giftCardId()).isEqualTo(giftCardId);
    assertThat(event.companyId()).isEqualTo(companyId);
    assertThat(event.businessId()).isEqualTo(businessId);
    assertThat(event.amount()).isEqualTo(Money.ofMinor(250_000L, "IDR"));
    assertThat(event.tenderType()).isEqualTo("CARD");
  }

  @Test
  void backwardCompatibleWithOldProducerWithoutTenderType() {
    Schema producer = new Schema.Parser().parse(ORIGINAL_PRODUCER_SCHEMA_JSON);
    Schema consumer = GiftCardSoldSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }
}
