package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.barbershop.pricing.domain.PriceBreakdown;
import id.co.nativeapp.barbershop.ticket.messaging.TicketSaleRecordedSchema;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Test (f) — the {@code SaleRecorded} PRODUCER-copy contract test (no Spring context needed; rule
 * 7). Ported from carwash-service's {@code SaleRecordedContractTest}, adapted for ADR 0024: unlike
 * carwash (which still carries a legacy null-breakdown {@code wash.messaging.SaleRecordedSchema}
 * producer alongside its full-breakdown ticket producer), barbershop-service has NO legacy revenue
 * path — {@link TicketSaleRecordedSchema} is the ONLY {@code SaleRecorded} producer in this
 * service, so there is no "legacy vs. ticket" comparison to make.
 *
 * <p>barbershop emits {@code SaleRecorded} on the SAME Avro contract restaurant-service and
 * carwash-service already produce onto (the restaurant-service producer schema — the namespace
 * stays "restaurant", the historic producer-of-record, exactly as carwash's copy does). This proves
 * barbershop's copy parses, has the expected shape and full name, round-trips through {@code
 * libs/events AvroSerde} (the exact path the outbox serializes on), stays mutually
 * backward-compatible with the producer (restaurant) schema — so finance reads barbershop tickets
 * through the very same consumer path — and that the back-compat gate accepts an added-optional
 * field while rejecting a new required field with no default.
 *
 * <p>ADR 0027 (Phase 4, loyalty + gift cards): asserts the five trailing optional fields round-trip
 * when populated on a ticket record, and that the current schema stays backward-compatible with the
 * pre-Phase-4 shape (old-writer / new-reader).
 */
class SaleRecordedContractTest {

