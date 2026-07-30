package id.co.nativeapp.loyalty;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.loyalty.ingest.dto.SaleRecordedFact;
import id.co.nativeapp.loyalty.ingest.messaging.SaleRecordedConsumerSchema;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code SaleRecorded} CONSUMER-side contract test (rule 7, HR-7 triad; no Spring context
 * needed). Unlike a per-service copy, loyalty-service resolves the SAME {@code
 * avro/SaleRecorded.avsc} classpath resource every producer (restaurant/carwash/barbershop) and
 * finance's consumer already resolve (ADR 0003) — this proves loyalty's decode path against that
 * shared schema, including the Phase-4 (ADR 0027) loyalty/gift-card fields and the
 * old-writer/new-reader evolution case, and pins the "no PII on this event" contract (rule 6:
 * {@code loyalty_member_id} is an opaque UUID, never the member's phone/name).
 */
class SaleRecordedConsumerContractTest {

  /** The full producer schema (source of truth), inlined from docs/EVENT-CATALOG.md. */
  private static final Schema PRODUCER_SCHEMA =
      new Schema.Parser()
          .parse(
              """
              {
                "type": "record",
                "name": "SaleRecorded",
                "namespace": "id.co.nativeapp.events.restaurant",
                "fields": [
                  {"name": "sale_id", "type": "string"},
                  {"name": "company_id", "type": "string"},
                  {"name": "business_id", "type": "string"},
                  {"name": "amount_minor", "type": "long"},
                  {"name": "currency", "type": "string"},
                  {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                  {"name": "tender_type", "type": ["null", "string"], "default": null},
                  {"name": "subtotal_minor", "type": ["null", "long"], "default": null},
                  {"name": "discount_minor", "type": ["null", "long"], "default": null},
                  {"name": "service_charge_minor", "type": ["null", "long"], "default": null},
                  {"name": "tax_minor", "type": ["null", "long"], "default": null},
                  {"name": "tax_rule_version", "type": ["null", "string"], "default": null},
                  {"name": "uses_illustrative_rules", "type": ["null", "boolean"], "default": null},
                  {"name": "loyalty_member_id", "type": ["null", "string"], "default": null},
                  {"name": "loyalty_redeemed_points", "type": ["null", "long"], "default": null},
                  {"name": "loyalty_redeemed_minor", "type": ["null", "long"], "default": null},
                  {"name": "gift_card_id", "type": ["null", "string"], "default": null},
                  {"name": "gift_card_redeemed_minor", "type": ["null", "long"], "default": null}
                ]
              }
              """);

  @Test
  void consumerCopyParsesWithTheExpectedShapeAndCarriesNoPii() {
    Schema schema = SaleRecordedConsumerSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.restaurant.SaleRecorded");
    assertThat(schema.getField("loyalty_member_id")).isNotNull();
    assertThat(schema.getField("gift_card_id")).isNotNull();
    // NO PII (rule 6): never the member's phone or display name.
    assertThat(schema.getField("phone")).isNull();
    assertThat(schema.getField("display_name")).isNull();
    assertThat(AvroSerde.isBackwardCompatible(PRODUCER_SCHEMA, schema)).isTrue();
    assertThat(AvroSerde.isBackwardCompatible(schema, PRODUCER_SCHEMA)).isTrue();
  }

  @Test
  void decodesAFullBreakdownRecordWithLoyaltyAndGiftCardFields() {
    Schema schema = SaleRecordedConsumerSchema.schema();
    UUID saleId = UUID.randomUUID();
    UUID businessId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID giftCardId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();

    GenericRecord record = new GenericData.Record(schema);
    record.put("sale_id", saleId.toString());
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("business_id", businessId.toString());
    record.put("amount_minor", 100_000L);
    record.put("currency", "IDR");
    record.put("occurred_at", Instant.now().toEpochMilli());
    record.put("tender_type", "CASH");
    record.put("subtotal_minor", 90_000L);
    record.put("discount_minor", 5_000L);
    record.put("service_charge_minor", 0L);
    record.put("tax_minor", 15_000L);
    record.put("tax_rule_version", null);
    record.put("uses_illustrative_rules", true);
    record.put("loyalty_member_id", memberId.toString());
    record.put("loyalty_redeemed_points", 100L);
    record.put("loyalty_redeemed_minor", 1_000L);
    record.put("gift_card_id", giftCardId.toString());
    record.put("gift_card_redeemed_minor", 2_000L);

    byte[] bytes = AvroSerde.serialize(record);
    SaleRecordedFact fact = SaleRecordedConsumerSchema.decode(eventId, bytes);

    assertThat(fact.eventId()).isEqualTo(eventId);
    assertThat(fact.saleId()).isEqualTo(saleId);
    assertThat(fact.businessId()).isEqualTo(businessId);
    assertThat(fact.amountMinor()).isEqualTo(100_000L);
    assertThat(fact.subtotalMinor()).isEqualTo(90_000L);
    assertThat(fact.discountMinor()).isEqualTo(5_000L);
    assertThat(fact.loyaltyMemberId()).isEqualTo(memberId);
    assertThat(fact.loyaltyRedeemedPoints()).isEqualTo(100L);
    assertThat(fact.loyaltyRedeemedMinor()).isEqualTo(1_000L);
    assertThat(fact.giftCardId()).isEqualTo(giftCardId);
    assertThat(fact.giftCardRedeemedMinor()).isEqualTo(2_000L);
  }

  @Test
  void oldWriterNewReaderDecodesAPrePhase4RecordWithAllFiveFieldsNull() {
    // OLD-WRITER / NEW-READER: a pre-Phase-4 producer's bytes (only through
    // uses_illustrative_rules) decode fine under the current (Phase-4) reader schema, the five
    // trailing fields defaulting to null.
    Schema oldWriter =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "SaleRecorded",
                  "namespace": "id.co.nativeapp.events.restaurant",
                  "fields": [
                    {"name": "sale_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "business_id", "type": "string"},
                    {"name": "amount_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "tender_type", "type": ["null", "string"], "default": null},
                    {"name": "subtotal_minor", "type": ["null", "long"], "default": null},
                    {"name": "discount_minor", "type": ["null", "long"], "default": null},
                    {"name": "service_charge_minor", "type": ["null", "long"], "default": null},
                    {"name": "tax_minor", "type": ["null", "long"], "default": null},
                    {"name": "tax_rule_version", "type": ["null", "string"], "default": null},
                    {"name": "uses_illustrative_rules", "type": ["null", "boolean"], "default": null}
                  ]
                }
                """);
    GenericRecord oldRecord = new GenericData.Record(oldWriter);
    oldRecord.put("sale_id", UUID.randomUUID().toString());
    oldRecord.put("company_id", "11111111-1111-1111-1111-111111111111");
    oldRecord.put("business_id", UUID.randomUUID().toString());
    oldRecord.put("amount_minor", 50_000L);
    oldRecord.put("currency", "IDR");
    oldRecord.put("occurred_at", Instant.now().toEpochMilli());
    oldRecord.put("tender_type", "CASH");
    oldRecord.put("subtotal_minor", null);
    oldRecord.put("discount_minor", null);
    oldRecord.put("service_charge_minor", null);
    oldRecord.put("tax_minor", null);
    oldRecord.put("tax_rule_version", null);
    oldRecord.put("uses_illustrative_rules", null);

    byte[] oldBytes = AvroSerde.serialize(oldRecord);
    org.apache.avro.generic.GenericRecord decoded =
        AvroSerde.deserialize(oldBytes, oldWriter, SaleRecordedConsumerSchema.schema());
    assertThat(decoded.get("loyalty_member_id")).isNull();
    assertThat(decoded.get("gift_card_id")).isNull();
  }
}
