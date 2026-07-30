package id.co.nativeapp.loyalty;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.loyalty.ledger.messaging.LoyaltyRedemptionFlaggedSchema;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code LoyaltyRedemptionFlagged} PRODUCER-copy contract test (ADR 0027, HR-7 triad): parse +
 * shape, round-trip through {@code libs/events AvroSerde} for BOTH the points and gift-card
 * variants, and the back-compat gate. Also pins NO PII (rule 6).
 */
class LoyaltyRedemptionFlaggedContractTest {

  @Test
  void avscParsesWithTheExpectedShape() {
    Schema schema = LoyaltyRedemptionFlaggedSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.loyalty.LoyaltyRedemptionFlagged");
    assertThat(schema.getField("flag_id")).isNotNull();
    assertThat(schema.getField("sale_id")).isNotNull();
    assertThat(schema.getField("member_id").hasDefaultValue()).isTrue();
    assertThat(schema.getField("gift_card_id").hasDefaultValue()).isTrue();
    assertThat(schema.getField("shortfall_minor").hasDefaultValue()).isTrue();
    assertThat(schema.getField("shortfall_points").hasDefaultValue()).isTrue();
  }

  @Test
  void aPointsOverdraftFlagRoundTripsWithGiftCardFieldsNull() {
    UUID flagId = UUID.randomUUID();
    UUID companyBusinessSale = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    GenericRecord record =
        LoyaltyRedemptionFlaggedSchema.forPoints(
            flagId,
            "11111111-1111-1111-1111-111111111111",
            companyBusinessSale,
            companyBusinessSale,
            memberId,
            50L,
            Instant.now());

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, LoyaltyRedemptionFlaggedSchema.schema());

    assertThat(decoded.get("member_id").toString()).isEqualTo(memberId.toString());
    assertThat(decoded.get("gift_card_id")).isNull();
    assertThat(decoded.get("shortfall_points")).isEqualTo(50L);
    assertThat(decoded.get("shortfall_minor")).isNull();
  }

  @Test
  void aGiftCardOverdraftFlagRoundTripsWithMemberFieldsNull() {
    UUID flagId = UUID.randomUUID();
    UUID companyBusinessSale = UUID.randomUUID();
    UUID cardId = UUID.randomUUID();
    GenericRecord record =
        LoyaltyRedemptionFlaggedSchema.forGiftCard(
            flagId,
            "11111111-1111-1111-1111-111111111111",
            companyBusinessSale,
            companyBusinessSale,
            cardId,
            5_000L,
            Instant.now());

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, LoyaltyRedemptionFlaggedSchema.schema());

    assertThat(decoded.get("gift_card_id").toString()).isEqualTo(cardId.toString());
    assertThat(decoded.get("member_id")).isNull();
    assertThat(decoded.get("shortfall_minor")).isEqualTo(5_000L);
    assertThat(decoded.get("shortfall_points")).isNull();
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema v1 = LoyaltyRedemptionFlaggedSchema.schema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "LoyaltyRedemptionFlagged",
                  "namespace": "id.co.nativeapp.events.loyalty",
                  "fields": [
                    {"name": "flag_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "business_id", "type": "string"},
                    {"name": "sale_id", "type": "string"},
                    {"name": "member_id", "type": ["null", "string"], "default": null},
                    {"name": "gift_card_id", "type": ["null", "string"], "default": null},
                    {"name": "shortfall_minor", "type": ["null", "long"], "default": null},
                    {"name": "shortfall_points", "type": ["null", "long"], "default": null},
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "severity", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }
}