  /**
   * The restaurant-service producer schema (source of truth), inlined from docs/EVENT-CATALOG.md.
   */
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
                  {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
                ]
              }
              """);

  @Test
  void avscParsesWithTheExpectedShape() {
    Schema schema = TicketSaleRecordedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.restaurant.SaleRecorded");
    assertThat(schema.getField("sale_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("business_id")).isNotNull();
    assertThat(schema.getField("amount_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
    assertThat(schema.getField("occurred_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = TicketSaleRecordedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("sale_id", "33333333-3333-3333-3333-333333333333");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("business_id", "22222222-2222-2222-2222-222222222222"); // the barbershop outlet
    record.put("amount_minor", 4_500_000L); // money is integer minor units, never a float
    record.put("currency", "IDR");
    record.put("occurred_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("sale_id").toString()).isEqualTo("33333333-3333-3333-3333-333333333333");
    assertThat(decoded.get("amount_minor")).isEqualTo(4_500_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
  }

  @Test
  void barbershopCopyIsMutuallyBackwardCompatibleWithTheRestaurantProducerSchema() {
    Schema consumerCopy = TicketSaleRecordedSchema.schema();
    // finance reads barbershop's bytes with the producer schema, and vice versa — both directions
    // must be compatible so the SAME ledger path consolidates restaurant + carwash + barbershop.
    assertThat(AvroSerde.isBackwardCompatible(PRODUCER_SCHEMA, consumerCopy)).isTrue();
    assertThat(AvroSerde.isBackwardCompatible(consumerCopy, PRODUCER_SCHEMA)).isTrue();
  }

  @Test
  void addingAnOptionalFieldWithDefaultIsBackwardCompatible() {
    Schema v1 = TicketSaleRecordedSchema.schema();
    Schema v2 =
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
                    {"name": "channel", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, v2)).isTrue();
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema v1 = TicketSaleRecordedSchema.schema();
    Schema incompatible =
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
                    {"name": "attendant_id", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }

  /**
   * The ticket checkout populates the FULL price breakdown + {@code tender_type} — the only
   * producer shape this service ever writes (there is no legacy null-breakdown path in
   * barbershop-service, unlike carwash's grandfathered {@code POST /washes}).
   */
  @Test
  void ticketFullBreakdownProducerBuildsAValidRecordAgainstTheSharedSchema() {
    Schema schema = TicketSaleRecordedSchema.schema();

    PriceBreakdown breakdown =
        new PriceBreakdown(
            Money.ofMinor(70_000_00L, "IDR"),
            Money.ofMinor(0L, "IDR"),
            Money.ofMinor(70_000_00L, "IDR"),
            Money.ofMinor(0L, "IDR"),
            Money.ofMinor(7_700_00L, "IDR"),
            Money.ofMinor(77_700_00L, "IDR"),
            "ILLUSTRATIVE-2026.1",
            true);
    UUID saleId = UUID.randomUUID();
    UUID businessId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    GenericRecord record =
        TicketSaleRecordedSchema.toRecord(
            saleId,
            "11111111-1111-1111-1111-111111111111",
            businessId,
            breakdown.grandTotal(),
            Instant.parse("2026-06-14T08:30:00Z"),
            "CASH",
            breakdown);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);
    assertThat(decoded.get("sale_id").toString()).isEqualTo(saleId.toString());
    assertThat(decoded.get("business_id").toString()).isEqualTo(businessId.toString());
    assertThat(decoded.get("amount_minor")).isEqualTo(77_700_00L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("tender_type").toString()).isEqualTo("CASH");
    assertThat(decoded.get("subtotal_minor")).isEqualTo(70_000_00L);
    assertThat(decoded.get("tax_minor")).isEqualTo(7_700_00L);
    assertThat(decoded.get("tax_rule_version").toString()).isEqualTo("ILLUSTRATIVE-2026.1");
    assertThat(decoded.get("uses_illustrative_rules")).isEqualTo(true);
    // ADR 0036 (Phase B): the ticket producer explicitly nulls channel too (this wave never
    // threads a real channel — TicketSaleRecordedSchema.toRecord puts an explicit null).
    assertThat(decoded.get("channel")).isNull();

    // Still mutually backward-compatible with the restaurant producer schema — same shared
    // contract.
    assertThat(AvroSerde.isBackwardCompatible(PRODUCER_SCHEMA, schema)).isTrue();
    assertThat(AvroSerde.isBackwardCompatible(schema, PRODUCER_SCHEMA)).isTrue();
  }

  /**
   * The PRE-Phase-4 producer/consumer shape (13 fields, through {@code uses_illustrative_rules}) —
   * the schema every service ran before ADR 0027 (loyalty + gift cards) added the five trailing
   * optional fields.
   */
  private static final String PRE_PHASE4_SCHEMA_JSON =
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
      """;

  @Test
  void schemaCarriesTheFivePhase4LoyaltyAndGiftCardFields() {
    // ADR 0027 (Phase 4): loyalty_member_id, loyalty_redeemed_points, loyalty_redeemed_minor,
    // gift_card_id, gift_card_redeemed_minor — all ["null", <type>] with default null.
    Schema schema = TicketSaleRecordedSchema.schema();
    assertThat(schema.getField("loyalty_member_id").hasDefaultValue()).isTrue();
    assertThat(schema.getField("loyalty_redeemed_points").hasDefaultValue()).isTrue();
    assertThat(schema.getField("loyalty_redeemed_minor").hasDefaultValue()).isTrue();
    assertThat(schema.getField("gift_card_id").hasDefaultValue()).isTrue();
    assertThat(schema.getField("gift_card_redeemed_minor").hasDefaultValue()).isTrue();
  }

  @Test
  void schemaCarriesTheChannelField() {
    // ADR 0036 (Phase B): channel — ["null","string"] with default null, appended LAST.
    Schema schema = TicketSaleRecordedSchema.schema();
    assertThat(schema.getField("channel")).isNotNull();
    assertThat(schema.getField("channel").hasDefaultValue()).isTrue();
  }

  @Test
  void newReaderIsBackwardCompatibleWithThePrePhase4Schema() {
    // OLD-WRITER / NEW-READER: a reader on the current (Phase 4) schema must be able to read bytes
    // written by barbershop's own already-deployed pre-Phase-4 ticket producer.
    Schema oldWriter = new Schema.Parser().parse(PRE_PHASE4_SCHEMA_JSON);
    Schema newReader = TicketSaleRecordedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(oldWriter, newReader)).isTrue();
  }

  @Test
  void ticketProducerPhase4FieldsRoundTripWhenPopulated() {
    // Unlike the wash producer (carwash's legacy path), a ticket producer CAN populate the Phase 4
    // fields directly on a GenericRecord built from the shared schema (the loyalty/gift-card write
    // paths land in a later wave; this proves the WIRE shape is ready for them now).
    Schema schema = TicketSaleRecordedSchema.schema();
    PriceBreakdown breakdown =
        new PriceBreakdown(
            Money.ofMinor(70_000_00L, "IDR"),
            Money.ofMinor(0L, "IDR"),
            Money.ofMinor(70_000_00L, "IDR"),
            Money.ofMinor(0L, "IDR"),
            Money.ofMinor(7_700_00L, "IDR"),
            Money.ofMinor(77_700_00L, "IDR"),
            "ILLUSTRATIVE-2026.1",
            true);
    UUID saleId = UUID.randomUUID();
    UUID businessId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    GenericRecord record =
        TicketSaleRecordedSchema.toRecord(
            saleId,
            "11111111-1111-1111-1111-111111111111",
            businessId,
            breakdown.grandTotal(),
            Instant.parse("2026-06-14T08:30:00Z"),
            "CASH",
            breakdown);
    record.put("loyalty_member_id", "44444444-4444-4444-4444-444444444444");
    record.put("loyalty_redeemed_points", 500L);
    record.put("loyalty_redeemed_minor", 5_000_00L);
    record.put("gift_card_id", "55555555-5555-5555-5555-555555555555");
    record.put("gift_card_redeemed_minor", 10_000_00L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("loyalty_member_id").toString())
        .isEqualTo("44444444-4444-4444-4444-444444444444");
    assertThat(decoded.get("loyalty_redeemed_points")).isEqualTo(500L);
    assertThat(decoded.get("loyalty_redeemed_minor")).isEqualTo(5_000_00L);
    assertThat(decoded.get("gift_card_id").toString())
        .isEqualTo("55555555-5555-5555-5555-555555555555");
    assertThat(decoded.get("gift_card_redeemed_minor")).isEqualTo(10_000_00L);
    // ADR 0036 (Phase B): the ticket producer explicitly nulls channel in this wave.
    assertThat(decoded.get("channel")).isNull();
  }

  @Test
  void ticketProducerChannelDefaultsToNullInThisWaveButTheWireCarriesARealValue() {
    // No producer threads a real channel yet (that lands in Phase B2) — the ticket producer
    // always puts an explicit null. This proves both halves: the producer's own null, AND that
    // the shared schema round-trips a real channel value (the wire is ready for it now), mirroring
    // ticketProducerPhase4FieldsRoundTripWhenPopulated's proof for the Phase 4 fields.
    Schema schema = TicketSaleRecordedSchema.schema();
    PriceBreakdown breakdown =
        new PriceBreakdown(
            Money.ofMinor(70_000_00L, "IDR"),
            Money.ofMinor(0L, "IDR"),
            Money.ofMinor(70_000_00L, "IDR"),
            Money.ofMinor(0L, "IDR"),
            Money.ofMinor(7_700_00L, "IDR"),
            Money.ofMinor(77_700_00L, "IDR"),
            "ILLUSTRATIVE-2026.1",
            true);
    UUID saleId = UUID.randomUUID();
    UUID businessId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    GenericRecord producedByRealProducer =
        TicketSaleRecordedSchema.toRecord(
            saleId,
            "11111111-1111-1111-1111-111111111111",
            businessId,
            breakdown.grandTotal(),
            Instant.parse("2026-06-14T08:30:00Z"),
            "CASH",
            breakdown);
    byte[] realProducerBytes = AvroSerde.serialize(producedByRealProducer);
    assertThat(AvroSerde.deserialize(realProducerBytes, schema).get("channel")).isNull();

    // Independently, the WIRE format can carry a real channel value once Phase B2 ships.
    GenericRecord withChannel =
        TicketSaleRecordedSchema.toRecord(
            saleId,
            "11111111-1111-1111-1111-111111111111",
            businessId,
            breakdown.grandTotal(),
            Instant.parse("2026-06-14T08:30:00Z"),
            "CASH",
            breakdown);
    withChannel.put("channel", "GOFOOD");
    byte[] bytes = AvroSerde.serialize(withChannel);
    assertThat(AvroSerde.deserialize(bytes, schema).get("channel").toString())
        .isEqualTo("GOFOOD");
  }
}
